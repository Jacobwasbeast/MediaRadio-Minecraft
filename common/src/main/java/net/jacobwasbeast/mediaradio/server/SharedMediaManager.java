package net.jacobwasbeast.mediaradio.server;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.event.TickPhase;
import net.blay09.mods.balm.api.event.TickType;
import net.jacobwasbeast.mediaradio.MediaRadio;
import net.jacobwasbeast.mediaradio.block.entity.RadioBlockEntity;
import net.jacobwasbeast.mediaradio.network.ModNetworking;
import net.jacobwasbeast.mediaradio.network.message.ClientboundRadioStateMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundHandheldStateMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundRadioControlMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundRequestRadioStateMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.net.URI;
import java.net.URISyntaxException;

public class SharedMediaManager {
    private static final long CHUNK_TTL_MS = 30_000L;
    private static final int MAX_QUEUE_STATE_RADIO_PACKET = 8_192;
    private static final int HANDHELD_PERIODIC_SYNC_INTERVAL_TICKS = 40;
    private static final int UNOWNED_RUNTIME_SIMULATION_INTERVAL_TICKS = 20;
    private static final long UNOWNED_RUNTIME_STALE_MS = 3_000L;
    private static final long TRACK_END_EPSILON_MS = 250L;
    private static final int MAX_SIMULATED_TRANSITIONS_PER_TICK = 128;
    private static final double HANDHELD_LISTENER_SYNC_RANGE_SQR = 96.0D * 96.0D;
    private static final double BLOCK_CONTROL_MAX_DISTANCE_SQR = 24.0D * 24.0D;
    private static final Gson GSON = new Gson();
    private static final Map<String, ChunkAccumulator> CHUNK_UPLOADS = new HashMap<>();
    private static final Map<String, HandheldListenerContext> ACTIVE_HANDHELD_LISTENER_CONTEXTS = new HashMap<>();

    public static void initialize() {
        Balm.getEvents().onEvent(net.blay09.mods.balm.api.event.PlayerLoginEvent.class,
                event -> {
                    ModNetworking.sendSharedSnapshot(event.getPlayer(), getSnapshot(event.getPlayer().server));
                    syncActiveHandheldRadiosToPlayer(event.getPlayer());
                });
        Balm.getEvents().onTickEvent(TickType.Server, TickPhase.End, (net.blay09.mods.balm.api.event.ServerTickHandler) server -> {
            simulateUnownedRadioPlayback(server);
            syncActiveHandheldRadiosToNearbyPlayers(server);
        });
    }

    public static String getSnapshot(MinecraftServer server) {
        return SharedMediaSavedData.get(server).getSnapshotJson();
    }

    public static void handleClientSnapshotUpload(ServerPlayer player, String json) {
        if (json == null || json.isBlank() || json.length() > SharedMediaSnapshot.MAX_JSON_LENGTH) {
            return;
        }

        SharedMediaSavedData data = SharedMediaSavedData.get(player.server);
        SharedMediaSnapshot currentSnapshot = SharedMediaSnapshot.fromJson(data.getSnapshotJson());
        SharedMediaSnapshot incomingSnapshot = SharedMediaSnapshot.fromJson(json);
        SharedMediaSnapshot mergedSnapshot = mergeSnapshotForPlayer(
                currentSnapshot,
                incomingSnapshot,
                player.getStringUUID(),
                player.getGameProfile().getName()
        );
        String mergedJson = mergedSnapshot.toJson();

        data.setSnapshotJson(mergedJson);

        ModNetworking.broadcastSharedSnapshot(player.server, mergedJson);
    }

    public static void handleClientSnapshotUploadChunk(
            ServerPlayer player,
            String transferId,
            int chunkIndex,
            int totalChunks,
            String chunkData
    ) {
        if (player == null || transferId == null || transferId.isBlank() || chunkData == null) {
            return;
        }
        if (totalChunks <= 0 || totalChunks > 512 || chunkIndex < 0 || chunkIndex >= totalChunks) {
            return;
        }

        long now = System.currentTimeMillis();
        String key = player.getUUID() + "|" + transferId;
        String json;
        synchronized (CHUNK_UPLOADS) {
            CHUNK_UPLOADS.entrySet().removeIf(entry -> now - entry.getValue().createdAtMs > CHUNK_TTL_MS);
            ChunkAccumulator accumulator = CHUNK_UPLOADS.computeIfAbsent(key, ignored -> new ChunkAccumulator(totalChunks, now));
            if (accumulator.totalChunks != totalChunks) {
                CHUNK_UPLOADS.remove(key);
                return;
            }
            if (!accumulator.accept(chunkIndex, chunkData, SharedMediaSnapshot.MAX_JSON_LENGTH)) {
                CHUNK_UPLOADS.remove(key);
                return;
            }
            if (!accumulator.complete()) {
                return;
            }
            json = accumulator.join();
            CHUNK_UPLOADS.remove(key);
        }

        handleClientSnapshotUpload(player, json);
    }

