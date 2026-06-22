package com.blackgamerz.jmteg.network.payload;

import com.blackgamerz.jmteg.network.RecruitDoctrineNetworkHelper;
import com.blackgamerz.jmteg.recruitcompat.RecruitDoctrine;
import com.blackgamerz.jmteg.recruitcompat.RecruitDoctrineHolder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client -> server update for recruit doctrine override.
 */
public record C2SRecruitDoctrineSetPayload(UUID recruitUuid, String doctrineName, boolean clearOverride) {

    public static void encode(C2SRecruitDoctrineSetPayload payload, FriendlyByteBuf buf) {
        buf.writeUUID(payload.recruitUuid);
        buf.writeBoolean(payload.clearOverride);
        buf.writeBoolean(payload.doctrineName != null);
        if (payload.doctrineName != null) {
            buf.writeUtf(payload.doctrineName);
        }
    }

    public static C2SRecruitDoctrineSetPayload decode(FriendlyByteBuf buf) {
        UUID recruitUuid = buf.readUUID();
        boolean clear = buf.readBoolean();
        String doctrineName = buf.readBoolean() ? buf.readUtf(32) : null;
        return new C2SRecruitDoctrineSetPayload(recruitUuid, doctrineName, clear);
    }

    public static void handle(C2SRecruitDoctrineSetPayload payload, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || payload.recruitUuid == null) {
                return;
            }

            PathfinderMob recruit = RecruitDoctrineNetworkHelper.findEditableRecruit(player, payload.recruitUuid);
            if (recruit == null) {
                return;
            }

            RecruitDoctrine doctrine = null;
            if (!payload.clearOverride && payload.doctrineName != null) {
                doctrine = RecruitDoctrine.fromName(payload.doctrineName);
                if (doctrine == null) {
                    RecruitDoctrineNetworkHelper.sendSync(player, recruit);
                    return;
                }
            }

            RecruitDoctrineHolder.setDoctrine(recruit, payload.clearOverride ? null : doctrine);
            RecruitDoctrineNetworkHelper.sendFeedback(player, doctrine, payload.clearOverride);
            RecruitDoctrineNetworkHelper.sendSync(player, recruit);
        });
        ctx.setPacketHandled(true);
    }
}
