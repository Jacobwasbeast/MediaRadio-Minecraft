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
import net.jacobwasbeast.mediaradio.network.message.ClientboundSessionCommandResultMessage;
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
    private static final long COMMAND_DEDUPE_WINDOW_MS = 30_000L;
    private static final int MAX_QUEUE_STATE_RADIO_PACKET = 30_000;
    private static final int HANDHELD_PERIODIC_SYNC_INTERVAL_TICKS = 40;
    private static final int UNOWNED_RUNTIME_SIMULATION_INTERVAL_TICKS = 20;
    private static final long UNOWNED_RUNTIME_STALE_MS = 3_000L;
    private static final long TRACK_END_EPSILON_MS = 250L;
    private static final int MAX_SIMULATED_TRANSITIONS_PER_TICK = 128;
    private static final double HANDHELD_LISTENER_SYNC_RANGE_SQR = 96.0D * 96.0D;
    private static final double BLOCK_CONTROL_MAX_DISTANCE_SQR = 24.0D * 24.0D;
    private static final String RUNTIME_SCOPE_HANDHELD = "handheld";
    private static final String RUNTIME_SCOPE_BLOCK = "block";
    private static final Gson GSON = new Gson();
    private static final Map<String, ChunkAccumulator> CHUNK_UPLOADS = new HashMap<>();
    private static final Map<String, HandheldListenerContext> ACTIVE_HANDHELD_LISTENER_CONTEXTS = new HashMap<>();
    private static final Map<String, Long> RECENT_SESSION_COMMANDS = new HashMap<>();

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
        ServerboundRadioControlMessage.Context messageContext = message.context() == null
                ? ServerboundRadioControlMessage.Context.BLOCK
                : message.context();
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
            } else {
                return;
            }
        } else if (radioId.isBlank()) {
            return;
        }

        RadioRuntimeStateSavedData runtimeData = RadioRuntimeStateSavedData.get(player.server);
        boolean blockScoped = blockPos != null || messageContext == ServerboundRadioControlMessage.Context.BLOCK;
        ServerboundRequestRadioStateMessage.Context responseContext = blockScoped
                ? ServerboundRequestRadioStateMessage.Context.BLOCK
                : ServerboundRequestRadioStateMessage.Context.HANDHELD;
        String runtimeKey = blockScoped ? blockRuntimeKey(radioId) : handheldRuntimeKey(radioId);
        RadioRuntimeStateSavedData.RadioRuntimeState runtimeState = resolveOrCreateScopedState(
                runtimeData,
                runtimeKey,
                radioId
        );
        ensureSessionIdentity(runtimeState, runtimeKey);
        if (!authorizeRuntimeControl(player, radioId, runtimeState, runtimeData, blockScoped, radioBlockEntity)) {
            sendSessionCommandResult(
                    player,
                    radioId,
                    runtimeState,
                    false,
                    ClientboundSessionCommandResultMessage.Reason.UNAUTHORIZED,
                    responseContext
            );
            return;
        }
        long now = System.currentTimeMillis();
        if (isDuplicateCommand(runtimeState, player.getUUID(), message.commandId(), now)) {
            sendSessionCommandResult(
                    player,
                    radioId,
                    runtimeState,
                    false,
                    ClientboundSessionCommandResultMessage.Reason.DUPLICATE_COMMAND,
                    responseContext
            );
            return;
        }
        if (isStaleRevision(message.knownRevision(), runtimeState.revision)) {
            sendSessionCommandResult(
                    player,
                    radioId,
                    runtimeState,
                    false,
                    ClientboundSessionCommandResultMessage.Reason.STALE_REVISION,
                    responseContext
            );
            return;
        }

        boolean runtimeChanged = false;
        switch (message.action()) {
            case PLAY_URL -> {
                String incomingUrl = safe(message.url());
                if (incomingUrl.isBlank()) {
                    break;
                }
                boolean sameTrack = sameTrack(runtimeState.url, incomingUrl);
                boolean wasPlaying = runtimeState.playing;
                long requestedPosition = Math.max(0L, message.positionMs());
                float requestedVolume = Mth.clamp(message.volume(), 0f, 2f);
                String requestedTitle = safe(message.title());
                String requestedArtist = safe(message.artist());
                String requestedThumbnail = safe(message.thumbnail());
                long incomingDurationMs = message.trackDurationMs();
                long resolvedDurationMs = incomingDurationMs > 0L
                        ? incomingDurationMs
                        : resolveTrackDurationMs(runtimeState, incomingUrl);

                String targetTitle = (!requestedTitle.isBlank() || !sameTrack) ? requestedTitle : safe(runtimeState.title);
                String targetArtist = (!requestedArtist.isBlank() || !sameTrack) ? requestedArtist : safe(runtimeState.artist);
                String targetThumbnail = (!requestedThumbnail.isBlank() || !sameTrack) ? requestedThumbnail : safe(runtimeState.thumbnail);
                boolean metadataChanged = !safe(runtimeState.title).equals(targetTitle)
                        || !safe(runtimeState.artist).equals(targetArtist)
                        || !safe(runtimeState.thumbnail).equals(targetThumbnail);
                boolean volumeChanged = Math.abs(runtimeState.volume - requestedVolume) > 0.0001f;
                boolean durationChanged = resolvedDurationMs > 0L && runtimeState.trackDurationMs != resolvedDurationMs;
                boolean seekRequested = requestedPosition > 0L;
                boolean noOpRepeat = sameTrack && wasPlaying && !metadataChanged && !volumeChanged && !durationChanged && !seekRequested;
                if (noOpRepeat) {
                    break;
                }

                if (sameTrack && wasPlaying && !seekRequested) {
                    preservePlaybackTimelineAnchor(runtimeData, runtimeState, now);
                } else if (!sameTrack || seekRequested) {
                    runtimeState.positionMs = requestedPosition;
                }

                runtimeState.url = incomingUrl;
                runtimeState.title = targetTitle;
                runtimeState.artist = targetArtist;
                runtimeState.thumbnail = targetThumbnail;
                if (resolvedDurationMs > 0L) {
                    runtimeState.trackDurationMs = resolvedDurationMs;
                    runtimeState.knownTrackDurationsMs.put(trackSyncKey(runtimeState.url), resolvedDurationMs);
                } else {
                    runtimeState.trackDurationMs = resolveTrackDurationMs(runtimeState, runtimeState.url);
                }
                runtimeState.playing = true;
                runtimeState.updatedAtMs = now;
                runtimeState.volume = requestedVolume;
                runtimeChanged = true;
                if (radioBlockEntity != null) {
                    if (sameTrack) {
                        radioBlockEntity.updateMetadata(runtimeState.title, runtimeState.artist, runtimeState.thumbnail);
                        if (seekRequested) {
                            radioBlockEntity.seekTo(requestedPosition);
                        }
                        if (!radioBlockEntity.isPlaying()) {
                            radioBlockEntity.play();
                        }
                    } else {
                        radioBlockEntity.setMedia(runtimeState.url, runtimeState.title, runtimeState.artist, runtimeState.thumbnail);
                        if (seekRequested) {
                            radioBlockEntity.seekTo(requestedPosition);
                        }
                        radioBlockEntity.play();
                    }
                }
            }
            case UPDATE_METADATA -> {
                String requestedTitle = safe(message.title());
                String requestedArtist = safe(message.artist());
                String requestedThumbnail = safe(message.thumbnail());
                if (!safe(runtimeState.title).equals(requestedTitle)
                        || !safe(runtimeState.artist).equals(requestedArtist)
                        || !safe(runtimeState.thumbnail).equals(requestedThumbnail)) {
                    preservePlaybackTimelineAnchor(runtimeData, runtimeState, now);
                    runtimeState.title = requestedTitle;
                    runtimeState.artist = requestedArtist;
                    runtimeState.thumbnail = requestedThumbnail;
                    runtimeState.updatedAtMs = now;
                    runtimeChanged = true;
                    if (radioBlockEntity != null) {
                        radioBlockEntity.updateMetadata(runtimeState.title, runtimeState.artist, runtimeState.thumbnail);
                    }
                }
            }
            case UPDATE_QUEUE_STATE -> {
                String incomingQueueState = safe(message.url());
                String mergedQueueState = mergeIncomingQueueState(runtimeState.queueStateJson, incomingQueueState);
                if (!safe(runtimeState.queueStateJson).equals(mergedQueueState)) {
                    preservePlaybackTimelineAnchor(runtimeData, runtimeState, now);
                    runtimeState.queueStateJson = mergedQueueState;
                    runtimeState.updatedAtMs = now;
                    runtimeChanged = true;
                    if (radioBlockEntity != null) {
                        radioBlockEntity.setQueueStateJson(mergedQueueState);
                    }
                }
            }
            case TOGGLE_PAUSE -> {
                if (runtimeState.playing) {
                    runtimeState.positionMs = runtimeData.currentPositionMs(runtimeState);
                    runtimeState.playing = false;
                    runtimeState.updatedAtMs = now;
                    runtimeChanged = true;
                    if (radioBlockEntity != null) {
                        radioBlockEntity.pause();
                    }
                } else {
                    if (safe(runtimeState.url).isBlank()) {
                        break;
                    }
                    runtimeState.playing = true;
                    runtimeState.updatedAtMs = now;
                    runtimeChanged = true;
                    if (radioBlockEntity != null) {
                        radioBlockEntity.play();
                    }
                }
            }
            case STOP -> {
                boolean wasStopped = !runtimeState.playing
                        && Math.max(0L, runtimeState.positionMs) == 0L
                        && safe(runtimeState.url).isBlank();
                if (!wasStopped) {
                    runtimeState.playing = false;
                    runtimeState.positionMs = 0L;
                    runtimeState.trackDurationMs = -1L;
                    runtimeState.updatedAtMs = now;
                    runtimeState.url = "";
                    runtimeState.title = "";
                    runtimeState.artist = "";
                    runtimeState.thumbnail = "";
                    runtimeChanged = true;
                    if (radioBlockEntity != null) {
                        radioBlockEntity.stop();
                    }
                }
            }
            case SET_VOLUME -> {
                float clampedVolume = Mth.clamp(message.volume(), 0f, 2f);
                if (Math.abs(runtimeState.volume - clampedVolume) > 0.0001f) {
                    preservePlaybackTimelineAnchor(runtimeData, runtimeState, now);
                    runtimeState.volume = clampedVolume;
                    runtimeState.updatedAtMs = now;
                    runtimeChanged = true;
                    if (radioBlockEntity != null) {
                        radioBlockEntity.setVolume(clampedVolume);
                    }
                }
            }
            case SEEK -> {
                long targetPosition = Math.max(0L, message.positionMs());
                long currentPosition = runtimeData.currentPositionMs(runtimeState);
                if (Math.abs(currentPosition - targetPosition) >= 50L || !runtimeState.playing) {
                    runtimeState.positionMs = targetPosition;
                    runtimeState.updatedAtMs = now;
                    runtimeChanged = true;
                    if (radioBlockEntity != null) {
                        radioBlockEntity.seekTo(targetPosition);
                    }
                }
            }
            case SYNC_RUNTIME -> {
                String syncedUrl = safe(message.url());
                String syncedTitle = safe(message.title());
                String syncedArtist = safe(message.artist());
                String syncedThumbnail = safe(message.thumbnail());
                long syncedDurationMs = message.trackDurationMs();
                String runtimeUrl = safe(runtimeState.url);
                boolean trackMatches = !runtimeUrl.isBlank()
                        && ((!syncedUrl.isBlank() && sameTrack(runtimeUrl, syncedUrl)) || syncedUrl.isBlank());
                boolean changed = false;
                // Runtime sync packets from clients are advisory only. Never let them override
                // authoritative transport decisions (play/pause/seek/queue pointer).
                if (trackMatches && syncedDurationMs > 0L && runtimeState.trackDurationMs != syncedDurationMs) {
                    runtimeState.trackDurationMs = syncedDurationMs;
                    if (runtimeState.knownTrackDurationsMs == null) {
                        runtimeState.knownTrackDurationsMs = new HashMap<>();
                    }
                    runtimeState.knownTrackDurationsMs.put(trackSyncKey(runtimeUrl), syncedDurationMs);
                    changed = true;
                }
                if (trackMatches && safe(runtimeState.title).isBlank() && !syncedTitle.isBlank()) {
                    runtimeState.title = syncedTitle;
                    changed = true;
                }
                if (trackMatches && safe(runtimeState.artist).isBlank() && !syncedArtist.isBlank()) {
                    runtimeState.artist = syncedArtist;
                    changed = true;
                }
                if (trackMatches && safe(runtimeState.thumbnail).isBlank() && !syncedThumbnail.isBlank()) {
                    runtimeState.thumbnail = syncedThumbnail;
                    changed = true;
                }
                if (changed) {
                    runtimeState.updatedAtMs = now;
                    runtimeChanged = true;
                }
            }
        }

        if (runtimeChanged && alignQueueStateToCurrentTrack(runtimeState)) {
            if (radioBlockEntity != null) {
                radioBlockEntity.setQueueStateJson(runtimeState.queueStateJson);
            }
        }

        if (!runtimeChanged) {
            return;
        }
        if (radioBlockEntity != null) {
            runtimeState.volume = Mth.clamp(radioBlockEntity.getVolume(), 0f, 2f);
            if (safe(runtimeState.queueStateJson).isBlank()) {
                runtimeState.queueStateJson = safe(radioBlockEntity.getQueueStateJson());
            }
        }
        markRuntimeMutation(runtimeState, runtimeKey, now);
        runtimeData.setDirty();
        syncLoadedBlockEntitiesForRadioId(radioId);
        if (!blockScoped) {
            boolean seekEvent = message.action() == ServerboundRadioControlMessage.Action.SEEK;
            boolean forcePositionSync = seekEvent
                    || message.action() == ServerboundRadioControlMessage.Action.PLAY_URL
                    || message.action() == ServerboundRadioControlMessage.Action.STOP
                    || message.action() == ServerboundRadioControlMessage.Action.SYNC_RUNTIME;
            broadcastHandheldRuntimeState(player, radioId, runtimeData, runtimeState, forcePositionSync, seekEvent);
        }
        MediaRadio.LOGGER.debug("Radio control {} for {} by {}", message.action(), !radioId.isBlank() ? radioId : blockPos, player.getGameProfile().getName());
    }

    public static void handleHandheldState(ServerPlayer player, ServerboundHandheldStateMessage message) {
        String radioId = message.radioId();
        if (radioId == null || radioId.isBlank()) {
            return;
        }

        RadioRuntimeStateSavedData runtimeData = RadioRuntimeStateSavedData.get(player.server);
        String runtimeKey = handheldRuntimeKey(radioId);
        RadioRuntimeStateSavedData.RadioRuntimeState state = resolveOrCreateScopedState(runtimeData, runtimeKey, radioId);
        ensureSessionIdentity(state, runtimeKey);
        if (!authorizeRuntimeControl(player, radioId, state, runtimeData, false, null)) {
            sendSessionCommandResult(
                    player,
                    radioId,
                    state,
                    false,
                    ClientboundSessionCommandResultMessage.Reason.UNAUTHORIZED,
                    ServerboundRequestRadioStateMessage.Context.HANDHELD
            );
            return;
        }
        long now = System.currentTimeMillis();
        if (isDuplicateCommand(state, player.getUUID(), message.commandId(), now)) {
            sendSessionCommandResult(
                    player,
                    radioId,
                    state,
                    false,
                    ClientboundSessionCommandResultMessage.Reason.DUPLICATE_COMMAND,
                    ServerboundRequestRadioStateMessage.Context.HANDHELD
            );
            return;
        }
        if (isStaleRevision(message.knownRevision(), state.revision)) {
            sendSessionCommandResult(
                    player,
                    radioId,
                    state,
                    false,
                    ClientboundSessionCommandResultMessage.Reason.STALE_REVISION,
                    ServerboundRequestRadioStateMessage.Context.HANDHELD
            );
            return;
        }
        RadioRuntimeStateSavedData.RadioRuntimeState previousState = copyState(state);
        boolean previousContextActive = shouldBroadcastHandheldContext(player, radioId, previousState);
        boolean stateChanged = false;
        String incomingQueueState = safe(message.queueStateJson());
        String mergedQueueState = mergeIncomingQueueState(state.queueStateJson, incomingQueueState);
        if (!safe(state.queueStateJson).equals(mergedQueueState)) {
            state.queueStateJson = mergedQueueState;
            stateChanged = true;
        }
        boolean incomingInventoryBroadcast = message.allowInventoryBroadcast();
        if (state.allowInventoryBroadcast != incomingInventoryBroadcast) {
            state.allowInventoryBroadcast = incomingInventoryBroadcast;
            stateChanged = true;
        }
        long incomingDurationMs = message.trackDurationMs();
        if (incomingDurationMs > 0L) {
            long resolvedDuration = incomingDurationMs == Long.MAX_VALUE ? -1L : incomingDurationMs;
            if (resolvedDuration > 0L && state.trackDurationMs != resolvedDuration) {
                state.trackDurationMs = resolvedDuration;
                stateChanged = true;
            }
            String incomingUrl = safe(message.url());
            if (!incomingUrl.isBlank()) {
                Long previousKnown = state.knownTrackDurationsMs.get(trackSyncKey(incomingUrl));
                if (previousKnown == null || previousKnown != resolvedDuration) {
                    state.knownTrackDurationsMs.put(trackSyncKey(incomingUrl), resolvedDuration);
                    stateChanged = true;
                }
            }
        }
        boolean refreshedTimelineAnchor = false;
        String incomingUrl = safe(message.url());
        if (message.playing() && !incomingUrl.isBlank()) {
            boolean sameRuntimeTrack = sameTrack(state.url, incomingUrl);
            if (!sameRuntimeTrack) {
                state.url = incomingUrl;
                String incomingTitle = safe(message.title());
                String incomingArtist = safe(message.artist());
                String incomingThumbnail = safe(message.thumbnail());
                if (!incomingTitle.isBlank()) {
                    state.title = incomingTitle;
                }
                if (!incomingArtist.isBlank()) {
                    state.artist = incomingArtist;
                }
                if (!incomingThumbnail.isBlank()) {
                    state.thumbnail = incomingThumbnail;
                }
                state.playing = true;
                state.positionMs = Math.max(0L, message.positionMs());
                state.updatedAtMs = now;
                stateChanged = true;
                refreshedTimelineAnchor = true;
            } else {
                state.positionMs = Math.max(0L, message.positionMs());
                state.updatedAtMs = now;
                if (!state.playing) {
                    state.playing = true;
                    stateChanged = true;
                }
                refreshedTimelineAnchor = true;
            }
        }
        if (alignQueueStateToCurrentTrack(state)) {
            stateChanged = true;
        }

        boolean contextActive = shouldBroadcastHandheldContext(player, radioId, state);
        boolean contextChanged = previousContextActive != contextActive;
        boolean meaningfulMutation = stateChanged || contextChanged;

        if (stateChanged) {
            if (!refreshedTimelineAnchor) {
                preservePlaybackTimelineAnchor(runtimeData, state, now);
            }
            markRuntimeMutation(state, runtimeKey, now);
            runtimeData.setDirty();
            syncLoadedBlockEntitiesForRadioId(radioId);
        }

        if (!meaningfulMutation) {
            return;
        }

        broadcastHandheldRuntimeState(player, radioId, runtimeData, state, false, false);
    }

    public static void handleRadioStateRequest(ServerPlayer player, ServerboundRequestRadioStateMessage message) {
        String radioId = safe(message.radioId());
        if (radioId.isBlank()) {
            return;
        }
        RadioRuntimeStateSavedData runtimeData = RadioRuntimeStateSavedData.get(player.server);
        String runtimeKey = message.context() == ServerboundRequestRadioStateMessage.Context.BLOCK
                ? blockRuntimeKey(radioId)
                : handheldRuntimeKey(radioId);
        RadioRuntimeStateSavedData.RadioRuntimeState state = resolveOrCreateScopedState(runtimeData, runtimeKey, radioId);
        ensureSessionIdentity(state, runtimeKey);
        long position = runtimeData.currentPositionMs(state);
        ModNetworking.sendRadioState(player, new ClientboundRadioStateMessage(
                radioId,
                safe(state.sessionId),
                Math.max(0L, state.revision),
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
        String runtimeKey = blockRuntimeKey(radioId);
        RadioRuntimeStateSavedData.RadioRuntimeState state = resolveOrCreateScopedState(
                runtimeData,
                runtimeKey,
                radioId
        );
        ensureSessionIdentity(state, runtimeKey);
        long nowMs = System.currentTimeMillis();
        boolean changed = false;
        String blockOwnerId = safe(radioBlockEntity.getOwnerId());
        String runtimeOwnerId = safe(state.ownerId);
        if (runtimeOwnerId.isBlank()) {
            if (!blockOwnerId.isBlank()) {
                state.ownerId = blockOwnerId;
                runtimeOwnerId = blockOwnerId;
                changed = true;
            }
        } else if (blockOwnerId.isBlank()) {
            radioBlockEntity.setOwnerId(runtimeOwnerId);
        }
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
            preservePlaybackTimelineAnchor(runtimeData, state, nowMs);
            markRuntimeMutation(state, runtimeKey, nowMs);
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

    private static String handheldRuntimeKey(String radioId) {
        return sessionRuntimeKey(radioId);
    }

    private static String blockRuntimeKey(String radioId) {
        return sessionRuntimeKey(radioId);
    }

    private static String sessionRuntimeKey(String radioId) {
        return safe(radioId);
    }

    private static String scopedRuntimeKey(String scope, String radioId) {
        return safe(scope) + ":" + safe(radioId);
    }

    private static RadioRuntimeStateSavedData.RadioRuntimeState resolveScopedState(
            RadioRuntimeStateSavedData runtimeData,
            String scopedKey,
            String legacyRadioId
    ) {
        if (runtimeData == null) {
            return null;
        }
        String radioId = safe(legacyRadioId);
        String primaryKey = safe(scopedKey);
        if (primaryKey.isBlank()) {
            primaryKey = sessionRuntimeKey(radioId);
        }
        if (primaryKey.isBlank()) {
            return null;
        }

        Map<String, RadioRuntimeStateSavedData.RadioRuntimeState> candidates = new LinkedHashMap<>();
        RadioRuntimeStateSavedData.RadioRuntimeState primary = runtimeData.get(primaryKey);
        if (primary != null) {
            candidates.put(primaryKey, primary);
        }

        if (!radioId.isBlank()) {
            String legacyKey = safe(radioId);
            if (!legacyKey.isBlank() && !legacyKey.equals(primaryKey)) {
                RadioRuntimeStateSavedData.RadioRuntimeState legacy = runtimeData.get(legacyKey);
                if (legacy != null) {
                    candidates.put(legacyKey, legacy);
                }
            }
            String blockAlias = scopedRuntimeKey(RUNTIME_SCOPE_BLOCK, radioId);
            if (!blockAlias.equals(primaryKey)) {
                RadioRuntimeStateSavedData.RadioRuntimeState blockState = runtimeData.get(blockAlias);
                if (blockState != null) {
                    candidates.put(blockAlias, blockState);
                }
            }
            String handheldAlias = scopedRuntimeKey(RUNTIME_SCOPE_HANDHELD, radioId);
            if (!handheldAlias.equals(primaryKey)) {
                RadioRuntimeStateSavedData.RadioRuntimeState handheldState = runtimeData.get(handheldAlias);
                if (handheldState != null) {
                    candidates.put(handheldAlias, handheldState);
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        String preferredKey = null;
        RadioRuntimeStateSavedData.RadioRuntimeState preferredState = null;
        for (Map.Entry<String, RadioRuntimeStateSavedData.RadioRuntimeState> candidate : candidates.entrySet()) {
            if (preferredState == null || isStateNewer(candidate.getValue(), preferredState)) {
                preferredState = candidate.getValue();
                preferredKey = candidate.getKey();
            }
        }
        if (preferredState == null) {
            return null;
        }

        boolean migrated = false;
        if (!primaryKey.equals(preferredKey) || primary == null) {
            RadioRuntimeStateSavedData.RadioRuntimeState migratedState = copyState(preferredState);
            if (migratedState != null) {
                ensureSessionIdentity(migratedState, primaryKey);
                runtimeData.states().put(primaryKey, migratedState);
                primary = migratedState;
                migrated = true;
            }
        }
        for (String candidateKey : new HashSet<>(candidates.keySet())) {
            if (candidateKey.equals(primaryKey)) {
                continue;
            }
            if (runtimeData.states().remove(candidateKey) != null) {
                migrated = true;
            }
        }
        if (migrated) {
            runtimeData.setDirty();
        }
        if (primary == null) {
            primary = runtimeData.get(primaryKey);
        }
        ensureSessionIdentity(primary, primaryKey);
        return primary;
    }

    private static RadioRuntimeStateSavedData.RadioRuntimeState resolveOrCreateScopedState(
            RadioRuntimeStateSavedData runtimeData,
            String scopedKey,
            String legacyRadioId
    ) {
        RadioRuntimeStateSavedData.RadioRuntimeState state = resolveScopedState(runtimeData, scopedKey, legacyRadioId);
        if (state != null) {
            return state;
        }
        RadioRuntimeStateSavedData.RadioRuntimeState created = runtimeData.getOrCreate(scopedKey);
        ensureSessionIdentity(created, scopedKey);
        return created;
    }

    private static void ensureSessionIdentity(RadioRuntimeStateSavedData.RadioRuntimeState state, String sessionId) {
        if (state == null) {
            return;
        }
        String resolved = safe(sessionId);
        if (!resolved.isBlank()) {
            state.sessionId = resolved;
        } else if (safe(state.sessionId).isBlank()) {
            state.sessionId = UUID.randomUUID().toString();
        }
        state.revision = Math.max(0L, state.revision);
    }

    private static void markRuntimeMutation(RadioRuntimeStateSavedData.RadioRuntimeState state, String sessionId, long updatedAtMs) {
        if (state == null) {
            return;
        }
        ensureSessionIdentity(state, sessionId);
        state.revision = Math.max(0L, state.revision) + 1L;
        state.updatedAtMs = Math.max(0L, updatedAtMs);
    }

    private static boolean isStaleRevision(long knownRevision, long currentRevision) {
        return knownRevision >= 0L && currentRevision > knownRevision;
    }

    private static boolean isDuplicateCommand(
            RadioRuntimeStateSavedData.RadioRuntimeState state,
            UUID playerUuid,
            long commandId,
            long nowMs
    ) {
        if (state == null || playerUuid == null || commandId < 0L) {
            return false;
        }
        String sessionId = safe(state.sessionId);
        if (sessionId.isBlank()) {
            return false;
        }
        String dedupeKey = sessionId + "|" + playerUuid + "|" + commandId;
        synchronized (RECENT_SESSION_COMMANDS) {
            RECENT_SESSION_COMMANDS.entrySet().removeIf(entry -> nowMs - entry.getValue() > COMMAND_DEDUPE_WINDOW_MS);
            Long existing = RECENT_SESSION_COMMANDS.putIfAbsent(dedupeKey, nowMs);
            return existing != null;
        }
    }

    private static void sendSessionCommandResult(
            ServerPlayer player,
            String radioId,
            RadioRuntimeStateSavedData.RadioRuntimeState state,
            boolean accepted,
            ClientboundSessionCommandResultMessage.Reason reason,
            ServerboundRequestRadioStateMessage.Context context
    ) {
        if (player == null) {
            return;
        }
        String sessionId = state == null ? "" : safe(state.sessionId);
        long revision = state == null ? 0L : Math.max(0L, state.revision);
        ModNetworking.sendSessionCommandResult(
                player,
                new ClientboundSessionCommandResultMessage(
                        safe(radioId),
                        sessionId,
                        revision,
                        accepted,
                        reason == null ? ClientboundSessionCommandResultMessage.Reason.NONE : reason,
                        context == null ? ServerboundRequestRadioStateMessage.Context.HANDHELD : context
                )
        );
    }

    private static boolean authorizeRuntimeControl(
            ServerPlayer player,
            String radioId,
            RadioRuntimeStateSavedData.RadioRuntimeState state,
            RadioRuntimeStateSavedData runtimeData,
            boolean blockScoped,
            RadioBlockEntity radioBlockEntity
    ) {
        if (player == null || state == null || radioId == null || radioId.isBlank()) {
            return false;
        }
        return blockScoped
                ? authorizeBlockRuntimeControl(player, state, runtimeData, radioBlockEntity)
                : authorizeHandheldRuntimeControl(player, radioId, state, runtimeData);
    }

    private static boolean authorizeBlockRuntimeControl(
            ServerPlayer player,
            RadioRuntimeStateSavedData.RadioRuntimeState state,
            RadioRuntimeStateSavedData runtimeData,
            RadioBlockEntity radioBlockEntity
    ) {
        if (player == null || state == null) {
            return false;
        }
        String playerId = safe(player.getStringUUID());
        if (playerId.isBlank()) {
            return false;
        }

        String runtimeOwnerId = safe(state.ownerId);
        if (radioBlockEntity != null) {
            String blockOwnerId = safe(radioBlockEntity.getOwnerId());
            if (runtimeOwnerId.isBlank()) {
                String resolvedOwner = blockOwnerId.isBlank() ? playerId : blockOwnerId;
                if (!resolvedOwner.equals(runtimeOwnerId)) {
                    state.ownerId = resolvedOwner;
                    if (runtimeData != null) {
                        runtimeData.setDirty();
                    }
                    runtimeOwnerId = resolvedOwner;
                }
                if (!resolvedOwner.equals(blockOwnerId)) {
                    radioBlockEntity.setOwnerId(resolvedOwner);
                }
            } else if (!blockOwnerId.isBlank() && !runtimeOwnerId.equals(blockOwnerId)) {
                // Keep runtime owner metadata aligned to the actual block owner tag,
                // but do not gate block controls by ownership.
                state.ownerId = blockOwnerId;
                if (runtimeData != null) {
                    runtimeData.setDirty();
                }
                runtimeOwnerId = blockOwnerId;
            } else if (blockOwnerId.isBlank()) {
                radioBlockEntity.setOwnerId(runtimeOwnerId);
            }
        }

        if (runtimeOwnerId.isBlank()) {
            state.ownerId = playerId;
            if (runtimeData != null) {
                runtimeData.setDirty();
            }
        }
        // Placed block radios are collaborative controls: any nearby player may operate transport/queue/options.
        return true;
    }

    private static boolean authorizeHandheldRuntimeControl(
            ServerPlayer player,
            String radioId,
            RadioRuntimeStateSavedData.RadioRuntimeState state,
            RadioRuntimeStateSavedData runtimeData
    ) {
        if (player == null || radioId == null || radioId.isBlank() || state == null) {
            return false;
        }
        ItemStack matchingStack = findPlayerRadioStackById(player, radioId);
        if (matchingStack.isEmpty()) {
            return false;
        }
        String playerId = safe(player.getStringUUID());
        if (playerId.isBlank()) {
            return false;
        }

        String stackOwnerId = safe(net.jacobwasbeast.mediaradio.item.RadioItem.getOwnerId(matchingStack));
        if (!stackOwnerId.isBlank() && !stackOwnerId.equals(playerId)) {
            return false;
        }

        String runtimeOwnerId = safe(state.ownerId);
        String resolvedOwnerId = !stackOwnerId.isBlank() ? stackOwnerId : runtimeOwnerId;
        if (resolvedOwnerId.isBlank()) {
            resolvedOwnerId = playerId;
        }
        if (!resolvedOwnerId.equals(playerId)) {
            return false;
        }
        if (!resolvedOwnerId.equals(runtimeOwnerId)) {
            state.ownerId = resolvedOwnerId;
            if (runtimeData != null) {
                runtimeData.setDirty();
            }
        }
        return true;
    }

    private static ItemStack findPlayerRadioStackById(ServerPlayer player, String radioId) {
        if (player == null || radioId == null || radioId.isBlank()) {
            return ItemStack.EMPTY;
        }
        ItemStack main = player.getMainHandItem();
        if (isMatchingRadioStack(main, radioId)) {
            return main;
        }
        ItemStack off = player.getOffhandItem();
        if (isMatchingRadioStack(off, radioId)) {
            return off;
        }
        for (ItemStack stack : player.getInventory().items) {
            if (isMatchingRadioStack(stack, radioId)) {
                return stack;
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (isMatchingRadioStack(stack, radioId)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean isMatchingRadioStack(ItemStack stack, String radioId) {
        if (stack == null || stack.isEmpty() || radioId == null || radioId.isBlank()) {
            return false;
        }
        if (!stack.is(net.jacobwasbeast.mediaradio.registry.ModItems.RADIO_ITEM)) {
            return false;
        }
        return radioId.equals(net.jacobwasbeast.mediaradio.item.RadioItem.getRadioId(stack));
    }

    private static void preservePlaybackTimelineAnchor(
            RadioRuntimeStateSavedData runtimeData,
            RadioRuntimeStateSavedData.RadioRuntimeState state,
            long nowMs
    ) {
        if (runtimeData == null || state == null || !state.playing) {
            return;
        }
        state.positionMs = runtimeData.currentPositionMs(state);
        state.updatedAtMs = Math.max(0L, nowMs);
    }

    private static String mergeIncomingQueueState(String existingQueueStateJson, String incomingQueueStateJson) {
        String incomingJson = safe(incomingQueueStateJson);
        if (incomingJson.isBlank()) {
            return "";
        }

        QueueStatePayload incomingPayload = parseQueueState(incomingJson);
        if (incomingPayload == null) {
            return incomingJson;
        }
        alignQueuePointer(incomingPayload);
        if (!incomingPayload.partial) {
            return GSON.toJson(incomingPayload);
        }

        QueueStatePayload existingPayload = parseQueueState(existingQueueStateJson);
        if (existingPayload == null) {
            return GSON.toJson(incomingPayload);
        }
        if (countQueueIdOverlap(existingPayload, incomingPayload) == 0) {
            return GSON.toJson(incomingPayload);
        }

        QueueStatePayload merged = copyQueuePayload(existingPayload);
        merged.loopMode = incomingPayload.loopMode == null ? merged.loopMode : incomingPayload.loopMode;
        mergeQueueEntries(merged, incomingPayload);
        applyIncomingQueuePointer(merged, incomingPayload);
        alignQueuePointer(merged);
        merged.partial = merged.partial && incomingPayload.partial;
        return GSON.toJson(merged);
    }

    private static boolean alignQueueStateToCurrentTrack(RadioRuntimeStateSavedData.RadioRuntimeState state) {
        if (state == null) {
            return false;
        }
        String currentUrl = safe(state.url);
        if (currentUrl.isBlank()) {
            return false;
        }

        QueueStatePayload payload = parseQueueState(state.queueStateJson);
        boolean changed = false;
        if (payload == null) {
            payload = new QueueStatePayload();
            changed = true;
        }
        if (payload.loopMode == null) {
            payload.loopMode = QueueLoopMode.ALL;
            changed = true;
        }

        int preferredIndex = resolveCurrentQueueIndex(payload, currentUrl);
        int matchedIndex = -1;
        if (payload.entries != null) {
            for (int i = 0; i < payload.entries.size(); i++) {
                QueueMediaPayload entry = payload.entries.get(i);
                if (entry == null || !sameTrack(currentUrl, entry.url)) {
                    continue;
                }
                if (matchedIndex < 0) {
                    matchedIndex = i;
                    continue;
                }
                int baseline = preferredIndex < 0 ? 0 : preferredIndex;
                int currentDistance = Math.abs(matchedIndex - baseline);
                int candidateDistance = Math.abs(i - baseline);
                if (candidateDistance < currentDistance) {
                    matchedIndex = i;
                }
            }
        }

        if (matchedIndex < 0) {
            QueueMediaPayload currentEntry = new QueueMediaPayload();
            currentEntry.queueItemId = newQueueItemId();
            currentEntry.url = currentUrl;
            currentEntry.title = safe(state.title);
            currentEntry.artist = safe(state.artist);
            currentEntry.thumbnail = safe(state.thumbnail);
            int insertIndex = preferredIndex < 0 ? payload.entries.size() : Math.max(0, Math.min(preferredIndex, payload.entries.size()));
            payload.entries.add(insertIndex, currentEntry);
            matchedIndex = insertIndex;
            changed = true;
        } else {
            QueueMediaPayload entry = payload.entries.get(matchedIndex);
            String stateTitle = safe(state.title);
            String stateArtist = safe(state.artist);
            String stateThumbnail = safe(state.thumbnail);
            if (!stateTitle.isBlank() && !stateTitle.equals(safe(entry.title))) {
                entry.title = stateTitle;
                changed = true;
            }
            if (!stateArtist.isBlank() && !stateArtist.equals(safe(entry.artist))) {
                entry.artist = stateArtist;
                changed = true;
            }
            if (!stateThumbnail.isBlank() && !stateThumbnail.equals(safe(entry.thumbnail))) {
                entry.thumbnail = stateThumbnail;
                changed = true;
            }
            if (safe(entry.queueItemId).isBlank()) {
                entry.queueItemId = newQueueItemId();
                changed = true;
            }
        }

        QueueMediaPayload matchedEntry = payload.entries.get(matchedIndex);
        String matchedId = safe(matchedEntry.queueItemId);
        if (!matchedId.equals(safe(payload.currentQueueItemId))) {
            payload.currentQueueItemId = matchedId;
            changed = true;
        }
        if (payload.queueIndex != matchedIndex) {
            payload.queueIndex = matchedIndex;
            changed = true;
        }
        alignQueuePointer(payload);

        String alignedJson = GSON.toJson(payload);
        if (!alignedJson.equals(safe(state.queueStateJson))) {
            state.queueStateJson = alignedJson;
            changed = true;
        }
        return changed;
    }

    private static QueueStatePayload copyQueuePayload(QueueStatePayload source) {
        QueueStatePayload copy = new QueueStatePayload();
        if (source == null) {
            return copy;
        }
        copy.currentQueueItemId = safe(source.currentQueueItemId);
        copy.queueIndex = source.queueIndex;
        copy.loopMode = source.loopMode == null ? QueueLoopMode.ALL : source.loopMode;
        copy.partial = source.partial;
        if (source.entries == null) {
            return copy;
        }
        for (QueueMediaPayload entry : source.entries) {
            if (entry == null || safe(entry.url).isBlank()) {
                continue;
            }
            copy.entries.add(copyQueueEntry(entry));
        }
        return copy;
    }

    private static int countQueueIdOverlap(QueueStatePayload left, QueueStatePayload right) {
        if (left == null || right == null || left.entries == null || right.entries == null
                || left.entries.isEmpty() || right.entries.isEmpty()) {
            return 0;
        }
        Set<String> leftIds = new HashSet<>();
        for (QueueMediaPayload entry : left.entries) {
            String queueItemId = safe(entry == null ? "" : entry.queueItemId);
            if (!queueItemId.isBlank()) {
                leftIds.add(queueItemId);
            }
        }
        if (leftIds.isEmpty()) {
            return 0;
        }
        int overlap = 0;
        for (QueueMediaPayload entry : right.entries) {
            String queueItemId = safe(entry == null ? "" : entry.queueItemId);
            if (!queueItemId.isBlank() && leftIds.contains(queueItemId)) {
                overlap++;
            }
        }
        return overlap;
    }

    private static void mergeQueueEntries(QueueStatePayload target, QueueStatePayload incoming) {
        if (target == null || target.entries == null || incoming == null || incoming.entries == null) {
            return;
        }
        Map<String, QueueMediaPayload> byQueueItemId = new LinkedHashMap<>();
        for (QueueMediaPayload entry : target.entries) {
            String queueItemId = safe(entry == null ? "" : entry.queueItemId);
            if (!queueItemId.isBlank()) {
                byQueueItemId.put(queueItemId, entry);
            }
        }
        for (QueueMediaPayload incomingEntry : incoming.entries) {
            if (incomingEntry == null || safe(incomingEntry.url).isBlank()) {
                continue;
            }
            String incomingQueueItemId = safe(incomingEntry.queueItemId);
            if (!incomingQueueItemId.isBlank()) {
                QueueMediaPayload existing = byQueueItemId.get(incomingQueueItemId);
                if (existing != null) {
                    mergeQueueEntry(existing, incomingEntry);
                    continue;
                }
                QueueMediaPayload copy = copyQueueEntry(incomingEntry);
                target.entries.add(copy);
                byQueueItemId.put(copy.queueItemId, copy);
                continue;
            }

            QueueMediaPayload existingByTrack = null;
            for (QueueMediaPayload candidate : target.entries) {
                if (candidate != null && sameTrack(candidate.url, incomingEntry.url)) {
                    existingByTrack = candidate;
                    break;
                }
            }
            if (existingByTrack != null) {
                mergeQueueEntry(existingByTrack, incomingEntry);
            } else {
                target.entries.add(copyQueueEntry(incomingEntry));
            }
        }
    }

    private static void applyIncomingQueuePointer(QueueStatePayload target, QueueStatePayload incoming) {
        if (target == null || incoming == null) {
            return;
        }
        String incomingPointerId = safe(incoming.currentQueueItemId);
        if (!incomingPointerId.isBlank()) {
            for (int i = 0; i < target.entries.size(); i++) {
                QueueMediaPayload entry = target.entries.get(i);
                if (entry != null && incomingPointerId.equals(safe(entry.queueItemId))) {
                    target.currentQueueItemId = incomingPointerId;
                    target.queueIndex = i;
                    return;
                }
            }
        }

        String pointerUrl = resolveQueuePointerUrl(incoming);
        if (!pointerUrl.isBlank()) {
            for (int i = 0; i < target.entries.size(); i++) {
                QueueMediaPayload entry = target.entries.get(i);
                if (entry != null && sameTrack(pointerUrl, entry.url)) {
                    target.currentQueueItemId = safe(entry.queueItemId);
                    target.queueIndex = i;
                    return;
                }
            }
        }
    }

    private static String resolveQueuePointerUrl(QueueStatePayload payload) {
        if (payload == null || payload.entries == null || payload.entries.isEmpty()) {
            return "";
        }
        String pointerId = safe(payload.currentQueueItemId);
        if (!pointerId.isBlank()) {
            for (QueueMediaPayload entry : payload.entries) {
                if (entry != null && pointerId.equals(safe(entry.queueItemId))) {
                    return safe(entry.url);
                }
            }
        }
        if (payload.queueIndex >= 0 && payload.queueIndex < payload.entries.size()) {
            QueueMediaPayload entry = payload.entries.get(payload.queueIndex);
            return entry == null ? "" : safe(entry.url);
        }
        return "";
    }

    private static QueueMediaPayload copyQueueEntry(QueueMediaPayload source) {
        QueueMediaPayload copy = new QueueMediaPayload();
        copy.queueItemId = safe(source == null ? "" : source.queueItemId);
        if (copy.queueItemId.isBlank()) {
            copy.queueItemId = newQueueItemId();
        }
        copy.url = safe(source == null ? "" : source.url);
        copy.title = safe(source == null ? "" : source.title);
        copy.artist = safe(source == null ? "" : source.artist);
        copy.thumbnail = safe(source == null ? "" : source.thumbnail);
        return copy;
    }

    private static void mergeQueueEntry(QueueMediaPayload target, QueueMediaPayload incoming) {
        if (target == null || incoming == null) {
            return;
        }
        if (safe(target.queueItemId).isBlank()) {
            target.queueItemId = newQueueItemId();
        }
        String incomingUrl = safe(incoming.url);
        if (!incomingUrl.isBlank()) {
            target.url = incomingUrl;
        }
        String incomingTitle = safe(incoming.title);
        if (!incomingTitle.isBlank()) {
            target.title = incomingTitle;
        }
        String incomingArtist = safe(incoming.artist);
        if (!incomingArtist.isBlank()) {
            target.artist = incomingArtist;
        }
        String incomingThumbnail = safe(incoming.thumbnail);
        if (!incomingThumbnail.isBlank()) {
            target.thumbnail = incomingThumbnail;
        }
    }

    private static String newQueueItemId() {
        return UUID.randomUUID().toString();
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

        // Keep existing library as baseline so playlists cannot lose entries due to
        // partial/stale client snapshots.
        for (Map.Entry<String, SharedMediaSnapshot.MediaEntry> entry : current.library.entrySet()) {
            String mediaId = safe(entry.getKey());
            SharedMediaSnapshot.MediaEntry mediaEntry = cloneMedia(entry.getValue(), mediaId);
            if (mediaEntry != null) {
                String resolvedId = mediaId.isBlank() ? safe(mediaEntry.id) : mediaId;
                if (!resolvedId.isBlank()) {
                    merged.library.put(resolvedId, mediaEntry);
                }
            }
        }
        // Merge incoming media updates without wiping existing metadata on blank fields.
        for (Map.Entry<String, SharedMediaSnapshot.MediaEntry> entry : incoming.library.entrySet()) {
            String mediaId = safe(entry.getKey());
            if (mediaId.isBlank()) {
                continue;
            }
            SharedMediaSnapshot.MediaEntry incomingEntry = cloneMedia(entry.getValue(), mediaId);
            if (incomingEntry == null) {
                continue;
            }
            String resolvedId = mediaId.isBlank() ? safe(incomingEntry.id) : mediaId;
            if (resolvedId.isBlank()) {
                continue;
            }
            SharedMediaSnapshot.MediaEntry currentEntry = merged.library.get(resolvedId);
            if (currentEntry == null) {
                merged.library.put(resolvedId, incomingEntry);
                continue;
            }
            merged.library.put(resolvedId, mergeMedia(currentEntry, incomingEntry, resolvedId));
        }

        // Start from current playlists so unauthorized deletions/edits are ignored.
        for (Map.Entry<String, SharedMediaSnapshot.PlaylistEntry> entry : current.playlists.entrySet()) {
            merged.playlists.put(entry.getKey(), clonePlaylist(entry.getValue()));
        }

        // Apply explicit owner-authorized deletions (tombstones).
        for (String deletedPlaylistId : incoming.deletedPlaylistIds) {
            String playlistId = safe(deletedPlaylistId);
            if (playlistId.isBlank()) {
                continue;
            }
            SharedMediaSnapshot.PlaylistEntry existing = current.playlists.get(playlistId);
            if (existing != null && existing.canEdit(playerId)) {
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
                playlist.mediaIds.removeIf(mediaId -> {
                    String safeMediaId = safe(mediaId);
                    if (safeMediaId.isBlank()) {
                        return true;
                    }
                    if (merged.library.containsKey(safeMediaId)) {
                        return false;
                    }
                    SharedMediaSnapshot.MediaEntry fallback = cloneMedia(current.library.get(safeMediaId), safeMediaId);
                    if (fallback == null) {
                        fallback = cloneMedia(incoming.library.get(safeMediaId), safeMediaId);
                    }
                    if (fallback != null) {
                        merged.library.put(safeMediaId, fallback);
                        return false;
                    }
                    return true;
                });
            }
        });

        merged.deletedPlaylistIds.clear();
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

    private static SharedMediaSnapshot.MediaEntry cloneMedia(SharedMediaSnapshot.MediaEntry source, String mediaId) {
        if (source == null) {
            return null;
        }
        SharedMediaSnapshot.MediaEntry copy = new SharedMediaSnapshot.MediaEntry();
        copy.id = safe(mediaId).isBlank() ? safe(source.id) : safe(mediaId);
        copy.url = safe(source.url);
        copy.title = safe(source.title);
        copy.artist = safe(source.artist);
        copy.thumbnail = safe(source.thumbnail);
        copy.tags = source.tags == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(source.tags);
        copy.hiddenFromLibrary = source.hiddenFromLibrary;
        copy.sanitize();
        if (copy.url.isBlank()) {
            return null;
        }
        if (copy.id.isBlank()) {
            copy.id = SharedMediaSnapshot.idForUrl(copy.url);
        }
        return copy;
    }

    private static SharedMediaSnapshot.MediaEntry mergeMedia(
            SharedMediaSnapshot.MediaEntry current,
            SharedMediaSnapshot.MediaEntry incoming,
            String mediaId
    ) {
        SharedMediaSnapshot.MediaEntry merged = cloneMedia(current, mediaId);
        if (merged == null) {
            merged = cloneMedia(incoming, mediaId);
            return merged == null ? null : merged;
        }
        if (incoming != null) {
            if (!safe(incoming.url).isBlank()) {
                merged.url = safe(incoming.url);
            }
            if (!safe(incoming.title).isBlank()) {
                merged.title = safe(incoming.title);
            }
            if (!safe(incoming.artist).isBlank()) {
                merged.artist = safe(incoming.artist);
            }
            if (!safe(incoming.thumbnail).isBlank()) {
                merged.thumbnail = safe(incoming.thumbnail);
            }
            if (incoming.tags != null && !incoming.tags.isEmpty()) {
                merged.tags = new java.util.ArrayList<>(incoming.tags);
            }
            merged.hiddenFromLibrary = merged.hiddenFromLibrary && incoming.hiddenFromLibrary;
        }
        merged.sanitize();
        return merged;
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
        copy.sessionId = safe(source.sessionId);
        copy.revision = Math.max(0L, source.revision);
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
        copy.ownerId = safe(source.ownerId);
        copy.updatedAtMs = Math.max(0L, source.updatedAtMs);
        return copy;
    }

    private static boolean isStateNewer(
            RadioRuntimeStateSavedData.RadioRuntimeState candidate,
            RadioRuntimeStateSavedData.RadioRuntimeState baseline
    ) {
        if (candidate == null) {
            return false;
        }
        if (baseline == null) {
            return true;
        }
        long candidateRevision = Math.max(0L, candidate.revision);
        long baselineRevision = Math.max(0L, baseline.revision);
        if (candidateRevision != baselineRevision) {
            return candidateRevision > baselineRevision;
        }
        long candidateUpdatedAt = Math.max(0L, candidate.updatedAtMs);
        long baselineUpdatedAt = Math.max(0L, baseline.updatedAtMs);
        if (candidateUpdatedAt != baselineUpdatedAt) {
            return candidateUpdatedAt > baselineUpdatedAt;
        }
        if (candidate.playing != baseline.playing) {
            return candidate.playing;
        }
        if (!safe(candidate.url).isBlank() && safe(baseline.url).isBlank()) {
            return true;
        }
        return false;
    }

    private static void syncLoadedBlockEntitiesForRadioId(String radioId) {
        String resolvedRadioId = safe(radioId);
        if (resolvedRadioId.isBlank()) {
            return;
        }
        for (RadioBlockEntity blockEntity : RadioBlockEntity.loadedForRadioId(resolvedRadioId)) {
            if (blockEntity == null || blockEntity.isRemoved()) {
                continue;
            }
            applyRuntimeStateToBlockEntity(blockEntity);
        }
    }

    private static void broadcastHandheldRuntimeState(
            ServerPlayer owner,
            String radioId,
            RadioRuntimeStateSavedData runtimeData,
            RadioRuntimeStateSavedData.RadioRuntimeState state,
            boolean forcePositionSync,
            boolean seekEvent
    ) {
        if (owner == null || owner.server == null || runtimeData == null || state == null || radioId == null || radioId.isBlank()) {
            return;
        }

        boolean contextActive = shouldBroadcastHandheldContext(owner, radioId, state);
        long position = runtimeData.currentPositionMs(state);
        String queueForPacket = queueStateForPacket(state.queueStateJson);
        long sentAtMs = System.currentTimeMillis();
        ModNetworking.sendRadioState(owner, new ClientboundRadioStateMessage(
                radioId,
                safe(state.sessionId),
                Math.max(0L, state.revision),
                safe(state.url),
                safe(state.title),
                safe(state.artist),
                safe(state.thumbnail),
                queueForPacket,
                Mth.clamp(state.volume, 0f, 2f),
                position,
                sentAtMs,
                forcePositionSync,
                seekEvent,
                state.playing
        ));
        for (ServerPlayer other : owner.server.getPlayerList().getPlayers()) {
            if (!shouldBroadcastToHandheldListener(owner, other)) {
                continue;
            }
            boolean inventoryPlayback = contextActive && isInventoryPlaybackContext(owner, radioId);
            ModNetworking.sendPlayerRadioContext(other, radioId, owner.getId(), contextActive, inventoryPlayback);
            ModNetworking.sendRadioState(other, new ClientboundRadioStateMessage(
                    radioId,
                    safe(state.sessionId),
                    Math.max(0L, state.revision),
                    safe(state.url),
                    safe(state.title),
                    safe(state.artist),
                    safe(state.thumbnail),
                    queueForPacket,
                    Mth.clamp(state.volume, 0f, 2f),
                    position,
                    sentAtMs,
                    forcePositionSync,
                    seekEvent,
                    state.playing
            ));
        }
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
                RadioRuntimeStateSavedData.RadioRuntimeState state = resolveScopedState(
                        runtimeData,
                        handheldRuntimeKey(radioId),
                        radioId
                );
                if (!shouldBroadcastHandheldContext(owner, radioId, state)) {
                    continue;
                }
                boolean inventoryPlayback = isInventoryPlaybackContext(owner, radioId);
                ModNetworking.sendPlayerRadioContext(target, radioId, owner.getId(), true, inventoryPlayback);
                ModNetworking.sendRadioState(target, new ClientboundRadioStateMessage(
                        radioId,
                        safe(state.sessionId),
                        Math.max(0L, state.revision),
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
                RadioRuntimeStateSavedData.RadioRuntimeState state = resolveScopedState(
                        runtimeData,
                        handheldRuntimeKey(radioId),
                        radioId
                );
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
                                safe(state.sessionId),
                                Math.max(0L, state.revision),
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
        Set<String> candidateRadioIds = new HashSet<>();
        for (Map.Entry<String, RadioRuntimeStateSavedData.RadioRuntimeState> entry : runtimeData.states().entrySet()) {
            String radioId = runtimeRadioIdFromStateKey(entry.getKey());
            if (!radioId.isBlank()) {
                candidateRadioIds.add(radioId);
            }
        }
        for (String radioId : candidateRadioIds) {
            String runtimeKey = sessionRuntimeKey(radioId);
            RadioRuntimeStateSavedData.RadioRuntimeState state = resolveOrCreateScopedState(runtimeData, runtimeKey, radioId);
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
                    preservePlaybackTimelineAnchor(runtimeData, state, nowMs);
                    markRuntimeMutation(state, runtimeKey, nowMs);
                    changedAny = true;
                    syncLoadedBlockEntitiesForRadioId(radioId);
                }
            }
            if (state.trackDurationMs <= 0L) {
                continue;
            }
            if (advanceUnownedPlaybackState(runtimeData, state, nowMs)) {
                preservePlaybackTimelineAnchor(runtimeData, state, nowMs);
                markRuntimeMutation(state, runtimeKey, nowMs);
                changedAny = true;
                syncLoadedBlockEntitiesForRadioId(radioId);
            }
        }

        if (changedAny) {
            runtimeData.setDirty();
        }
    }

    private static String runtimeRadioIdFromStateKey(String key) {
        String safeKey = safe(key);
        if (safeKey.isBlank()) {
            return "";
        }
        String blockPrefix = RUNTIME_SCOPE_BLOCK + ":";
        if (safeKey.startsWith(blockPrefix)) {
            return safe(safeKey.substring(blockPrefix.length()));
        }
        String handheldPrefix = RUNTIME_SCOPE_HANDHELD + ":";
        if (safeKey.startsWith(handheldPrefix)) {
            return safe(safeKey.substring(handheldPrefix.length()));
        }
        return safeKey;
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
        if (payload == null || payload.entries == null || payload.entries.isEmpty() || payload.partial) {
            // Contraption/runtime fallback: keep continuous playback on the current track
            // when queue metadata is absent instead of force-stopping unexpectedly.
            long loopedPosition = projectedPositionMs % currentDurationMs;
            if (loopedPosition == Math.max(0L, state.positionMs)) {
                return false;
            }
            state.positionMs = loopedPosition;
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
            for (QueueMediaPayload entry : payload.entries) {
                if (entry == null) {
                    continue;
                }
                entry.url = safe(entry.url);
                entry.title = safe(entry.title);
                entry.artist = safe(entry.artist);
                entry.thumbnail = safe(entry.thumbnail);
                entry.queueItemId = safe(entry.queueItemId);
                if (entry.queueItemId.isBlank()) {
                    entry.queueItemId = newQueueItemId();
                }
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
        String url = safe(currentUrl);
        if (!url.isBlank()) {
            if (payload.currentQueueItemId != null && !payload.currentQueueItemId.isBlank()) {
                for (int i = 0; i < payload.entries.size(); i++) {
                    QueueMediaPayload entry = payload.entries.get(i);
                    if (entry != null
                            && payload.currentQueueItemId.equals(safe(entry.queueItemId))
                            && sameTrack(url, entry.url)) {
                        return i;
                    }
                }
            }
            for (int i = 0; i < payload.entries.size(); i++) {
                QueueMediaPayload entry = payload.entries.get(i);
                if (entry != null && sameTrack(url, entry.url)) {
                    return i;
                }
            }
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
        QueueStatePayload payload = parseQueueState(safe);
        if (payload == null) {
            return "";
        }
        QueueStatePayload compact = compactQueuePayload(payload);
        String compactJson = GSON.toJson(compact);
        if (compactJson.length() <= MAX_QUEUE_STATE_RADIO_PACKET) {
            return compactJson;
        }
        QueueStatePayload trimmed = trimQueuePayloadForPacket(compact, MAX_QUEUE_STATE_RADIO_PACKET);
        String trimmedJson = GSON.toJson(trimmed);
        return trimmedJson.length() <= MAX_QUEUE_STATE_RADIO_PACKET ? trimmedJson : "";
    }

    private static QueueStatePayload compactQueuePayload(QueueStatePayload payload) {
        QueueStatePayload compact = new QueueStatePayload();
        if (payload == null) {
            return compact;
        }
        compact.currentQueueItemId = safe(payload.currentQueueItemId);
        compact.queueIndex = payload.queueIndex;
        compact.loopMode = payload.loopMode == null ? QueueLoopMode.ALL : payload.loopMode;
        compact.partial = payload.partial;
        if (payload.entries == null) {
            return compact;
        }
        for (QueueMediaPayload entry : payload.entries) {
            if (entry == null || safe(entry.url).isBlank()) {
                continue;
            }
            QueueMediaPayload copy = new QueueMediaPayload();
            copy.queueItemId = safe(entry.queueItemId);
            copy.url = safe(entry.url);
            copy.title = truncateForPacket(safe(entry.title), 256);
            copy.artist = truncateForPacket(safe(entry.artist), 256);
            copy.thumbnail = truncateForPacket(safe(entry.thumbnail), 1024);
            compact.entries.add(copy);
        }
        return compact;
    }

    private static QueueStatePayload trimQueuePayloadForPacket(QueueStatePayload payload, int maxLength) {
        QueueStatePayload trimmed = compactQueuePayload(payload);
        if (trimmed.entries.isEmpty()) {
            return trimmed;
        }
        String json = GSON.toJson(trimmed);
        if (json.length() <= maxLength) {
            return trimmed;
        }
        trimmed.partial = true;
        while (json.length() > maxLength && trimmed.entries.size() > 1) {
            trimmed.entries.remove(trimmed.entries.size() - 1);
            alignQueuePointer(trimmed);
            json = GSON.toJson(trimmed);
        }
        if (json.length() > maxLength) {
            for (QueueMediaPayload entry : trimmed.entries) {
                if (entry == null) {
                    continue;
                }
                entry.title = "";
                entry.artist = "";
                entry.thumbnail = "";
            }
            json = GSON.toJson(trimmed);
        }
        while (json.length() > maxLength && trimmed.entries.size() > 1) {
            trimmed.entries.remove(trimmed.entries.size() - 1);
            alignQueuePointer(trimmed);
            json = GSON.toJson(trimmed);
        }
        alignQueuePointer(trimmed);
        return trimmed;
    }

    private static void alignQueuePointer(QueueStatePayload payload) {
        if (payload == null || payload.entries == null || payload.entries.isEmpty()) {
            if (payload != null) {
                payload.currentQueueItemId = "";
                payload.queueIndex = -1;
            }
            return;
        }
        String currentId = safe(payload.currentQueueItemId);
        int currentIndex = -1;
        if (!currentId.isBlank()) {
            for (int i = 0; i < payload.entries.size(); i++) {
                QueueMediaPayload entry = payload.entries.get(i);
                if (entry != null && currentId.equals(safe(entry.queueItemId))) {
                    currentIndex = i;
                    break;
                }
            }
        }
        if (currentIndex < 0) {
            currentIndex = Math.max(0, Math.min(payload.queueIndex, payload.entries.size() - 1));
            QueueMediaPayload entry = payload.entries.get(currentIndex);
            payload.currentQueueItemId = entry == null ? "" : safe(entry.queueItemId);
        }
        payload.queueIndex = currentIndex;
    }

    private static String truncateForPacket(String value, int maxLength) {
        String safeValue = safe(value);
        if (maxLength <= 0 || safeValue.length() <= maxLength) {
            return safeValue;
        }
        return safeValue.substring(0, maxLength);
    }

    private static class QueueStatePayload {
        private java.util.List<QueueMediaPayload> entries = new java.util.ArrayList<>();
        private String currentQueueItemId = "";
        private int queueIndex = -1;
        private QueueLoopMode loopMode = QueueLoopMode.ALL;
        private boolean partial = false;
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