    public static void handleRadioControl(ServerPlayer player, ServerboundRadioControlMessage message) {
        BlockPos blockPos = message.blockPos();
        RadioBlockEntity radioBlockEntity = null;
        String radioId = safe(message.radioId());
        if (blockPos != null) {
            if (player.distanceToSqr(blockPos.getX() + 0.5d, blockPos.getY() + 0.5d, blockPos.getZ() + 0.5d) > BLOCK_CONTROL_MAX_DISTANCE_SQR) {
                return;
            }
            BlockEntity blockEntity = player.level().getBlockEntity(blockPos);
            if (blockEntity instanceof RadioBlockEntity resolvedRadioBlockEntity) {
                radioBlockEntity = resolvedRadioBlockEntity;
                String blockRadioId = safe(radioBlockEntity.getRadioId());
                if (blockRadioId.isBlank()) {
                    blockRadioId = !radioId.isBlank() ? radioId : UUID.randomUUID().toString();
                    radioBlockEntity.setRadioId(blockRadioId);
                }
                radioId = blockRadioId;
            } else if (radioId.isBlank()) {
                return;
            }
        } else if (radioId.isBlank()) {
            return;
        }

        RadioRuntimeStateSavedData runtimeData = RadioRuntimeStateSavedData.get(player.server);
        RadioRuntimeStateSavedData.RadioRuntimeState runtimeState = runtimeData.getOrCreate(radioId);
        long now = System.currentTimeMillis();

        switch (message.action()) {
            case PLAY_URL -> {
                runtimeState.url = safe(message.url());
                runtimeState.title = safe(message.title());
                runtimeState.artist = safe(message.artist());
                runtimeState.thumbnail = safe(message.thumbnail());
                runtimeState.positionMs = 0L;
                long incomingDurationMs = message.trackDurationMs();
                if (incomingDurationMs > 0L) {
                    runtimeState.trackDurationMs = incomingDurationMs;
                    if (!runtimeState.url.isBlank()) {
                        runtimeState.knownTrackDurationsMs.put(trackSyncKey(runtimeState.url), incomingDurationMs);
                    }
                } else {
                    runtimeState.trackDurationMs = resolveTrackDurationMs(runtimeState, runtimeState.url);
                }
                runtimeState.playing = true;
                runtimeState.updatedAtMs = now;
                runtimeState.volume = Mth.clamp(message.volume(), 0f, 2f);
                if (radioBlockEntity != null) {
                    radioBlockEntity.setMedia(message.url(), message.title(), message.artist(), message.thumbnail());
                    radioBlockEntity.play();
                }
            }
            case UPDATE_METADATA -> {
                runtimeState.title = safe(message.title());
                runtimeState.artist = safe(message.artist());
                runtimeState.thumbnail = safe(message.thumbnail());
                runtimeState.updatedAtMs = now;
                if (radioBlockEntity != null) {
                    radioBlockEntity.updateMetadata(message.title(), message.artist(), message.thumbnail());
                }
            }
            case UPDATE_QUEUE_STATE -> {
                runtimeState.queueStateJson = safe(message.url());
                runtimeState.updatedAtMs = now;
                if (radioBlockEntity != null) {
                    radioBlockEntity.setQueueStateJson(message.url());
                }
            }
            case TOGGLE_PAUSE -> {
                if (runtimeState.playing) {
                    runtimeState.positionMs = runtimeData.currentPositionMs(runtimeState);
                    runtimeState.playing = false;
                    runtimeState.updatedAtMs = now;
                    if (radioBlockEntity != null) {
                        radioBlockEntity.pause();
                    }
                } else {
                    runtimeState.playing = true;
                    runtimeState.updatedAtMs = now;
                    if (radioBlockEntity != null) {
                        radioBlockEntity.play();
                    }
                }
            }
            case STOP -> {
                runtimeState.playing = false;
                runtimeState.positionMs = 0L;
                runtimeState.trackDurationMs = -1L;
                runtimeState.updatedAtMs = now;
                if (radioBlockEntity != null) {
                    radioBlockEntity.stop();
                }
            }
            case SET_VOLUME -> {
                runtimeState.volume = Mth.clamp(message.volume(), 0f, 2f);
                runtimeState.updatedAtMs = now;
                if (radioBlockEntity != null) {
                    radioBlockEntity.setVolume(message.volume());
                }
            }
            case SEEK -> {
                runtimeState.positionMs = Math.max(0L, message.positionMs());
                runtimeState.updatedAtMs = now;
                if (radioBlockEntity != null) {
                    radioBlockEntity.seekTo(message.positionMs());
                }
            }
            case SYNC_RUNTIME -> {
                runtimeState.url = safe(message.url()).isBlank() ? runtimeState.url : safe(message.url());
                runtimeState.title = safe(message.title()).isBlank() ? runtimeState.title : safe(message.title());
                runtimeState.artist = safe(message.artist()).isBlank() ? runtimeState.artist : safe(message.artist());
                runtimeState.thumbnail = safe(message.thumbnail()).isBlank() ? runtimeState.thumbnail : safe(message.thumbnail());
                runtimeState.positionMs = Math.max(0L, message.positionMs());
                long syncedDurationMs = message.trackDurationMs();
                if (syncedDurationMs > 0L) {
                    runtimeState.trackDurationMs = syncedDurationMs;
                    if (!runtimeState.url.isBlank()) {
                        runtimeState.knownTrackDurationsMs.put(trackSyncKey(runtimeState.url), syncedDurationMs);
                    }
                }
                runtimeState.playing = true;
                runtimeState.updatedAtMs = now;
            }
        }

        if (radioBlockEntity != null) {
            runtimeState.volume = Mth.clamp(radioBlockEntity.getVolume(), 0f, 2f);
            runtimeState.queueStateJson = safe(radioBlockEntity.getQueueStateJson());
        }
        runtimeData.setDirty();
        MediaRadio.LOGGER.debug("Radio control {} for {} by {}", message.action(), !radioId.isBlank() ? radioId : blockPos, player.getGameProfile().getName());
    }

