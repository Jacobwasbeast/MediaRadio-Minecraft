package net.jacobwasbeast.mediaradio.network;

import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.BalmEnvironment;
import net.blay09.mods.balm.api.network.BalmNetworking;
import net.jacobwasbeast.mediaradio.MediaRadio;
import net.jacobwasbeast.mediaradio.network.message.ClientboundSharedMediaMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundRadioControlMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundSharedMediaMessage;
import net.jacobwasbeast.mediaradio.server.SharedMediaManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModNetworking {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModNetworking.class);

    public static void initialize(BalmNetworking networking) {
        networking.registerClientboundPacket(
                MediaRadio.id("shared_media"),
                ClientboundSharedMediaMessage.class,
                ClientboundSharedMediaMessage::encode,
                ClientboundSharedMediaMessage::new,
                (player, message) -> {
                    if (Balm.getEnvironment() != BalmEnvironment.CLIENT) {
                        return;
                    }
                    try {
                        Class<?> clientRepositoryClass = Class.forName("net.jacobwasbeast.mediaradio.client.data.ClientMediaRepository");
                        clientRepositoryClass.getMethod("applyServerSnapshot", String.class).invoke(null, message.json());
                    } catch (Exception exception) {
                        LOGGER.error("Failed to apply shared media snapshot on client", exception);
                    }
                }
        );

        networking.registerServerboundPacket(
                MediaRadio.id("shared_media_upload"),
                ServerboundSharedMediaMessage.class,
                ServerboundSharedMediaMessage::encode,
                ServerboundSharedMediaMessage::new,
                (player, message) -> SharedMediaManager.handleClientSnapshotUpload(player, message.json())
        );

        networking.registerServerboundPacket(
                MediaRadio.id("radio_control"),
                ServerboundRadioControlMessage.class,
                ServerboundRadioControlMessage::encode,
                ServerboundRadioControlMessage::new,
                SharedMediaManager::handleRadioControl
        );
    }

    public static void sendSharedSnapshot(ServerPlayer serverPlayer, String json) {
        Balm.getNetworking().sendTo(serverPlayer, new ClientboundSharedMediaMessage(json));
    }

    public static void broadcastSharedSnapshot(MinecraftServer minecraftServer, String json) {
        Balm.getNetworking().sendToAll(minecraftServer, new ClientboundSharedMediaMessage(json));
    }

    public static void uploadSharedSnapshot(String json) {
        Balm.getNetworking().sendToServer(new ServerboundSharedMediaMessage(json));
    }

    public static void sendBlockRadioControl(ServerboundRadioControlMessage message) {
        Balm.getNetworking().sendToServer(message);
    }
}
