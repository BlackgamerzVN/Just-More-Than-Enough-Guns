package com.blackgamerz.jmteg.recruitcompat;

import com.blackgamerz.jmteg.Main;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side integrator that maps Recruits movement/command actions to JMTEG doctrines.
 *
 * Behavior summary:
 *  - Runs every SCAN_INTERVAL_TICKS ticks.
 *  - Scans only in the vicinity of online players (player bounding box inflated by PLAYER_SCAN_RADIUS).
 *  - Uses reflection and class-name checks to detect Talhanation Recruits entities without
 *    creating a hard dependency on that mod.
 *  - Is the sole, authoritative source of a recruit's doctrine: whenever the owner issues a
 *    movement/follow command (tracked via {@code getFollowState()}), the mapped doctrine is
 *    written straight to {@link RecruitDoctrineHolder}. There is no manual click-to-cycle
 *    override anymore, so a recruit's tactical behaviour always follows its current orders.
 *  - Optionally verifies the recruit is affected by the player who issued the command by calling
 *    isEffectedByCommand(UUID) reflectively.
 *
 * Tune the constants below or wire them to a Forge server config later.
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE, modid = Main.MOD_ID)
public final class RecruitMovementDoctrineIntegrator {
    private static final Logger LOGGER = LogManager.getLogger("JMTEG-RecruitIntegrator");

    // Tuning
    private static final int SCAN_INTERVAL_TICKS = 10;      // how often we run the scan
    private static final double PLAYER_SCAN_RADIUS = 100.0; // matches Recruits' packet-range

    // If true, require recruit.isEffectedByCommand(playerUUID) to be true before applying doctrine for that player.
    private static final boolean REQUIRE_EFFECTED_BY_COMMAND = true;

    // Internal caches
    private static final Map<PathfinderMob, Integer> lastKnownState = new WeakHashMap<>();
    // Keyed by (class, methodName) so distinct reflective lookups (isEffectedByCommand vs.
    // getFollowState) on the same recruit class don't evict one another.
    private static final Map<Class<?>, Map<String, Method>> methodCache = new ConcurrentHashMap<>();

    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        tickCounter = (tickCounter + 1) % SCAN_INTERVAL_TICKS;
        if (tickCounter != 0) return;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer player : players) {
            try {
                scanAroundPlayer(player);
            } catch (Throwable t) {
                LOGGER.debug("Error scanning recruits around player " + player.getName().getString(), t);
            }
        }
    }

    private static void scanAroundPlayer(ServerPlayer player) {
        ServerLevel world = (ServerLevel) player.getCommandSenderWorld();
        if (world == null) return;

        // Use PathfinderMob class as a broad upper bound (Recruits are PathFinderMob-derived); keep filter light.
        List<PathfinderMob> nearby = world.getEntitiesOfClass(PathfinderMob.class, player.getBoundingBox().inflate(PLAYER_SCAN_RADIUS), mob -> true);

        for (PathfinderMob mob : nearby) {
            try {
                String fqcn = mob.getClass().getName();
                if (!fqcn.contains("talhanation.recruits") && !fqcn.contains("recruits")) continue; // cheap guard

                // If configured, require recruit to be affected by this player (owner/nearby selection)
                if (REQUIRE_EFFECTED_BY_COMMAND) {
                    Boolean effected = callIsEffectedByCommand(mob, player.getUUID());
                    if (effected == null || !effected) continue;
                }

                Integer state = callGetFollowState(mob);
                if (state == null) continue;

                Integer prev = lastKnownState.get(mob);
                if (prev != null && prev.equals(state)) continue; // nothing changed

                lastKnownState.put(mob, state);

                RecruitDoctrine doc = mapStateToDoctrine(state);
                if (doc == null) {
                    // mapping says do nothing
                    continue;
                }

                // Apply doctrine via our holder
                try {
                    RecruitDoctrineHolder.setDoctrine(mob, doc);
                    LOGGER.debug("Applied doctrine {} to recruit {} (state={})", doc.name(), mob.getStringUUID(), state);
                } catch (Throwable t) {
                    LOGGER.debug("Failed to set doctrine on recruit " + mob, t);
                }

            } catch (Throwable t) {
                LOGGER.debug("Error while handling potential recruit entity: " + mob, t);
            }
        }
    }

    // ----------------- Reflection helpers -----------------

    private static Boolean callIsEffectedByCommand(PathfinderMob mob, UUID playerUUID) {
        try {
            Method m = findMethod(mob.getClass(), "isEffectedByCommand", UUID.class);
            if (m == null) return null;
            Object res = m.invoke(mob, playerUUID);
            if (res instanceof Boolean b) return b;
        } catch (Throwable ignored) {}
        return null;
    }

    private static Integer callGetFollowState(PathfinderMob mob) {
        try {
            Method m = findMethod(mob.getClass(), "getFollowState");
            if (m == null) return null;
            Object res = m.invoke(mob);
            if (res instanceof Integer i) return i;
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Live check for whether {@code mob} is currently under a "hold your position" or
     * "hold my position" Recruits order (follow-state 2 or 4). Unlike {@link RecruitDoctrine}
     * (which may be commander-inherited or manually set and doesn't necessarily reflect the
     * recruit's literal current order) this always reflects the real, current movement order,
     * making it safe for callers such as {@link RecruitRangedGunnerAttackGoal} that need to
     * know whether the recruit should hold ground rather than chase.
     *
     * <p>Performs a fresh reflective call (reusing the cached {@link Method} lookup) rather
     * than reading {@link #lastKnownState}, since that map is only populated for recruits
     * within {@link #PLAYER_SCAN_RADIUS} of an online player and only updated on state changes.
     *
     * @return {@code true} only when the live follow-state is 2 or 4; {@code false} on any
     *         error, when the mob isn't a Recruits entity, or for any other state.
     */
    public static boolean isHoldingPosition(PathfinderMob mob) {
        if (mob == null) return false;
        Integer state = callGetFollowState(mob);
        return state != null && (state == 2 || state == 4);
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        Map<String, Method> byName = methodCache.computeIfAbsent(cls, c -> new ConcurrentHashMap<>());
        Method cached = byName.get(name);
        if (cached != null) return cached;

        try {
            Method m = cls.getMethod(name, params);
            m.setAccessible(true);
            byName.put(name, m);
            return m;
        } catch (Throwable t) {
            // walk up class hierarchy searching for the method
            Class<?> sup = cls.getSuperclass();
            if (sup != null && sup != Object.class) return findMethod(sup, name, params);
            return null;
        }
    }

    // ----------------- Mapping -----------------

    /**
     * Default mapping from Recruits movement states to doctrines.
     * Values match {@code AbstractRecruitEntity#getFollowState()} in the real Recruits mod:
     * 0=wander, 1=follow, 2=hold your position, 3=back to position, 4=hold my position,
     * 5=Protect, 6=Work. No values above 6 exist.
     * Adjust or make configurable as needed.
     */
    private static RecruitDoctrine mapStateToDoctrine(int state) {
        switch (state) {
            case 0: // wander - no orders in effect, leave doctrine unchanged
                return null;
            case 1: // follow
                return RecruitDoctrine.SKIRMISHER;
            case 2: // hold your position
            case 4: // hold my position
                return RecruitDoctrine.DEFENSIVE;
            case 3: // back to position
                return RecruitDoctrine.DEFENSIVE;
            case 5: // Protect - close-protection detail
                return RecruitDoctrine.ESCORT;
            case 6: // Work - non-combat task, leave doctrine unchanged
                return null;
            default:
                return null;
        }
    }

    private RecruitMovementDoctrineIntegrator() {}
}