    public static void handleHandheldState(ServerPlayer player, ServerboundHandheldStateMessage message) {
        String radioId = message.radioId();
        if (radioId == null || radioId.isBlank()) {
            return;
        }

        RadioRuntimeStateSavedData runtimeData = RadioRuntimeStateSavedData.get(player.server);
        RadioRuntimeStateSavedData.RadioRuntimeState previousState = copyState(runtimeData.get(radioId));
        long previousPosition = runtimeData.currentPositionMs(previousState);
        String previousQueueForPacket = queueStateForPacket(previousState == null ? "" : previousState.queueStateJson);
        boolean previousContextActive = shouldBroadcastHandheldContext(player, radioId, previousState);
        runtimeData.setFromClient(
                radioId,
                message.url(),
                message.title(),
                message.artist(),
                message.thumbnail(),
                message.queueStateJson(),
                message.volume(),
                message.positionMs(),
                message.trackDurationMs(),
                message.seekSerial(),
                message.playing(),
                message.allowInventoryBroadcast()
        );

        RadioRuntimeStateSavedData.RadioRuntimeState state = runtimeData.getOrCreate(radioId);
        int previousSeekSerial = previousState == null ? -1 : previousState.seekSerial;
        long position = runtimeData.currentPositionMs(state);
        String queueForPacket = queueStateForPacket(state.queueStateJson);
        boolean contextActive = shouldBroadcastHandheldContext(player, radioId, state);
        boolean naturalLoopRestart = previousState != null
                && previousState.playing
                && state.playing
                && sameTrack(previousState.url, state.url)
                && previousPosition >= 2_000L
                && position + 1_250L < previousPosition;
        boolean seekEvent = (previousState != null && state.seekSerial != previousSeekSerial) || naturalLoopRestart;
        boolean forcePositionSync = seekEvent
                || previousState == null
                || previousState.playing != state.playing
                || !sameTrack(previousState.url, state.url);

        if (!seekEvent
                && !shouldBroadcastHandheldUpdate(previousState, previousPosition, state, position, previousQueueForPacket, queueForPacket, previousContextActive, contextActive)) {
            return;
        }

        for (ServerPlayer other : player.server.getPlayerList().getPlayers()) {
            if (!shouldBroadcastToHandheldListener(player, other)) {
                continue;
            }
            boolean inventoryPlayback = contextActive && isInventoryPlaybackContext(player, radioId);
            ModNetworking.sendPlayerRadioContext(other, radioId, player.getId(), contextActive, inventoryPlayback);
            ModNetworking.sendRadioState(other, new ClientboundRadioStateMessage(
                    radioId,
                    safe(state.url),
                    safe(state.title),
                    safe(state.artist),
                    safe(state.thumbnail),
                    queueForPacket,
                    Mth.clamp(state.volume, 0f, 2f),
                    position,
                    System.currentTimeMillis(),
                    forcePositionSync,
                    seekEvent,
                    state.playing
            ));
        }
    }

    public static void handleRadioStateRequest(ServerPlayer player, ServerboundRequestRadioStateMessage message) {
        String radioId = safe(message.radioId());
        if (radioId.isBlank()) {
            return;
        }
        RadioRuntimeStateSavedData runtimeData = RadioRuntimeStateSavedData.get(player.server);
        RadioRuntimeStateSavedData.RadioRuntimeState state = runtimeData.getOrCreate(radioId);
        long position = runtimeData.currentPositionMs(state);
        ModNetworking.sendRadioState(player, new ClientboundRadioStateMessage(
                radioId,
                safe(state.url),
                safe(state.title),
                safe(state.artist),
                safe(state.thumbnail),
                queueStateForPacket(state.queueStateJson),
                Mth.clamp(state.volume, 0f, 2f),
                position,
                System.currentTimeMillis(),
                true,
                false,
                state.playing
        ));
    }

