package com.blackgamerz.jmteg.network;

import com.blackgamerz.jmteg.Main;
import com.blackgamerz.jmteg.network.payload.C2SRecruitDoctrineRequestPayload;
import com.blackgamerz.jmteg.network.payload.C2SRecruitDoctrineSetPayload;
import com.blackgamerz.jmteg.network.payload.S2CRecruitDoctrineSyncPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * Shared JMTEG network channel.
 *
 * <p>Uses Forge SimpleChannel so JMTEG remains independent from JEG/Recruits API types
 * while still supporting bidirectional recruit doctrine updates.</p>
 */
public final class JmtegNetwork {
    private static final String PROTOCOL_VERSION = "1";

    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(Main.MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            .clientAcceptedVersions(PROTOCOL_VERSION::equals)
            .serverAcceptedVersions(PROTOCOL_VERSION::equals)
            .simpleChannel();

    private static boolean registered = false;

    private JmtegNetwork() {}

    public static void register() {
        if (registered) {
            return;
        }
        int id = 0;

        CHANNEL.registerMessage(id++,
                C2SRecruitDoctrineSetPayload.class,
                C2SRecruitDoctrineSetPayload::encode,
                C2SRecruitDoctrineSetPayload::decode,
                C2SRecruitDoctrineSetPayload::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));

        CHANNEL.registerMessage(id++,
                C2SRecruitDoctrineRequestPayload.class,
                C2SRecruitDoctrineRequestPayload::encode,
                C2SRecruitDoctrineRequestPayload::decode,
                C2SRecruitDoctrineRequestPayload::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));

        CHANNEL.registerMessage(id,
                S2CRecruitDoctrineSyncPayload.class,
                S2CRecruitDoctrineSyncPayload::encode,
                S2CRecruitDoctrineSyncPayload::decode,
                S2CRecruitDoctrineSyncPayload::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        registered = true;
    }

    public static void sendToServer(Object packet) {
        CHANNEL.sendToServer(packet);
    }

    public static void sendToPlayer(ServerPlayer player, Object packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
