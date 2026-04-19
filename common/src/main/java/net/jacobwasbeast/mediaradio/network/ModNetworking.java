package net.jacobwasbeast.mediaradio.network;

import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.network.BalmNetworking;
import net.jacobwasbeast.mediaradio.MediaRadio;
import net.jacobwasbeast.mediaradio.network.message.ClientboundRadioQueueChunkMessage;
import net.jacobwasbeast.mediaradio.network.message.ClientboundSessionCommandResultMessage;
import net.jacobwasbeast.mediaradio.network.message.ClientboundSharedMediaChunkMessage;
import net.jacobwasbeast.mediaradio.network.message.ClientboundPlayerRadioContextMessage;
import net.jacobwasbeast.mediaradio.network.message.ClientboundRadioStateMessage;
import net.jacobwasbeast.mediaradio.network.message.ClientboundOpenContraptionRadioMessage;
import net.jacobwasbeast.mediaradio.network.message.ClientboundSharedMediaMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundRadioControlMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundRadioQueueChunkMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundRequestRadioStateMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundSharedMediaChunkMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundSharedMediaMessage;
import net.jacobwasbeast.mediaradio.server.SharedMediaManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

public class ModNetworking {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModNetworking.class);
    private static final int SHARED_MEDIA_DIRECT_THRESHOLD = 10_000;
    private static final int SHARED_MEDIA_CHUNK_SIZE = 8_000;
    private static final int RADIO_QUEUE_DIRECT_THRESHOLD = 30_000;
    private static final int RADIO_QUEUE_CHUNK_SIZE = 8_000;
    private static final AtomicLong COMMAND_SEQUENCE = new AtomicLong();

    public static void initialize(BalmNetworking networking) {
        networking.registerClientboundPacket(
                ClientboundSharedMediaMessage.TYPE,
                ClientboundSharedMediaMessage.class,
                (buf, msg) -> msg.encode(buf),
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
                ClientboundRadioStateMessage.TYPE,
                ClientboundRadioStateMessage.class,
                (buf, msg) -> msg.encode(buf),
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
                                        long.class,
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
                                        message.sessionId(),
                                        message.revision(),
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
                ClientboundRadioQueueChunkMessage.TYPE,
                ClientboundRadioQueueChunkMessage.class,
                (buf, msg) -> msg.encode(buf),
                ClientboundRadioQueueChunkMessage::new,
                (player, message) -> {
                    if (!Balm.getProxy().isClient()) {
                        return;
                    }
                    try {
                        Class<?> clientClass = Class.forName("net.jacobwasbeast.mediaradio.client.MediaRadioClient");
                        clientClass.getMethod(
                                        "applyServerRadioQueueStateChunk",
                                        String.class,
                                        long.class,
                                        String.class,
                                        int.class,
                                        int.class,
                                        String.class
                                )
                                .invoke(
                                        null,
                                        message.radioId(),
                                        message.revision(),
                                        message.transferId(),
                                        message.chunkIndex(),
                                        message.totalChunks(),
                                        message.chunkData()
                                );
                    } catch (Exception exception) {
                        LOGGER.error("Failed to apply radio queue chunk on client", exception);
                    }
                }
        );

        networking.registerClientboundPacket(
                ClientboundPlayerRadioContextMessage.TYPE,
                ClientboundPlayerRadioContextMessage.class,
                (buf, msg) -> msg.encode(buf),
                ClientboundPlayerRadioContextMessage::new,
                (player, message) -> {
                    if (!Balm.getProxy().isClient()) {
                        return;
                    }
                    try {
                        Class<?> clientClass = Class.forName("net.jacobwasbeast.mediaradio.client.MediaRadioClient");
                        clientClass.getMethod("applyServerPlayerRadioContext", String.class, int.class, boolean.class, boolean.class)
                                .invoke(null, message.radioId(), message.entityId(), message.active(), message.inventoryPlayback());
                    } catch (Exception exception) {
                        LOGGER.error("Failed to apply player radio context on client", exception);
                    }
                }
        );

        networking.registerClientboundPacket(
                ClientboundSharedMediaChunkMessage.TYPE,
                ClientboundSharedMediaChunkMessage.class,
                (buf, msg) -> msg.encode(buf),
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
                ClientboundOpenContraptionRadioMessage.TYPE,
                ClientboundOpenContraptionRadioMessage.class,
                (buf, msg) -> msg.encode(buf),
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

        networking.registerClientboundPacket(
                ClientboundSessionCommandResultMessage.TYPE,
                ClientboundSessionCommandResultMessage.class,
                (buf, msg) -> msg.encode(buf),
                ClientboundSessionCommandResultMessage::new,
                (player, message) -> {
                    if (!Balm.getProxy().isClient()) {
                        return;
                    }
                    try {
                        Class<?> clientClass = Class.forName("net.jacobwasbeast.mediaradio.client.MediaRadioClient");
                        clientClass.getMethod(
                                        "handleServerSessionCommandResult",
                                        String.class,
                                        String.class,
                                        long.class,
                                        boolean.class,
                                        ClientboundSessionCommandResultMessage.Reason.class,
                                        net.jacobwasbeast.mediaradio.network.message.ServerboundRequestRadioStateMessage.Context.class
                                )
                                .invoke(
                                        null,
                                        message.radioId(),
                                        message.sessionId(),
                                        message.serverRevision(),
                                        message.accepted(),
                                        message.reason(),
                                        message.context()
                                );
                    } catch (Exception exception) {
                        LOGGER.error("Failed to process session command result on client", exception);
                    }
                }
        );

        networking.registerServerboundPacket(
                ServerboundSharedMediaMessage.TYPE,
                ServerboundSharedMediaMessage.class,
                (buf, msg) -> msg.encode(buf),
                ServerboundSharedMediaMessage::new,
                (player, message) -> SharedMediaManager.handleClientSnapshotUpload(player, message.json())
        );

        networking.registerServerboundPacket(
                ServerboundSharedMediaChunkMessage.TYPE,
                ServerboundSharedMediaChunkMessage.class,
                (buf, msg) -> msg.encode(buf),
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
                ServerboundRadioControlMessage.TYPE,
                ServerboundRadioControlMessage.class,
                (buf, msg) -> msg.encode(buf),
                ServerboundRadioControlMessage::new,
                SharedMediaManager::handleRadioControl
        );

        networking.registerServerboundPacket(
                ServerboundRadioQueueChunkMessage.TYPE,
                ServerboundRadioQueueChunkMessage.class,
                (buf, msg) -> msg.encode(buf),
                ServerboundRadioQueueChunkMessage::new,
                SharedMediaManager::handleRadioQueueChunk
        );

        networking.registerServerboundPacket(
                ServerboundRequestRadioStateMessage.TYPE,
                ServerboundRequestRadioStateMessage.class,
                (buf, msg) -> msg.encode(buf),
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
        if (message == null) {
            return;
        }
        ServerboundRadioControlMessage outbound = message;
        if (outbound.commandId() < 0L) {
            outbound = new ServerboundRadioControlMessage(
                    outbound.blockPos(),
                    outbound.radioId(),
                    outbound.context(),
                    outbound.action(),
                    outbound.url(),
                    outbound.title(),
                    outbound.artist(),
                    outbound.thumbnail(),
                    outbound.volume(),
                    outbound.positionMs(),
                    outbound.trackDurationMs(),
                    outbound.knownRevision(),
                    nextCommandId()
            );
        }
        if (outbound.action() == ServerboundRadioControlMessage.Action.UPDATE_QUEUE_STATE) {
            String queueStateJson = outbound.url() == null ? "" : outbound.url();
            if (queueStateJson.length() > RADIO_QUEUE_DIRECT_THRESHOLD) {
                sendRadioQueueChunksToServer(outbound, queueStateJson);
                return;
            }
        }
        Balm.getNetworking().sendToServer(outbound);
    }

    public static void requestRadioState(String radioId) {
        requestRadioState(radioId, ServerboundRequestRadioStateMessage.Context.HANDHELD);
    }

    public static void requestRadioState(String radioId, ServerboundRequestRadioStateMessage.Context context) {
        Balm.getNetworking().sendToServer(new ServerboundRequestRadioStateMessage(radioId, context));
    }

    public static void sendRadioState(ServerPlayer player, ClientboundRadioStateMessage message) {
        Balm.getNetworking().sendTo(player, message);
    }

    public static void sendRadioQueueStateChunks(ServerPlayer player, String radioId, long revision, String queueStateJson) {
        if (player == null || queueStateJson == null || queueStateJson.isBlank()) {
            return;
        }
        String transferId = java.util.UUID.randomUUID().toString();
        int totalChunks = chunkCount(queueStateJson.length(), RADIO_QUEUE_CHUNK_SIZE);
        for (int i = 0; i < totalChunks; i++) {
            int start = i * RADIO_QUEUE_CHUNK_SIZE;
            int end = Math.min(queueStateJson.length(), start + RADIO_QUEUE_CHUNK_SIZE);
            Balm.getNetworking().sendTo(player, new ClientboundRadioQueueChunkMessage(
                    transferId,
                    radioId == null ? "" : radioId,
                    Math.max(0L, revision),
                    i,
                    totalChunks,
                    queueStateJson.substring(start, end)
            ));
        }
    }

    public static void sendSessionCommandResult(ServerPlayer player, ClientboundSessionCommandResultMessage message) {
        if (player == null || message == null) {
            return;
        }
        Balm.getNetworking().sendTo(player, message);
    }

    public static void openContraptionRadioScreen(ServerPlayer player, String radioId, int contraptionEntityId, net.minecraft.core.BlockPos localPos) {
        Balm.getNetworking().sendTo(player, new ClientboundOpenContraptionRadioMessage(radioId, contraptionEntityId, localPos));
    }

    public static void sendPlayerRadioContext(ServerPlayer player, String radioId, int entityId, boolean active, boolean inventoryPlayback) {
        Balm.getNetworking().sendTo(player, new ClientboundPlayerRadioContextMessage(radioId, entityId, active, inventoryPlayback));
    }

    private static int chunkCount(int totalLength, int chunkSize) {
        return Math.max(1, (totalLength + chunkSize - 1) / chunkSize);
    }

    private static void sendRadioQueueChunksToServer(ServerboundRadioControlMessage message, String queueStateJson) {
        if (message == null || queueStateJson == null || queueStateJson.isBlank()) {
            return;
        }
        String transferId = java.util.UUID.randomUUID().toString();
        int totalChunks = chunkCount(queueStateJson.length(), RADIO_QUEUE_CHUNK_SIZE);
        for (int i = 0; i < totalChunks; i++) {
            int start = i * RADIO_QUEUE_CHUNK_SIZE;
            int end = Math.min(queueStateJson.length(), start + RADIO_QUEUE_CHUNK_SIZE);
            Balm.getNetworking().sendToServer(new ServerboundRadioQueueChunkMessage(
                    message.blockPos(),
                    message.radioId(),
                    message.context(),
                    transferId,
                    i,
                    totalChunks,
                    queueStateJson.substring(start, end),
                    message.knownRevision(),
                    message.commandId()
            ));
        }
    }

    private static long nextCommandId() {
        long next = COMMAND_SEQUENCE.incrementAndGet();
        if (next <= 0L) {
            COMMAND_SEQUENCE.set(1L);
            return 1L;
        }
        return next;
    }
}