    public static void applyRuntimeStateToBlockEntity(RadioBlockEntity radioBlockEntity) {
        if (radioBlockEntity == null || radioBlockEntity.getLevel() == null || radioBlockEntity.getLevel().isClientSide) {
            return;
        }
        String radioId = safe(radioBlockEntity.getRadioId());
        if (radioId.isBlank()) {
            return;
        }
        MinecraftServer server = radioBlockEntity.getLevel().getServer();
        if (server == null) {
            return;
        }
        RadioRuntimeStateSavedData runtimeData = RadioRuntimeStateSavedData.get(server);
        RadioRuntimeStateSavedData.RadioRuntimeState state = runtimeData.getOrCreate(radioId);
        long nowMs = System.currentTimeMillis();
        boolean changed = false;
        if (state.playing && !safe(state.url).isBlank()) {
            if (state.trackDurationMs <= 0L) {
                long resolvedDurationMs = resolveTrackDurationMs(state, state.url);
                if (resolvedDurationMs > 0L) {
                    state.trackDurationMs = resolvedDurationMs;
                    changed = true;
                }
            }
            long projectedPositionMs = runtimeData.currentPositionMs(state);
            if (state.trackDurationMs > 0L && projectedPositionMs + TRACK_END_EPSILON_MS >= state.trackDurationMs) {
                if (advanceUnownedPlaybackState(runtimeData, state, nowMs)) {
                    changed = true;
                }
            }
        }
        if (changed) {
            runtimeData.setDirty();
        }
        long position = runtimeData.currentPositionMs(state);
        radioBlockEntity.setMedia(state.url, state.title, state.artist, state.thumbnail);
        radioBlockEntity.setQueueStateJson(state.queueStateJson);
        radioBlockEntity.setVolume(state.volume);
        radioBlockEntity.setPausedPositionMs(position);
        if (state.playing && state.url != null && !state.url.isBlank()) {
            radioBlockEntity.play();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static SharedMediaSnapshot mergeSnapshotForPlayer(
            SharedMediaSnapshot current,
            SharedMediaSnapshot incoming,
            String playerId,
            String playerName
    ) {
        SharedMediaSnapshot merged = new SharedMediaSnapshot();

        // Library remains collaborative/global and follows incoming client state.
        merged.library.putAll(incoming.library);

        // Start from current playlists so unauthorized deletions/edits are ignored.
        for (Map.Entry<String, SharedMediaSnapshot.PlaylistEntry> entry : current.playlists.entrySet()) {
            merged.playlists.put(entry.getKey(), clonePlaylist(entry.getValue()));
        }

        // Apply owner-authorized deletions.
        for (Map.Entry<String, SharedMediaSnapshot.PlaylistEntry> entry : current.playlists.entrySet()) {
            String playlistId = entry.getKey();
            SharedMediaSnapshot.PlaylistEntry existing = entry.getValue();
            if (!incoming.playlists.containsKey(playlistId) && existing != null && existing.canEdit(playerId)) {
                merged.playlists.remove(playlistId);
            }
        }

        // Apply incoming updates and creations, bounded by ownership rules.
        for (Map.Entry<String, SharedMediaSnapshot.PlaylistEntry> entry : incoming.playlists.entrySet()) {
            String playlistId = entry.getKey();
            SharedMediaSnapshot.PlaylistEntry incomingEntry = clonePlaylist(entry.getValue());
            if (incomingEntry == null) {
                continue;
            }

            SharedMediaSnapshot.PlaylistEntry currentEntry = current.playlists.get(playlistId);
            if (currentEntry == null) {
                // New playlist creation must be owned by this player (or unspecified ownership).
                if (!incomingEntry.ownerId.isBlank() && !incomingEntry.ownerId.equals(playerId)) {
                    continue;
                }
                incomingEntry.ownerId = playerId;
                incomingEntry.ownerName = safe(playerName);
                merged.playlists.put(playlistId, incomingEntry);
                continue;
            }

            if (!currentEntry.canEdit(playerId)) {
                continue;
            }

            // Preserve canonical owner identity.
            if (!currentEntry.ownerId.isBlank()) {
                incomingEntry.ownerId = currentEntry.ownerId;
            } else {
                incomingEntry.ownerId = playerId;
            }
            if (!currentEntry.ownerName.isBlank()) {
                incomingEntry.ownerName = currentEntry.ownerName;
            } else {
                incomingEntry.ownerName = safe(playerName);
            }
            merged.playlists.put(playlistId, incomingEntry);
        }

        // Drop playlist media references that no longer exist in library.
        merged.playlists.values().forEach(playlist -> {
            if (playlist != null && playlist.mediaIds != null) {
                playlist.mediaIds.removeIf(mediaId -> mediaId == null || mediaId.isBlank() || !merged.library.containsKey(mediaId));
            }
        });

        return merged.sanitize();
    }

    private static SharedMediaSnapshot.PlaylistEntry clonePlaylist(SharedMediaSnapshot.PlaylistEntry source) {
        if (source == null) {
            return null;
        }
        SharedMediaSnapshot.PlaylistEntry copy = new SharedMediaSnapshot.PlaylistEntry();
        copy.id = source.id;
        copy.name = source.name;
        copy.thumbnail = source.thumbnail;
        copy.mediaIds = source.mediaIds == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(source.mediaIds);
        copy.ownerId = source.ownerId;
        copy.ownerName = source.ownerName;
        copy.access = source.access;
        copy.invitedPlayerIds = source.invitedPlayerIds == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(source.invitedPlayerIds);
        copy.invitedPlayerNames = source.invitedPlayerNames == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(source.invitedPlayerNames);
        copy.sanitize();
        return copy;
    }

    private static boolean shouldBroadcastHandheldContext(ServerPlayer player, String radioId, RadioRuntimeStateSavedData.RadioRuntimeState state) {
        if (player == null || radioId == null || radioId.isBlank() || state == null) {
            return false;
        }
        if (!state.playing || safe(state.url).isBlank()) {
            return false;
        }
        ItemStack main = player.getMainHandItem();
        if (main.is(net.jacobwasbeast.mediaradio.registry.ModItems.RADIO_ITEM)
                && radioId.equals(net.jacobwasbeast.mediaradio.item.RadioItem.getRadioId(main))
                && !net.jacobwasbeast.mediaradio.item.RadioItem.isPlaceMode(main)) {
            return true;
        }
        ItemStack off = player.getOffhandItem();
        if (off.is(net.jacobwasbeast.mediaradio.registry.ModItems.RADIO_ITEM)
                && radioId.equals(net.jacobwasbeast.mediaradio.item.RadioItem.getRadioId(off))
                && !net.jacobwasbeast.mediaradio.item.RadioItem.isPlaceMode(off)) {
            return true;
        }
        if (!state.allowInventoryBroadcast) {
            return false;
        }
        return hasRadioInInventory(player, radioId);
    }

    private static boolean shouldBroadcastHandheldUpdate(
            RadioRuntimeStateSavedData.RadioRuntimeState previous,
            long previousPosition,
            RadioRuntimeStateSavedData.RadioRuntimeState current,
            long currentPosition,
            String previousQueueForPacket,
            String currentQueueForPacket,
            boolean previousContextActive,
            boolean currentContextActive
    ) {
        if (current == null) {
            return false;
        }
        if (previous == null) {
            return true;
        }
        if (previousContextActive != currentContextActive) {
            return true;
        }
        if (!sameTrack(previous.url, current.url)) {
            return true;
        }
        if (!safe(previous.title).equals(safe(current.title))) {
            return true;
        }
        if (!safe(previous.artist).equals(safe(current.artist))) {
            return true;
        }
        if (!safe(previous.thumbnail).equals(safe(current.thumbnail))) {
            return true;
        }
        if (!Objects.equals(previousQueueForPacket, currentQueueForPacket)) {
            return true;
        }
        if (previous.playing != current.playing) {
            return true;
        }
        if (Math.abs(previous.volume - current.volume) > 0.01f) {
            return true;
        }

        // While playing, only broadcast larger jumps here. Periodic correction is handled separately.
        if (current.playing) {
            return false;
        }
        // While paused/stopped, propagate meaningful seek updates.
        return Math.abs(currentPosition - previousPosition) >= 250L;
    }

    private static RadioRuntimeStateSavedData.RadioRuntimeState copyState(RadioRuntimeStateSavedData.RadioRuntimeState source) {
        if (source == null) {
            return null;
        }
        RadioRuntimeStateSavedData.RadioRuntimeState copy = new RadioRuntimeStateSavedData.RadioRuntimeState();
        copy.url = safe(source.url);
        copy.title = safe(source.title);
        copy.artist = safe(source.artist);
        copy.thumbnail = safe(source.thumbnail);
        copy.queueStateJson = safe(source.queueStateJson);
        copy.volume = Mth.clamp(source.volume, 0f, 2f);
        copy.positionMs = Math.max(0L, source.positionMs);
        copy.trackDurationMs = source.trackDurationMs;
        copy.knownTrackDurationsMs = source.knownTrackDurationsMs == null
                ? new HashMap<>()
                : new HashMap<>(source.knownTrackDurationsMs);
        copy.seekSerial = Math.max(0, source.seekSerial);
        copy.playing = source.playing;
        copy.allowInventoryBroadcast = source.allowInventoryBroadcast;
        copy.updatedAtMs = Math.max(0L, source.updatedAtMs);
        return copy;
    }

    private static void syncActiveHandheldRadiosToPlayer(ServerPlayer target) {
        if (target == null || target.server == null) {
            return;
        }

        RadioRuntimeStateSavedData runtimeData = RadioRuntimeStateSavedData.get(target.server);
        for (ServerPlayer owner : target.server.getPlayerList().getPlayers()) {
            if (owner == null || owner == target) {
                continue;
            }
            Set<String> candidateRadioIds = candidateHandheldRadioIds(owner);
            if (candidateRadioIds.isEmpty()) {
                continue;
            }
            for (String radioId : candidateRadioIds) {
                RadioRuntimeStateSavedData.RadioRuntimeState state = runtimeData.get(radioId);
                if (!shouldBroadcastHandheldContext(owner, radioId, state)) {
                    continue;
                }
                boolean inventoryPlayback = isInventoryPlaybackContext(owner, radioId);
                ModNetworking.sendPlayerRadioContext(target, radioId, owner.getId(), true, inventoryPlayback);
                ModNetworking.sendRadioState(target, new ClientboundRadioStateMessage(
                        radioId,
                        safe(state.url),
                        safe(state.title),
                        safe(state.artist),
                        safe(state.thumbnail),
                        queueStateForPacket(state.queueStateJson),
                        Mth.clamp(state.volume, 0f, 2f),
                        runtimeData.currentPositionMs(state),
                        System.currentTimeMillis(),
                        true,
                        false,
                        state.playing
                ));
            }
        }
    }

    private static void syncActiveHandheldRadiosToNearbyPlayers(MinecraftServer server) {
        if (server == null || server.getTickCount() % HANDHELD_PERIODIC_SYNC_INTERVAL_TICKS != 0) {
            return;
        }
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }

        RadioRuntimeStateSavedData runtimeData = RadioRuntimeStateSavedData.get(server);
        Set<String> currentActiveContexts = new HashSet<>();
        for (ServerPlayer owner : players) {
            if (owner == null) {
                continue;
            }
            Set<String> candidateRadioIds = candidateHandheldRadioIds(owner);
            if (candidateRadioIds.isEmpty()) {
                continue;
            }
            for (String radioId : candidateRadioIds) {
                RadioRuntimeStateSavedData.RadioRuntimeState state = runtimeData.get(radioId);
                if (!shouldBroadcastHandheldContext(owner, radioId, state)) {
                    continue;
                }
                long position = runtimeData.currentPositionMs(state);
                String queueForPacket = queueStateForPacket(state.queueStateJson);
                long sentAtMs = System.currentTimeMillis();
                for (ServerPlayer listener : players) {
                    if (!shouldBroadcastToHandheldListener(owner, listener)) {
                        continue;
                    }
                    String contextKey = handheldContextKey(owner, listener, radioId);
                    currentActiveContexts.add(contextKey);
                    boolean inventoryPlayback = isInventoryPlaybackContext(owner, radioId);
                    ModNetworking.sendPlayerRadioContext(listener, radioId, owner.getId(), true, inventoryPlayback);
                    if (!ACTIVE_HANDHELD_LISTENER_CONTEXTS.containsKey(contextKey)) {
                        ModNetworking.sendRadioState(listener, new ClientboundRadioStateMessage(
                                radioId,
                                safe(state.url),
                                safe(state.title),
                                safe(state.artist),
                                safe(state.thumbnail),
                                queueForPacket,
                                Mth.clamp(state.volume, 0f, 2f),
                                position,
                                sentAtMs,
                                true,
                                false,
                                state.playing
                        ));
                    }
                    ACTIVE_HANDHELD_LISTENER_CONTEXTS.put(contextKey, new HandheldListenerContext(listener.getUUID(), radioId));
                }
            }
        }

        Set<String> staleContexts = new HashSet<>(ACTIVE_HANDHELD_LISTENER_CONTEXTS.keySet());
        staleContexts.removeAll(currentActiveContexts);
        for (String staleKey : staleContexts) {
            HandheldListenerContext context = ACTIVE_HANDHELD_LISTENER_CONTEXTS.remove(staleKey);
            if (context == null) {
                continue;
            }
            ServerPlayer listener = server.getPlayerList().getPlayer(context.listenerUuid);
            if (listener != null) {
                ModNetworking.sendPlayerRadioContext(listener, context.radioId, 0, false, false);
            }
        }
    }

    private static String handheldContextKey(ServerPlayer owner, ServerPlayer listener, String radioId) {
        return owner.getUUID() + "|" + listener.getUUID() + "|" + safe(radioId);
    }

    private static boolean shouldBroadcastToHandheldListener(ServerPlayer owner, ServerPlayer listener) {
        if (owner == null || listener == null || owner == listener) {
            return false;
        }
        if (owner.level() != listener.level()) {
            return false;
        }
        return owner.distanceToSqr(listener) <= HANDHELD_LISTENER_SYNC_RANGE_SQR;
    }

    private static Set<String> candidateHandheldRadioIds(ServerPlayer player) {
        Set<String> radioIds = new HashSet<>();
        if (player == null) {
            return radioIds;
        }
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.is(net.jacobwasbeast.mediaradio.registry.ModItems.RADIO_ITEM)) {
                continue;
            }
            String id = safe(net.jacobwasbeast.mediaradio.item.RadioItem.getRadioId(stack));
            if (!id.isBlank()) {
                radioIds.add(id);
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (!stack.is(net.jacobwasbeast.mediaradio.registry.ModItems.RADIO_ITEM)) {
                continue;
            }
            String id = safe(net.jacobwasbeast.mediaradio.item.RadioItem.getRadioId(stack));
            if (!id.isBlank()) {
                radioIds.add(id);
            }
        }
        return radioIds;
    }

