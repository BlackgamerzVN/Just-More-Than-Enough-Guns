package com.blackgamerz.jmteg.network;

import com.blackgamerz.jmteg.network.payload.S2CRecruitDoctrineSyncPayload;
import com.blackgamerz.jmteg.recruitcompat.RecruitDoctrine;
import com.blackgamerz.jmteg.recruitcompat.RecruitDoctrineHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PathfinderMob;

import java.util.List;
import java.util.UUID;

/**
 * Server-side helpers used by doctrine networking packets.
 */
public final class RecruitDoctrineNetworkHelper {
    private static final double LOOKUP_RADIUS = 16.0D;

    private RecruitDoctrineNetworkHelper() {}

    public static PathfinderMob findEditableRecruit(ServerPlayer player, UUID recruitUuid) {
        List<PathfinderMob> nearby = player.serverLevel().getEntitiesOfClass(
                PathfinderMob.class,
                player.getBoundingBox().inflate(LOOKUP_RADIUS),
                mob -> mob != null && recruitUuid.equals(mob.getUUID()));

        for (PathfinderMob mob : nearby) {
            if (RecruitDoctrineHolder.canPlayerEditDoctrine(mob, player)) {
                return mob;
            }
        }
        return null;
    }

    public static void sendSync(ServerPlayer player, PathfinderMob mob) {
        JmtegNetwork.sendToPlayer(player, S2CRecruitDoctrineSyncPayload.fromMob(mob));
    }

    public static void sendFeedback(ServerPlayer player, RecruitDoctrine doctrine, boolean cleared) {
        if (cleared) {
            player.sendSystemMessage(Component.literal("§e[Recruit] Doctrine: §7Inherit (Commander/None)"));
            return;
        }

        String label = doctrine != null ? doctrine.displayName : "None";
        player.sendSystemMessage(Component.literal("§e[Recruit] Doctrine override: §a" + label));
    }
}
