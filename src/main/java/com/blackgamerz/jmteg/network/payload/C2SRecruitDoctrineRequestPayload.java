package com.blackgamerz.jmteg.network.payload;

import com.blackgamerz.jmteg.network.RecruitDoctrineNetworkHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client -> server request for recruit doctrine state sync.
 */
public record C2SRecruitDoctrineRequestPayload(UUID recruitUuid) {

    public static void encode(C2SRecruitDoctrineRequestPayload payload, FriendlyByteBuf buf) {
        buf.writeUUID(payload.recruitUuid);
    }

    public static C2SRecruitDoctrineRequestPayload decode(FriendlyByteBuf buf) {
        return new C2SRecruitDoctrineRequestPayload(buf.readUUID());
    }

    public static void handle(C2SRecruitDoctrineRequestPayload payload, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || payload.recruitUuid() == null) {
                return;
            }
            PathfinderMob recruit = RecruitDoctrineNetworkHelper.findEditableRecruit(player, payload.recruitUuid());
            if (recruit != null) {
                RecruitDoctrineNetworkHelper.sendSync(player, recruit);
            }
        });
        ctx.setPacketHandled(true);
    }
}
