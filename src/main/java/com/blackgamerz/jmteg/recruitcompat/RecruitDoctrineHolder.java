package com.blackgamerz.jmteg.recruitcompat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Central registry for {@link RecruitDoctrine} assignments.
 *
 * <h3>Storage layers</h3>
 * <ol>
 *   <li><b>Runtime cache</b> – a {@link WeakHashMap} indexed by mob reference for
 *       fast, per-tick access.  The map is intentionally weak so GC-collected or
 *       unloaded entities do not prevent collection.</li>
 *   <li><b>Mob persistent data (NBT)</b> – the doctrine name is written to
 *       {@link net.minecraft.world.entity.Entity#getPersistentData()} under
 *       {@link RecruitDoctrine#NBT_KEY} so it survives server reloads and
 *       dimension travel.</li>
 *   <li><b>Commander inheritance</b> – when a recruit has no individual doctrine the
 *       holder tries to read the doctrine from the mob's assigned CommanderEntity
 *       (detected via reflection: {@code getCommander}, {@code getCommanderEntity},
 *       {@code getLeader}, {@code getFormationLeader}).  This lets a player issue a
 *       single {@code /jmteg doctrine} command to change the behaviour of an entire
 *       squad at once.</li>
 * </ol>
 *
 * <h3>Player controls</h3>
 * <ul>
 *   <li><b>Shift + right-click</b> an owned recruit with an empty main hand to cycle
 *       through all five doctrines.  A short chat message confirms the selection.</li>
 *   <li><b>{@code /jmteg doctrine &lt;NAME&gt;}</b> stores the chosen doctrine on
 *       the executing player's persistent data so all of their assigned recruits
 *       inherit it.  Accepts tab-completion.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecruitDoctrineHolder {

    private static final Logger LOGGER = LogManager.getLogger("JMTEG-Doctrine");

    /** Reflection method names tried (in order) when looking for a commander entity. */
    private static final String[] COMMANDER_METHODS = {
            "getCommander", "getCommanderEntity", "getLeader", "getFormationLeader",
    };

    /** Method names tried when checking whether a player is the mob's owner. */
    private static final String[] OWNER_UUID_METHODS = {
            "getOwnerUUID", "getOwnerId", "getOwnerUniqueId",
    };

    private static final String[] OWNER_ENTITY_METHODS = {
            "getOwner", "getMaster",
    };

    /**
     * Runtime cache: mob → doctrine.  WeakHashMap prevents memory leaks when
     * entities are GC-collected after unloading.  Access is synchronised via
     * {@link Collections#synchronizedMap}.
     */
    private static final Map<PathfinderMob, RecruitDoctrine> cache =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RecruitDoctrineHolder() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the active {@link RecruitDoctrine} for this recruit, checking (in
     * priority order):
     * <ol>
     *   <li>Runtime cache (fast path, avoids repeated NBT reads)</li>
     *   <li>The mob's own persistent-data NBT</li>
     *   <li>The commander entity's persistent-data NBT (squad inheritance)</li>
     * </ol>
     * Returns {@code null} when no doctrine is set at any level.
     *
     * @param mob the recruit; {@code null} returns {@code null}
     */
    public static RecruitDoctrine getDoctrine(PathfinderMob mob) {
        if (mob == null) return null;

        // 1. Runtime cache
        RecruitDoctrine cached = cache.get(mob);
        if (cached != null) return cached;

        // 2. Mob's own persistent NBT
        String stored = mob.getPersistentData().getString(RecruitDoctrine.NBT_KEY);
        if (!stored.isEmpty()) {
            RecruitDoctrine parsed = RecruitDoctrine.fromName(stored);
            if (parsed != null) {
                cache.put(mob, parsed);
                return parsed;
            }
        }

        // 3. Commander inheritance (squad-level doctrine)
        return tryInheritFromCommander(mob);
    }

    /**
     * Assigns {@code doctrine} to this recruit, persisting the choice in NBT.
     * Pass {@code null} to clear the individual doctrine (the recruit will then
     * inherit from its commander, if any).
     *
     * @param mob      the recruit; ignored when {@code null}
     * @param doctrine the doctrine to apply, or {@code null} to clear
     */
    public static void setDoctrine(PathfinderMob mob, RecruitDoctrine doctrine) {
        if (mob == null) return;
        if (doctrine == null) {
            cache.remove(mob);
            mob.getPersistentData().remove(RecruitDoctrine.NBT_KEY);
        } else {
            cache.put(mob, doctrine);
            mob.getPersistentData().putString(RecruitDoctrine.NBT_KEY, doctrine.name());
        }
    }

    // ── Commander inheritance ─────────────────────────────────────────────────

    /**
     * Attempts to resolve a doctrine from the mob's CommanderEntity (if any)
     * using soft-dependency reflection.
     * Returns {@code null} if no commander is found or the commander has no doctrine.
     */
    private static RecruitDoctrine tryInheritFromCommander(PathfinderMob mob) {
        for (String methodName : COMMANDER_METHODS) {
            try {
                Method m = findMethod(mob, methodName);
                if (m == null) continue;
                Object result = m.invoke(mob);
                if (!(result instanceof net.minecraft.world.entity.Entity commander)) continue;
                String stored = commander.getPersistentData().getString(RecruitDoctrine.NBT_KEY);
                if (!stored.isEmpty()) {
                    RecruitDoctrine d = RecruitDoctrine.fromName(stored);
                    if (d != null) {
                        LOGGER.debug("{} inherited doctrine {} from commander {}",
                                mob, d.name(), commander);
                        return d;
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ── Shift-right-click interaction ─────────────────────────────────────────

    /**
     * Cycles the doctrine of a recruit when its owner shift-right-clicks it
     * with an empty main hand.
     *
     * <p>The interaction is cancelled so vanilla trade/follow screens are not
     * opened when the player is cycling doctrines.  Only the mob's direct owner
     * can do this.</p>
     */
    @SubscribeEvent
    public static void onPlayerInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        if (!player.isShiftKeyDown()) return;
        if (!player.getMainHandItem().isEmpty()) return;
        if (!(event.getTarget() instanceof PathfinderMob recruit)) return;
        if (!RecruitOwnershipHelper.isAmmoAwareRecruit(recruit)) return;
        if (!isOwnedByPlayer(recruit, player)) return;

        // Determine the recruit's current personal doctrine (ignoring inherited ones)
        String personal = recruit.getPersistentData().getString(RecruitDoctrine.NBT_KEY);
        RecruitDoctrine personalDoctrine = personal.isEmpty()
                ? null
                : RecruitDoctrine.fromName(personal);

        // Cycle: null → first doctrine, then next, then wrap around
        RecruitDoctrine next = personalDoctrine == null
                ? RecruitDoctrine.values()[0]
                : personalDoctrine.next();

        setDoctrine(recruit, next);
        player.sendSystemMessage(Component.literal(
                "§e[Recruit] Doctrine: §a" + next.displayName
                + " §7(" + describeDoctrineShort(next) + ")"));
        event.setCanceled(true);
    }

    // ── Command: /jmteg doctrine <NAME> ──────────────────────────────────────

    /**
     * Registers the {@code /jmteg doctrine <NAME>} command.
     *
     * <p>Running this command stores the chosen doctrine on the executing entity's
     * (usually the player's) persistent data.  All of their assigned recruits that
     * have no individual doctrine will automatically inherit it on the next
     * profile-refresh tick inside {@link RecruitRangedGunnerAttackGoal}.</p>
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
            Commands.literal("jmteg")
                .then(Commands.literal("doctrine")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (RecruitDoctrine d : RecruitDoctrine.values()) {
                                builder.suggest(d.name());
                            }
                            return builder.buildFuture();
                        })
                        .executes(RecruitDoctrineHolder::executeDoctrineCommand)
                    )
                )
        );
    }

    private static int executeDoctrineCommand(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        RecruitDoctrine doctrine = RecruitDoctrine.fromName(name);
        if (doctrine == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Unknown doctrine '" + name + "'. "
                    + "Valid values: AGGRESSIVE, DEFENSIVE, SKIRMISHER, SIEGE, ESCORT"));
            return 0;
        }

        // Store on the source entity (player in almost all cases) so their squad inherits it
        try {
            net.minecraft.world.entity.Entity sourceEntity = ctx.getSource().getEntityOrException();
            sourceEntity.getPersistentData().putString(RecruitDoctrine.NBT_KEY, doctrine.name());
            ctx.getSource().sendSuccess(
                    () -> Component.literal(
                            "§e[JMTEG] Squad doctrine set to: §a" + doctrine.displayName
                            + " §7(" + describeDoctrineShort(doctrine) + ")"),
                    true);
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "This command must be run by an entity (not the console)."));
            return 0;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when {@code player} is confirmed as the direct owner
     * of {@code mob} via soft-dependency reflection (UUID and live-entity checks).
     */
    private static boolean isOwnedByPlayer(PathfinderMob mob, Player player) {
        for (String m : OWNER_UUID_METHODS) {
            try {
                Method method = findMethod(mob, m);
                if (method == null) continue;
                Object result = method.invoke(mob);
                if (result instanceof java.util.UUID uuid && uuid.equals(player.getUUID())) return true;
            } catch (Exception ignored) {}
        }
        for (String m : OWNER_ENTITY_METHODS) {
            try {
                Method method = findMethod(mob, m);
                if (method == null) continue;
                Object result = method.invoke(mob);
                if (result instanceof net.minecraft.world.entity.LivingEntity le
                        && le.getUUID().equals(player.getUUID())) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * Walks the class hierarchy of {@code obj} looking for a no-arg method with
     * the given name.  Returns {@code null} (never throws) when not found.
     */
    private static Method findMethod(Object obj, String name) {
        try { return obj.getClass().getMethod(name); }
        catch (NoSuchMethodException ignored) {}
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    /** One-line flavour description used in chat and command feedback messages. */
    private static String describeDoctrineShort(RecruitDoctrine d) {
        return switch (d) {
            case AGGRESSIVE -> "close range, fires freely";
            case DEFENSIVE  -> "long range, conserves ammo";
            case SKIRMISHER -> "mobile hit-and-run";
            case SIEGE      -> "maximum range, precise shots";
            case ESCORT     -> "close protection";
        };
    }
}
