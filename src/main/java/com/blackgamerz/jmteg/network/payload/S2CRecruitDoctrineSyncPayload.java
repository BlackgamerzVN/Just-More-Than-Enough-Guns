package com.blackgamerz.jmteg.network.payload;

import com.blackgamerz.jmteg.client.ui.RecruitDoctrineScreenIntegration;
import com.blackgamerz.jmteg.recruitcompat.RecruitDoctrine;
import com.blackgamerz.jmteg.recruitcompat.RecruitDoctrineHolder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server -> client recruit doctrine state sync.
 */
public record S2CRecruitDoctrineSyncPayload(
        UUID recruitUuid,
        RecruitDoctrine personalDoctrine,
        RecruitDoctrine effectiveDoctrine,
        RecruitDoctrineHolder.DoctrineSource source) {

    public static S2CRecruitDoctrineSyncPayload fromMob(PathfinderMob mob) {
        return new S2CRecruitDoctrineSyncPayload(
                mob.getUUID(),
                RecruitDoctrineHolder.getPersonalDoctrine(mob),
                RecruitDoctrineHolder.getEffectiveDoctrine(mob),
                RecruitDoctrineHolder.getDoctrineSource(mob));
    }

    public static void encode(S2CRecruitDoctrineSyncPayload payload, FriendlyByteBuf buf) {
        buf.writeUUID(payload.recruitUuid);
        writeDoctrine(buf, payload.personalDoctrine);
        writeDoctrine(buf, payload.effectiveDoctrine);
        buf.writeEnum(payload.source);
    }

    public static S2CRecruitDoctrineSyncPayload decode(FriendlyByteBuf buf) {
        UUID uuid = buf.readUUID();
        RecruitDoctrine personal = readDoctrine(buf);
        RecruitDoctrine effective = readDoctrine(buf);
        RecruitDoctrineHolder.DoctrineSource source = buf.readEnum(RecruitDoctrineHolder.DoctrineSource.class);
        return new S2CRecruitDoctrineSyncPayload(uuid, personal, effective, source);
    }

    public static void handle(S2CRecruitDoctrineSyncPayload payload, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> RecruitDoctrineScreenIntegration.handleSync(payload)));
        ctx.setPacketHandled(true);
    }

    private static void writeDoctrine(FriendlyByteBuf buf, RecruitDoctrine doctrine) {
        buf.writeBoolean(doctrine != null);
        if (doctrine != null) {
            buf.writeUtf(doctrine.name());
        }
    }

    private static RecruitDoctrine readDoctrine(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return null;
        }
        return RecruitDoctrine.fromName(buf.readUtf(32));
    }
}
