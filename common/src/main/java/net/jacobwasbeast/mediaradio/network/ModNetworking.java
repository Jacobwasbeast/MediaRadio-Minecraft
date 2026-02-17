package net.jacobwasbeast.mediaradio.network;

import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.network.BalmNetworking;
import net.jacobwasbeast.mediaradio.MediaRadio;
import net.jacobwasbeast.mediaradio.network.message.ClientboundSharedMediaChunkMessage;
import net.jacobwasbeast.mediaradio.network.message.ClientboundPlayerRadioContextMessage;
import net.jacobwasbeast.mediaradio.network.message.ClientboundRadioStateMessage;
import net.jacobwasbeast.mediaradio.network.message.ClientboundOpenContraptionRadioMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundHandheldStateMessage;
import net.jacobwasbeast.mediaradio.network.message.ClientboundSharedMediaMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundRadioControlMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundRequestRadioStateMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundSharedMediaChunkMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundSharedMediaMessage;
import net.jacobwasbeast.mediaradio.server.SharedMediaManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModNetworking {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModNetworking.class);
    private static final int SHARED_MEDIA_DIRECT_THRESHOLD = 10_000;
    private static final int SHARED_MEDIA_CHUNK_SIZE = 8_000;

    public static void initialize(BalmNetworking networking) {
        networking.registerClientboundPacket(
                MediaRadio.id("shared_media"),
                ClientboundSharedMediaMessage.class,
                ClientboundSharedMediaMessage::encode,
                ClientboundSharedMediaMessage::new,
                (player, message) -> {
                    if (!Balm.getProxy().isClient()) {
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

        networking.registerClientboundPacket(
                MediaRadio.id("radio_state"),
                ClientboundRadioStateMessage.class,
                ClientboundRadioStateMessage::encode,
                ClientboundRadioStateMessage::new,
                (player, message) -> {
                    if (!Balm.getProxy().isClient()) {
                        return;
                    }
                    try {
                        Class<?> clientClass = Class.forName("net.jacobwasbeast.mediaradio.client.MediaRadioClient");
                        clientClass.getMethod(
                                        "applyServerRadioRuntimeState",
                                        String.class,
                                        String.class,
                                        String.class,
                                        String.class,
                                        String.class,
                                        String.class,
                                        float.class,
                                        long.class,
                                        long.class,
                                        boolean.class,
                                        boolean.class,
                                        boolean.class
                                )
                                .invoke(
                                        null,
                                        message.radioId(),
                                        message.url(),
                                        message.title(),
                                        message.artist(),
                                        message.thumbnail(),
                                        message.queueStateJson(),
                                        message.volume(),
                                        message.positionMs(),
                                        message.sentAtMs(),
                                        message.forcePositionSync(),
                                        message.seekEvent(),
                                        message.playing()
                                );
                    } catch (Exception exception) {
                        LOGGER.error("Failed to apply radio runtime state on client", exception);
                    }
                }
        );

        networking.registerClientboundPacket(
                MediaRadio.id("player_radio_context"),
                ClientboundPlayerRadioContextMessage.class,
                ClientboundPlayerRadioContextMessage::encode,
                ClientboundPlayerRadioContextMessage::new,
                (player, message) -> {
                    if (!Balm.getProxy().isClient()) {
                        return;
                    }
                    try {
                        Class<?> clientClass = Class.forName("net.jacobwasbeast.mediaradio.client.MediaRadioClient");
                        clientClass.getMethod("applyServerPlayerRadioContext", String.class, int.class, boolean.class)
                                .invoke(null, message.radioId(), message.entityId(), message.active());
                    } catch (Exception exception) {
                        LOGGER.error("Failed to apply player radio context on client", exception);
                    }
                }
        );

        networking.registerClientboundPacket(
                MediaRadio.id("shared_media_chunk"),
                ClientboundSharedMediaChunkMessage.class,
                ClientboundSharedMediaChunkMessage::encode,
                ClientboundSharedMediaChunkMessage::new,
                (player, message) -> {
                    if (!Balm.getProxy().isClient()) {
                        return;
                    }
                    try {
                        Class<?> clientRepositoryClass = Class.forName("net.jacobwasbeast.mediaradio.client.data.ClientMediaRepository");
                        clientRepositoryClass.getMethod("applyServerSnapshotChunk", String.class, int.class, int.class, String.class)
                                .invoke(null, message.transferId(), message.chunkIndex(), message.totalChunks(), message.chunkData());
                    } catch (Exception exception) {
                        LOGGER.error("Failed to apply shared media chunk on client", exception);
                    }
                }
        );

        networking.registerClientboundPacket(
                MediaRadio.id("open_contraption_radio"),
                ClientboundOpenContraptionRadioMessage.class,
                ClientboundOpenContraptionRadioMessage::encode,
                ClientboundOpenContraptionRadioMessage::new,
                (player, message) -> {
                    if (!Balm.getProxy().isClient()) {
                        return;
                    }
                    try {
                        Class<?> clientClass = Class.forName("net.jacobwasbeast.mediaradio.client.MediaRadioClient");
                        clientClass.getMethod("openContraptionRadioScreen", String.class, int.class, net.minecraft.core.BlockPos.class)
                                .invoke(null, message.radioId(), message.contraptionEntityId(), message.localPos());
                    } catch (Exception exception) {
                        LOGGER.error("Failed to open contraption radio screen on client", exception);
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
                MediaRadio.id("shared_media_upload_chunk"),
                ServerboundSharedMediaChunkMessage.class,
                ServerboundSharedMediaChunkMessage::encode,
                ServerboundSharedMediaChunkMessage::new,
                (player, message) -> SharedMediaManager.handleClientSnapshotUploadChunk(
                        player,
                        message.transferId(),
                        message.chunkIndex(),
                        message.totalChunks(),
                        message.chunkData()
                )
        );

        networking.registerServerboundPacket(
                MediaRadio.id("radio_control"),
                ServerboundRadioControlMessage.class,
                ServerboundRadioControlMessage::encode,
                ServerboundRadioControlMessage::new,
                SharedMediaManager::handleRadioControl
        );

        networking.registerServerboundPacket(
                MediaRadio.id("handheld_state"),
                ServerboundHandheldStateMessage.class,
                ServerboundHandheldStateMessage::encode,
                ServerboundHandheldStateMessage::new,
                SharedMediaManager::handleHandheldState
        );

        networking.registerServerboundPacket(
                MediaRadio.id("radio_state_request"),
                ServerboundRequestRadioStateMessage.class,
                ServerboundRequestRadioStateMessage::encode,
                ServerboundRequestRadioStateMessage::new,
                SharedMediaManager::handleRadioStateRequest
        );
    }

    public static void sendSharedSnapshot(ServerPlayer serverPlayer, String json) {
        if (json == null) {
            return;
        }
        if (json.length() <= SHARED_MEDIA_DIRECT_THRESHOLD) {
            Balm.getNetworking().sendTo(serverPlayer, new ClientboundSharedMediaMessage(json));
            return;
        }
        String transferId = java.util.UUID.randomUUID().toString();
        int totalChunks = chunkCount(json.length(), SHARED_MEDIA_CHUNK_SIZE);
        for (int i = 0; i < totalChunks; i++) {
            int start = i * SHARED_MEDIA_CHUNK_SIZE;
            int end = Math.min(json.length(), start + SHARED_MEDIA_CHUNK_SIZE);
            Balm.getNetworking().sendTo(serverPlayer, new ClientboundSharedMediaChunkMessage(
                    transferId,
                    i,
                    totalChunks,
                    json.substring(start, end)
            ));
        }
    }

    public static void broadcastSharedSnapshot(MinecraftServer minecraftServer, String json) {
        if (json == null) {
            return;
        }
        if (json.length() <= SHARED_MEDIA_DIRECT_THRESHOLD) {
            Balm.getNetworking().sendToAll(minecraftServer, new ClientboundSharedMediaMessage(json));
            return;
        }
        String transferId = java.util.UUID.randomUUID().toString();
        int totalChunks = chunkCount(json.length(), SHARED_MEDIA_CHUNK_SIZE);
        for (int i = 0; i < totalChunks; i++) {
            int start = i * SHARED_MEDIA_CHUNK_SIZE;
            int end = Math.min(json.length(), start + SHARED_MEDIA_CHUNK_SIZE);
            Balm.getNetworking().sendToAll(minecraftServer, new ClientboundSharedMediaChunkMessage(
                    transferId,
                    i,
                    totalChunks,
                    json.substring(start, end)
            ));
        }
    }

    public static void uploadSharedSnapshot(String json) {
        if (json == null) {
            return;
        }
        if (json.length() <= SHARED_MEDIA_DIRECT_THRESHOLD) {
            Balm.getNetworking().sendToServer(new ServerboundSharedMediaMessage(json));
            return;
        }
        String transferId = java.util.UUID.randomUUID().toString();
        int totalChunks = chunkCount(json.length(), SHARED_MEDIA_CHUNK_SIZE);
        for (int i = 0; i < totalChunks; i++) {
            int start = i * SHARED_MEDIA_CHUNK_SIZE;
            int end = Math.min(json.length(), start + SHARED_MEDIA_CHUNK_SIZE);
            Balm.getNetworking().sendToServer(new ServerboundSharedMediaChunkMessage(
                    transferId,
                    i,
                    totalChunks,
                    json.substring(start, end)
            ));
        }
    }

    public static void sendBlockRadioControl(ServerboundRadioControlMessage message) {
        Balm.getNetworking().sendToServer(message);
    }

    public static void sendHandheldState(ServerboundHandheldStateMessage message) {
        Balm.getNetworking().sendToServer(message);
    }

    public static void requestRadioState(String radioId) {
        Balm.getNetworking().sendToServer(new ServerboundRequestRadioStateMessage(radioId));
    }

    public static void sendRadioState(ServerPlayer player, ClientboundRadioStateMessage message) {
        Balm.getNetworking().sendTo(player, message);
    }

    public static void openContraptionRadioScreen(ServerPlayer player, String radioId, int contraptionEntityId, net.minecraft.core.BlockPos localPos) {
        Balm.getNetworking().sendTo(player, new ClientboundOpenContraptionRadioMessage(radioId, contraptionEntityId, localPos));
    }

    public static void sendPlayerRadioContext(ServerPlayer player, String radioId, int entityId, boolean active) {
        Balm.getNetworking().sendTo(player, new ClientboundPlayerRadioContextMessage(radioId, entityId, active));
    }

    private static int chunkCount(int totalLength, int chunkSize) {
        return Math.max(1, (totalLength + chunkSize - 1) / chunkSize);
    }
}