    private static boolean hasRadioInInventory(ServerPlayer player, String radioId) {
        if (player == null || radioId == null || radioId.isBlank()) {
            return false;
        }
        int selectedSlot = player.getInventory().selected;
        for (int i = 0; i < player.getInventory().items.size(); i++) {
            if (i == selectedSlot) {
                continue;
            }
            ItemStack stack = player.getInventory().items.get(i);
            if (!stack.is(net.jacobwasbeast.mediaradio.registry.ModItems.RADIO_ITEM)) {
                continue;
            }
            if (radioId.equals(net.jacobwasbeast.mediaradio.item.RadioItem.getRadioId(stack))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHeldHandRadio(ServerPlayer player, String radioId) {
        if (player == null || radioId == null || radioId.isBlank()) {
            return false;
        }
        ItemStack main = player.getMainHandItem();
        if (main.is(net.jacobwasbeast.mediaradio.registry.ModItems.RADIO_ITEM)
                && radioId.equals(net.jacobwasbeast.mediaradio.item.RadioItem.getRadioId(main))
                && !net.jacobwasbeast.mediaradio.item.RadioItem.isPlaceMode(main)) {
            return true;
        }
        ItemStack off = player.getOffhandItem();
        return off.is(net.jacobwasbeast.mediaradio.registry.ModItems.RADIO_ITEM)
                && radioId.equals(net.jacobwasbeast.mediaradio.item.RadioItem.getRadioId(off))
                && !net.jacobwasbeast.mediaradio.item.RadioItem.isPlaceMode(off);
    }

    private static boolean isInventoryPlaybackContext(ServerPlayer player, String radioId) {
        return !isHeldHandRadio(player, radioId) && hasRadioInInventory(player, radioId);
    }

    private static void simulateUnownedRadioPlayback(MinecraftServer server) {
        if (server == null || server.getTickCount() % UNOWNED_RUNTIME_SIMULATION_INTERVAL_TICKS != 0) {
            return;
        }

        RadioRuntimeStateSavedData runtimeData = RadioRuntimeStateSavedData.get(server);
        if (runtimeData.states().isEmpty()) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        boolean changedAny = false;
        for (Map.Entry<String, RadioRuntimeStateSavedData.RadioRuntimeState> entry : runtimeData.states().entrySet()) {
            String radioId = safe(entry.getKey());
            RadioRuntimeStateSavedData.RadioRuntimeState state = entry.getValue();
            if (radioId.isBlank() || state == null || !state.playing || safe(state.url).isBlank()) {
                continue;
            }
            if (nowMs - Math.max(0L, state.updatedAtMs) < UNOWNED_RUNTIME_STALE_MS) {
                continue;
            }
            if (state.trackDurationMs <= 0L) {
                long resolvedDurationMs = resolveTrackDurationMs(state, state.url);
                if (resolvedDurationMs > 0L) {
                    state.trackDurationMs = resolvedDurationMs;
                    changedAny = true;
                }
            }
            if (state.trackDurationMs <= 0L) {
                continue;
            }
            if (advanceUnownedPlaybackState(runtimeData, state, nowMs)) {
                changedAny = true;
            }
        }

        if (changedAny) {
            runtimeData.setDirty();
        }
    }

    private static boolean advanceUnownedPlaybackState(
            RadioRuntimeStateSavedData runtimeData,
            RadioRuntimeStateSavedData.RadioRuntimeState state,
            long nowMs
    ) {
        if (runtimeData == null || state == null || !state.playing || safe(state.url).isBlank()) {
            return false;
        }
        long projectedPositionMs = runtimeData.currentPositionMs(state);
        long currentDurationMs = state.trackDurationMs <= 0L ? resolveTrackDurationMs(state, state.url) : state.trackDurationMs;
        if (currentDurationMs <= 0L || projectedPositionMs + TRACK_END_EPSILON_MS < currentDurationMs) {
            return false;
        }

        QueueStatePayload payload = parseQueueState(state.queueStateJson);
        if (payload == null || payload.entries == null || payload.entries.isEmpty()) {
            state.playing = false;
            state.positionMs = 0L;
            state.trackDurationMs = -1L;
            state.url = "";
            state.title = "";
            state.artist = "";
            state.thumbnail = "";
            state.updatedAtMs = nowMs;
            return true;
        }

        int currentIndex = resolveCurrentQueueIndex(payload, state.url);
        if (currentIndex < 0 || currentIndex >= payload.entries.size()) {
            return false;
        }

        long remainingMs = projectedPositionMs;
        if (payload.loopMode == QueueLoopMode.ONE && currentDurationMs > 0L) {
            long loopedPosition = remainingMs % currentDurationMs;
            if (loopedPosition == Math.max(0L, state.positionMs)) {
                return false;
            }
            state.positionMs = loopedPosition;
            state.updatedAtMs = nowMs;
            return true;
        }

        boolean changed = false;
        int transitions = 0;
        while (state.playing && transitions++ < MAX_SIMULATED_TRANSITIONS_PER_TICK) {
            QueueMediaPayload currentEntry = payload.entries.get(currentIndex);
            long durationMs = resolveTrackDurationMs(state, currentEntry.url);
            if (durationMs <= 0L) {
                break;
            }
            if (remainingMs + TRACK_END_EPSILON_MS < durationMs) {
                break;
            }
            remainingMs = Math.max(0L, remainingMs - durationMs);

            if (payload.loopMode == QueueLoopMode.ONE) {
                changed = true;
                continue;
            }

            int nextIndex = currentIndex + 1;
            if (nextIndex >= payload.entries.size()) {
                if (payload.loopMode == QueueLoopMode.ALL) {
                    nextIndex = 0;
                } else {
                    payload.currentQueueItemId = safe(currentEntry.queueItemId);
                    payload.queueIndex = currentIndex;
                    state.queueStateJson = GSON.toJson(payload);
                    state.playing = false;
                    state.positionMs = 0L;
                    state.trackDurationMs = -1L;
                    state.url = "";
                    state.title = "";
                    state.artist = "";
                    state.thumbnail = "";
                    state.updatedAtMs = nowMs;
                    return true;
                }
            }

            currentIndex = nextIndex;
            QueueMediaPayload nextEntry = payload.entries.get(currentIndex);
            payload.currentQueueItemId = safe(nextEntry.queueItemId);
            payload.queueIndex = currentIndex;
            state.url = safe(nextEntry.url);
            state.title = safe(nextEntry.title);
            state.artist = safe(nextEntry.artist);
            state.thumbnail = safe(nextEntry.thumbnail);
            state.trackDurationMs = resolveTrackDurationMs(state, state.url);
            changed = true;
        }

        long clampedPosition = Math.max(0L, remainingMs);
        if (!changed && clampedPosition == Math.max(0L, state.positionMs)) {
            return false;
        }

        state.positionMs = clampedPosition;
        state.updatedAtMs = nowMs;
        state.queueStateJson = GSON.toJson(payload);
        if (state.trackDurationMs <= 0L && !state.url.isBlank()) {
            state.trackDurationMs = resolveTrackDurationMs(state, state.url);
        }
        return true;
    }

    private static long resolveTrackDurationMs(RadioRuntimeStateSavedData.RadioRuntimeState state, String url) {
        if (state == null || url == null || url.isBlank()) {
            return -1L;
        }
        if (state.trackDurationMs > 0L && sameTrack(state.url, url)) {
            return state.trackDurationMs;
        }
        if (state.knownTrackDurationsMs == null || state.knownTrackDurationsMs.isEmpty()) {
            return -1L;
        }
        Long known = state.knownTrackDurationsMs.get(trackSyncKey(url));
        if (known == null || known <= 0L || known == Long.MAX_VALUE) {
            return -1L;
        }
        return known;
    }

    private static QueueStatePayload parseQueueState(String queueStateJson) {
        String json = safe(queueStateJson);
        if (json.isBlank()) {
            return null;
        }
        try {
            QueueStatePayload payload = GSON.fromJson(json, QueueStatePayload.class);
            if (payload == null || payload.entries == null) {
                return null;
            }
            payload.entries.removeIf(entry -> entry == null || safe(entry.url).isBlank());
            if (payload.entries.isEmpty()) {
                return null;
            }
            if (payload.loopMode == null) {
                payload.loopMode = QueueLoopMode.ALL;
            }
            if (payload.currentQueueItemId == null) {
                payload.currentQueueItemId = "";
            }
            return payload;
        } catch (JsonSyntaxException ignored) {
            return null;
        }
    }

    private static int resolveCurrentQueueIndex(QueueStatePayload payload, String currentUrl) {
        if (payload == null || payload.entries == null || payload.entries.isEmpty()) {
            return -1;
        }
        if (payload.currentQueueItemId != null && !payload.currentQueueItemId.isBlank()) {
            for (int i = 0; i < payload.entries.size(); i++) {
                QueueMediaPayload entry = payload.entries.get(i);
                if (entry != null && payload.currentQueueItemId.equals(safe(entry.queueItemId))) {
                    return i;
                }
            }
        }
        if (payload.queueIndex >= 0 && payload.queueIndex < payload.entries.size()) {
            return payload.queueIndex;
        }
        String url = safe(currentUrl);
        if (!url.isBlank()) {
            for (int i = 0; i < payload.entries.size(); i++) {
                QueueMediaPayload entry = payload.entries.get(i);
                if (entry != null && sameTrack(url, entry.url)) {
                    return i;
                }
            }
        }
        return 0;
    }

    private static boolean sameTrack(String left, String right) {
        return trackSyncKey(left).equals(trackSyncKey(right));
    }

    private static String trackSyncKey(String url) {
        String safeUrl = safe(url);
        if (safeUrl.isBlank()) {
            return "";
        }
        String trimmed = safeUrl.trim();
        try {
            URI uri = new URI(trimmed);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (host.contains("youtube.com")) {
                String videoId = queryParam(uri.getRawQuery(), "v");
                if (!videoId.isBlank()) {
                    return "yt:" + videoId;
                }
            } else if (host.equals("youtu.be")) {
                String id = path.startsWith("/") ? path.substring(1) : path;
                int slash = id.indexOf('/');
                if (slash >= 0) {
                    id = id.substring(0, slash);
                }
                if (!id.isBlank()) {
                    return "yt:" + id;
                }
            }
            String normalizedPath = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
            return host + normalizedPath;
        } catch (URISyntaxException ignored) {
            return trimmed;
        }
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank() || key == null || key.isBlank()) {
            return "";
        }
        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            int separator = pair.indexOf('=');
            String k = separator >= 0 ? pair.substring(0, separator) : pair;
            if (!key.equals(k)) {
                continue;
            }
            String value = separator >= 0 && separator + 1 < pair.length() ? pair.substring(separator + 1) : "";
            return value == null ? "" : value;
        }
        return "";
    }

    private static String queueStateForPacket(String queueStateJson) {
        String safe = safe(queueStateJson);
        if (safe.isBlank()) {
            return "";
        }
        if (safe.length() <= MAX_QUEUE_STATE_RADIO_PACKET) {
            return safe;
        }
        return "";
    }

    private static class QueueStatePayload {
        private java.util.List<QueueMediaPayload> entries = new java.util.ArrayList<>();
        private String currentQueueItemId = "";
        private int queueIndex = -1;
        private QueueLoopMode loopMode = QueueLoopMode.ALL;
    }

    private static class QueueMediaPayload {
        private String queueItemId = "";
        private String url = "";
        private String title = "";
        private String artist = "";
        private String thumbnail = "";
    }

    private enum QueueLoopMode {
        NONE,
        ONE,
        ALL
    }

    private static class ChunkAccumulator {
        private final int totalChunks;
        private final String[] chunks;
        private final long createdAtMs;
        private int received;
        private int totalLength;

        private ChunkAccumulator(int totalChunks, long createdAtMs) {
            this.totalChunks = totalChunks;
            this.chunks = new String[totalChunks];
            this.createdAtMs = createdAtMs;
        }

        private boolean accept(int index, String chunk, int maxLength) {
            if (index < 0 || index >= totalChunks) {
                return false;
            }
            if (chunks[index] != null) {
                return true;
            }
            chunks[index] = chunk;
            received++;
            totalLength += chunk.length();
            return totalLength <= maxLength;
        }

        private boolean complete() {
            return received == totalChunks;
        }

        private String join() {
            StringBuilder builder = new StringBuilder(totalLength);
            for (String chunk : chunks) {
                if (chunk != null) {
                    builder.append(chunk);
                }
            }
            return builder.toString();
        }
    }

    private static class HandheldListenerContext {
        private final java.util.UUID listenerUuid;
        private final String radioId;

        private HandheldListenerContext(java.util.UUID listenerUuid, String radioId) {
            this.listenerUuid = listenerUuid;
            this.radioId = safe(radioId);
        }
    }
}
