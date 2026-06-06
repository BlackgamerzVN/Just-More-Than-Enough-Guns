package com.blackgamerz.jmteg.recruitcompat;

import com.blackgamerz.jmteg.Main;
import net.minecraft.nbt.CompoundTag;
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
 *  - Respects personal doctrine stored in recruit persistent data unless CONFIG_OVERRIDE_PERSONAL is true.
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

    // If true, overwrite a recruit's personal doctrine (shift-right-click choice) when movement command maps a doctrine.
    private static final boolean CONFIG_OVERRIDE_PERSONAL = false;

    // If true, require recruit.isEffectedByCommand(playerUUID) to be true before applying doctrine for that player.
    private static final boolean REQUIRE_EFFECTED_BY_COMMAND = true;

    // Internal caches
    private static final Map<PathfinderMob, Integer> lastKnownState = new WeakHashMap<>();
    private static final Map<Class<?>, Method> methodCache = new ConcurrentHashMap<>();

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
                Boolean inOrder = callGetIsInOrder(mob);
                if (state == null) continue;

                Integer prev = lastKnownState.get(mob);
                if (prev != null && prev.equals(state)) continue; // nothing changed

                lastKnownState.put(mob, state);

                // only apply doctrine when recruit is in order (following commands) — keep safe default
                if (inOrder == null || !inOrder) continue;

                // Respect personal doctrine unless configured otherwise
                String personal = readPersonalDoctrineNBT(mob);
                if (!CONFIG_OVERRIDE_PERSONAL && personal != null && !personal.isEmpty()) {
                    // recruit has explicit personal doctrine -> skip
                    continue;
                }

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

    private static Boolean callGetIsInOrder(PathfinderMob mob) {
        try {
            Method m = findMethod(mob.getClass(), "getIsInOrder");
            if (m == null) return null;
            Object res = m.invoke(mob);
            if (res instanceof Boolean b) return b;
        } catch (Throwable ignored) {}
        return null;
    }

    private static String readPersonalDoctrineNBT(PathfinderMob mob) {
        try {
            CompoundTag tag = mob.getPersistentData();
            if (tag == null) return null;
            return tag.getString(RecruitDoctrine.NBT_KEY);
        } catch (Throwable ignored) {}
        return null;
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            Class<?> key = cls;
            Method cached = methodCache.get(key);
            if (cached != null && cached.getName().equals(name)) return cached;

            Method m = cls.getMethod(name, params);
            m.setAccessible(true);
            methodCache.put(key, m);
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
     * Adjust or make configurable as needed.
     */
    private static RecruitDoctrine mapStateToDoctrine(int state) {
        // See Recruits' CommandEvents mapping comments
        switch (state) {
            case 0: // wander
                return null;
            case 1: // follow
                return RecruitDoctrine.SKIRMISHER;
            case 2: // hold your position
            case 4: // hold my position
                return RecruitDoctrine.DEFENSIVE;
            case 3: // back to position
                return RecruitDoctrine.DEFENSIVE;
            case 5: // protect
                return RecruitDoctrine.DEFENSIVE;
            case 6: // move
            case 7: // forward
            case 8: // backward
                return RecruitDoctrine.AGGRESSIVE;
            default:
                return null;
        }
    }

    private RecruitMovementDoctrineIntegrator() {}
}
