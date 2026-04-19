package net.jacobwasbeast.mediaradio.client.audio;

import net.jacobwasbeast.mediaradio.block.entity.RadioBlockEntity;
import net.jacobwasbeast.mediaradio.client.data.ClientMediaRepository;
import net.jacobwasbeast.mediaradio.client.screen.RadioScreen;
import net.jacobwasbeast.mediaradio.client.settings.ClientAudioSettings;
import net.jacobwasbeast.mediaradio.item.RadioItem;
import net.jacobwasbeast.mediaradio.network.ModNetworking;
import net.jacobwasbeast.mediaradio.network.message.ServerboundRadioControlMessage;
import net.jacobwasbeast.mediaradio.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClientAudioEngine {

    private static final ClientAudioEngine INSTANCE = new ClientAudioEngine();
    private static final double EXTERNAL_POSITION_SMOOTHING_HZ = 12.0D;
    private static final double EXTERNAL_POSITION_TELEPORT_SNAP_DISTANCE_SQR = 64.0D;
    private static final double EXTERNAL_POSITION_MAX_DT_SECONDS = 0.1D;
    private static final float HANDHELD_EXTERNAL_MAX_DISTANCE = 24f;
    private static final double HANDHELD_EXTERNAL_MAX_DISTANCE_SQR = HANDHELD_EXTERNAL_MAX_DISTANCE * HANDHELD_EXTERNAL_MAX_DISTANCE;
    private static final float CONTRAPTION_EXTERNAL_MAX_DISTANCE = 30f;
    private static final double CONTRAPTION_EXTERNAL_MAX_DISTANCE_SQR = CONTRAPTION_EXTERNAL_MAX_DISTANCE * CONTRAPTION_EXTERNAL_MAX_DISTANCE;
    private static final long EXTERNAL_CONTEXT_TTL_CONTRAPTION_TICKS = 200L;
    private static final long EXTERNAL_CONTEXT_TTL_HANDHELD_TICKS = 200L;
    private static final long EXTERNAL_REACQUIRE_RESYNC_MIN_MISSING_TICKS = 20L;
    private static final long REMOTE_PLAYING_DRIFT_CORRECTION_MS = 2_500L;
    private static final long REMOTE_PAUSED_DRIFT_CORRECTION_MS = 500L;
    private static final long REMOTE_FORCE_SYNC_MIN_DRIFT_MS = 250L;
    private static final long REMOTE_SEEK_EVENT_MIN_DRIFT_MS = 1_250L;
    private static final long REMOTE_SEEK_COOLDOWN_MS = 10_000L;
    private static final long REMOTE_DRIFT_CORRECTION_COOLDOWN_MS = 2_500L;
    private static final long HANDHELD_INITIAL_SYNC_RETRY_MS = 1_000L;
    private static final long BLOCK_RUNTIME_SYNC_INTERVAL_MS = 2_000L;
    private static final long CHANNEL_STALL_RECOVERY_THRESHOLD_MS = 12_000L;
    private static final long CHANNEL_SOFT_RECOVERY_COOLDOWN_MS = 12_000L;
    private static final long CHANNEL_HARD_RECOVERY_COOLDOWN_MS = 35_000L;
    private static final float BLOCK_CHANNEL_MAX_DISTANCE = 30f;
    private static final double BLOCK_CHANNEL_ACTIVE_DISTANCE_SQR = BLOCK_CHANNEL_MAX_DISTANCE * BLOCK_CHANNEL_MAX_DISTANCE;
    private static final int MAX_ACTIVE_BLOCK_CHANNELS = 8;
    private static final int MAX_ACTIVE_EXTERNAL_CHANNELS = 8;
    private static final float MIN_ACTIVE_CHANNEL_GAIN = 0.0001f;

    private final Map<BlockPos, RadioAudioChannel> blockChannels = new ConcurrentHashMap<>();
    private final Map<BlockPos, Integer> blockSeekVersions = new ConcurrentHashMap<>();
    private final Map<BlockPos, String> blockRuntimeSyncKeys = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> blockRuntimeSyncAtMs = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> blockSilentSinceAtMs = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> blockRecoveryAtMs = new ConcurrentHashMap<>();
    private final Set<String> nearbyBlockRadioIds = ConcurrentHashMap.newKeySet();

    // Independent handheld playback sessions keyed by radio id.
    private final Map<String, HandheldSession> handheldSessions = new ConcurrentHashMap<>();
    private final Set<String> externalRadioIds = ConcurrentHashMap.newKeySet();
    private final Map<String, ExternalRadioContext> externalContexts = new ConcurrentHashMap<>();
    private String activeHandheldRadioId = "";
    private InteractionHand activeHandheldHand = InteractionHand.MAIN_HAND;

    private int blockScanTicker;

    public static ClientAudioEngine getInstance() {
        return INSTANCE;
    }

    public void tick(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            stopAll();
            return;
        }

        pruneStaleExternalContexts(minecraft);
        tickHandheld(minecraft);
        releaseInactiveLocalChannels(minecraft);
        tickNonActiveExternalSessions(minecraft);

        blockScanTicker++;
        if (blockScanTicker % 5 == 0) {
            scanBlockRadios(minecraft);
        }

        tickBlockChannels(minecraft);
        pruneDetachedRuntimeSessions(minecraft);
    }

    public void playHandheld(String url, long positionMs, InteractionHand hand, String displayTitle, String artist, String thumbnail) {
        if (url == null || url.isBlank()) {
            return;
        }

        if (hand != null) {
            activeHandheldHand = hand;
        }
        HandheldSession session = activeOrCreateSession();
        if (session == null) {
            return;
        }
        if (hand != null) {
            session.preferredHand = hand;
        }
        // Server is authoritative for runtime transport state. Only send an intent command.
        sendHandheldControlCommand(
                session,
                ServerboundRadioControlMessage.Action.PLAY_URL,
                url,
                displayTitle,
                artist,
                thumbnail,
                Math.max(0L, positionMs),
                session.lastKnownTrackDurationMs
        );
        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        sendHandheldControlCommand(
                session,
                ServerboundRadioControlMessage.Action.UPDATE_QUEUE_STATE,
                repository.exportQueueStateJsonForRadioId(session.radioId),
                "",
                "",
                "",
                0L,
                -1L
        );
    }

    public void togglePauseHandheld() {
        HandheldSession session = activeSession();
        if (session == null) {
            return;
        }
        sendHandheldControlCommand(
                session,
                ServerboundRadioControlMessage.Action.TOGGLE_PAUSE,
                "",
                "",
                "",
                "",
                0L,
                -1L
        );
    }

    public void stopHandheld() {
        HandheldSession session = activeSession();
        if (session == null) {
            return;
        }
        sendHandheldControlCommand(
                session,
                ServerboundRadioControlMessage.Action.STOP,
                "",
                "",
                "",
                "",
                0L,
                -1L
        );
    }

    public void clearHandheldState() {
        HandheldSession session = activeSession();
        if (session == null) {
            activeHandheldRadioId = "";
            return;
        }
        stopSessionPlayback(session);
        session.title = "";
        session.artist = "";
        session.thumbnail = "";
        session.url = "";
        session.lastKnownTrackDurationMs = -1L;
        session.intendedPlaying = false;
        session.pausedState = false;
        session.pausedPositionMs = 0L;
        session.awaitingAuthoritativeHandheldState = false;
        session.lastHandheldRuntimeStateRequestAtMs = 0L;
        clearAuthoritativeTimeline(session);
    }

    public void setHandheldVolume(float volume) {
        HandheldSession session = activeOrCreateSession();
        if (session == null) {
            return;
        }
        session.volume = Mth.clamp(volume, 0f, 2f);
        sendHandheldControlCommand(
                session,
                ServerboundRadioControlMessage.Action.SET_VOLUME,
                "",
                "",
                "",
                "",
                0L,
                -1L
        );
    }

    public float getHandheldVolume() {
        HandheldSession session = readSession();
        if (session == null) {
            return 1.0f;
        }
        return session.volume;
    }

    public String getHandheldNowPlaying() {
        HandheldSession session = readSession();
        if (session == null) {
            return "";
        }
        if (session.channel != null) {
            return session.channel.getDisplayTitle();
        }
        return session.title;
    }

    public boolean isHandheldPlaying() {
        HandheldSession session = readSession();
        return session != null && session.channel != null && session.channel.isPlaying();
    }

    public boolean isHandheldPaused() {
        HandheldSession session = readSession();
        if (session == null) {
            return false;
        }
        if (session.channel != null) {
            return session.channel.isPaused();
        }
        return session.pausedState && (!session.url.isBlank() || !session.title.isBlank());
    }

    public long getHandheldPlaybackPositionMs() {
        HandheldSession session = readSession();
        if (session == null) {
            return 0L;
        }
        return session.channel == null ? Math.max(0L, session.pausedPositionMs) : session.channel.getEstimatedPositionMs();
    }

    public long getHandheldTrackDurationMs() {
        HandheldSession session = readSession();
        if (session == null || session.channel == null) {
            return -1L;
        }
        return session.channel.getTrackDurationMs();
    }

    public void seekHandheld(long positionMs) {
        HandheldSession session = activeSession();
        if (session == null) {
            return;
        }

        long clamped = Math.max(0L, positionMs);
        session.seekSerial = Math.max(0, session.seekSerial + 1);
        sendHandheldControlCommand(
                session,
                ServerboundRadioControlMessage.Action.SEEK,
                "",
                "",
                "",
                "",
                clamped,
                -1L
        );
    }

    public String getHandheldArtist() {
        HandheldSession session = readSession();
        return session == null ? "" : session.artist;
    }

    public String getHandheldThumbnail() {
        HandheldSession session = readSession();
        return session == null ? "" : session.thumbnail;
    }

    public String getHandheldUrl() {
        HandheldSession session = readSession();
        return session == null ? "" : session.url;
    }

    public HandheldRenderState getRenderStateForRadioId(String radioId) {
        if (radioId == null || radioId.isBlank()) {
            return null;
        }

        HandheldSession session = handheldSessions.get(radioId);
        if (session == null) {
            return null;
        }

        String title = session.title;
        long position = Math.max(0L, session.pausedPositionMs);
        long duration = session.lastKnownTrackDurationMs;
        boolean playing = false;
        boolean paused = session.pausedState;

        if (session.channel != null) {
            title = session.channel.getDisplayTitle();
            position = session.channel.getEstimatedPositionMs();
            long channelDuration = session.channel.getTrackDurationMs();
            if (channelDuration > 0L) {
                duration = channelDuration;
                session.lastKnownTrackDurationMs = channelDuration;
            }
            playing = session.channel.isPlaying();
            paused = session.channel.isPaused();
            if (!playing && !paused && !safe(session.url).isBlank() && (session.pausedState || session.intendedPlaying)) {
                paused = true;
            }
        } else if (!safe(session.url).isBlank() && (session.pausedState || session.intendedPlaying)) {
            paused = true;
        }

        if (duration > 0L) {
            position = Math.min(position, duration);
        }

        return new HandheldRenderState(
                safe(session.url),
                safe(title),
                safe(session.artist),
                safe(session.thumbnail),
                Math.max(0L, position),
                duration,
                Mth.clamp(session.volume, 0f, 2f),
                playing,
                paused
        );
    }

    public void setHandheldContext(String radioId, InteractionHand hand) {
        if (hand != null) {
            activeHandheldHand = hand;
        }

        String safeRadioId = safeRadioId(radioId);
        if (safeRadioId.isBlank()) {
            return;
        }

        if (!safeRadioId.equals(activeHandheldRadioId)) {
            HandheldSession previous = activeSession();
            if (previous != null && previous.channel != null) {
                previous.pausedPositionMs = previous.channel.getEstimatedPositionMs();
                previous.pausedState = !previous.url.isBlank();
                // Keep resume intent for sessions that were actively playing.
                if (previous.channel.isPlaying()) {
                    previous.intendedPlaying = true;
                }
                stopSessionPlayback(previous);
            }
        }

        activeHandheldRadioId = safeRadioId;
        // Keep repository queue context aligned with the handheld session context.
        ClientMediaRepository.getInstance().setActiveRadioId(safeRadioId);
        HandheldSession current = activeOrCreateSession();
        if (current == null) {
            return;
        }
        if (hand != null) {
            current.preferredHand = hand;
        }
        beginAwaitingAuthoritativeHandheldState(current);
        requestInitialHandheldRuntimeState(current, true);

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            resumeSessionIfHeld(minecraft, current);
        }
    }

    public void updateHandheldMetadata(String title, String artist, String thumbnail) {
        HandheldSession session = activeOrCreateSession();
        if (session == null) {
            return;
        }
        session.title = title == null ? "" : title;
        session.artist = artist == null ? "" : artist;
        session.thumbnail = thumbnail == null ? "" : thumbnail;
        if (session.channel != null && !session.title.isBlank()) {
            session.channel.setDisplayTitle(session.title);
        }
        sendHandheldControlCommand(
                session,
                ServerboundRadioControlMessage.Action.UPDATE_METADATA,
                session.url,
                session.title,
                session.artist,
                session.thumbnail,
                0L,
                -1L
        );
    }

    public void setExternalContext(String radioId, int contraptionEntityId, BlockPos localPos) {
        setExternalContext(radioId, contraptionEntityId, localPos, false);
    }

    public void setExternalContext(String radioId, int contraptionEntityId, BlockPos localPos, boolean inventoryPlayback) {
        String safeRadioId = safeRadioId(radioId);
        if (safeRadioId.isBlank()) {
            return;
        }
        boolean wasExternal = hasExternalContext(safeRadioId);
        registerExternalContext(safeRadioId, contraptionEntityId, localPos, inventoryPlayback, localPos != null);
        HandheldSession session = handheldSessions.computeIfAbsent(safeRadioId, HandheldSession::new);
        if (!wasExternal && localPos != null) {
            beginAwaitingAuthoritativeExternalState(session);
        }
        if (session != null) {
            reconfigureSessionChannelMode(session, shouldUsePositionalChannel(Minecraft.getInstance(), session));
        }
    }

    public boolean hasExternalContext(String radioId) {
        return isExternalSession(radioId);
    }

    public boolean shouldAcceptRuntimePacket(String radioId) {
        String safeRadioId = safeRadioId(radioId);
        if (safeRadioId.isBlank()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            return false;
        }
        return hasRelevantRuntimeSource(minecraft, safeRadioId);
    }

    public void clearRuntimeStateIfDetached(String radioId) {
        String safeRadioId = safeRadioId(radioId);
        if (safeRadioId.isBlank()) {
            return;
        }
        clearDetachedRuntimeSession(safeRadioId);
    }

    public void playExternal(String radioId, String url, long positionMs, String displayTitle, String artist, String thumbnail) {
        String safeRadioId = safeRadioId(radioId);
        if (safeRadioId.isBlank() || url == null || url.isBlank()) {
            return;
        }
        HandheldSession session = handheldSessions.computeIfAbsent(safeRadioId, HandheldSession::new);
        playSession(session, url, Math.max(0L, positionMs), displayTitle, artist, thumbnail);
    }

    public void updateExternalMetadata(String radioId, String title, String artist, String thumbnail) {
        String safeRadioId = safeRadioId(radioId);
        if (safeRadioId.isBlank()) {
            return;
        }
        HandheldSession session = handheldSessions.computeIfAbsent(safeRadioId, HandheldSession::new);
        session.title = title == null ? "" : title;
        session.artist = artist == null ? "" : artist;
        session.thumbnail = thumbnail == null ? "" : thumbnail;
        if (session.channel != null && !session.title.isBlank()) {
            session.channel.setDisplayTitle(session.title);
        }
    }

    public void togglePauseExternal(String radioId) {
        String safeRadioId = safeRadioId(radioId);
        if (safeRadioId.isBlank()) {
            return;
        }
        HandheldSession session = handheldSessions.get(safeRadioId);
        if (session == null) {
            return;
        }
        if (session.channel == null) {
            if (session.pausedState && !session.url.isBlank()) {
                session.pausedState = false;
                session.intendedPlaying = true;
                applyRuntimeStateToSession(
                        session,
                        session.url,
                        session.title,
                        session.artist,
                        session.thumbnail,
                        session.pausedPositionMs,
                        session.volume,
                        true
                );
            } else {
                session.pausedState = true;
                session.intendedPlaying = false;
            }
            return;
        }
        if (session.channel.isPlaying()) {
            session.pausedPositionMs = session.channel.getEstimatedPositionMs();
            session.pausedState = true;
            session.intendedPlaying = false;
            session.channel.pause();
        } else {
            session.pausedState = false;
            session.intendedPlaying = true;
            session.channel.resume();
        }
    }

    public void stopExternal(String radioId) {
        String safeRadioId = safeRadioId(radioId);
        if (safeRadioId.isBlank()) {
            return;
        }
        HandheldSession session = handheldSessions.get(safeRadioId);
        if (session == null) {
            return;
        }
        stopSessionPlayback(session);
        session.intendedPlaying = false;
        session.pausedState = false;
        session.pausedPositionMs = 0L;
    }

    public void setExternalVolume(String radioId, float volume) {
        String safeRadioId = safeRadioId(radioId);
        if (safeRadioId.isBlank()) {
            return;
        }
        HandheldSession session = handheldSessions.computeIfAbsent(safeRadioId, HandheldSession::new);
        session.volume = Mth.clamp(volume, 0f, 2f);
    }

    public void seekExternal(String radioId, long positionMs) {
        String safeRadioId = safeRadioId(radioId);
        if (safeRadioId.isBlank()) {
            return;
        }
        HandheldSession session = handheldSessions.get(safeRadioId);
        if (session == null) {
            return;
        }
        long clamped = Math.max(0L, positionMs);
        session.pausedPositionMs = clamped;
        if (session.channel == null) {
            return;
        }
        session.channel.seekTo(clamped, session.channel.isPaused());
    }

    public void releaseExternalContext(String radioId) {
        String safeRadioId = safeRadioId(radioId);
        if (safeRadioId.isBlank()) {
            return;
        }
        HandheldSession session = handheldSessions.get(safeRadioId);
        externalContexts.remove(safeRadioId);
        externalRadioIds.remove(safeRadioId);

        Minecraft minecraft = Minecraft.getInstance();
        if (session == null) {
            return;
        }

        boolean hasLocalRadio = minecraft.player != null && !findRadioStackById(minecraft, safeRadioId).isEmpty();
        if (!hasLocalRadio) {
            if (session.channel != null) {
                session.pausedPositionMs = Math.max(0L, session.channel.getEstimatedPositionMs());
            }
            // Always stop channel before dropping the session entry, otherwise
            // OpenAL sources can be stranded and starve future playback.
            stopSessionPlayback(session);
            session.pausedState = session.intendedPlaying;
            session.lastExternalSyncKey = "";
            handheldSessions.remove(safeRadioId);
            if (safeRadioId.equals(activeHandheldRadioId)) {
                activeHandheldRadioId = "";
            }
            return;
        }

        reconfigureSessionChannelMode(session, false);
    }

    public void primeHandheldState(String url, String title, String artist, String thumbnail, long positionMs, float volume) {
        primeHandheldState(url, title, artist, thumbnail, positionMs, volume, false);
    }

    public void primeHandheldState(String url, String title, String artist, String thumbnail, long positionMs, float volume, boolean playing) {
        HandheldSession session = activeOrCreateSession();
        if (session == null) {
            return;
        }

        session.url = url == null ? "" : url;
        session.title = title == null ? "" : title;
        session.artist = artist == null ? "" : artist;
        session.thumbnail = thumbnail == null ? "" : thumbnail;
        session.volume = Mth.clamp(volume, 0f, 2f);
        session.pausedPositionMs = Math.max(0L, positionMs);
        session.pausedState = !playing && (!session.url.isBlank() || !session.title.isBlank());
        session.intendedPlaying = playing;

        if (session.channel != null) {
            reconfigureSessionChannelMode(session, shouldUsePositionalChannel(Minecraft.getInstance(), session));
            if (!session.title.isBlank()) {
                session.channel.setDisplayTitle(session.title);
            }

            if (!session.url.isBlank()) {
                if (!session.url.equals(session.channel.getCurrentUrl())) {
                    session.channel.play(session.url, session.pausedPositionMs);
                }
                if (playing) {
                    session.channel.resume();
                    session.pausedState = false;
                } else {
                    session.channel.pause();
                    session.pausedState = true;
                }
            }
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (playing && minecraft.player != null) {
            resumeSessionIfHeld(minecraft, session);
        }
    }

    public void syncExternalContraptionState(
            String radioId,
            int contraptionEntityId,
            BlockPos localPos,
            String url,
            String title,
            String artist,
            String thumbnail,
            float volume,
            long positionMs,
            boolean playing
    ) {
        String safeRadioId = safeRadioId(radioId);
        if (safeRadioId.isBlank()) {
            return;
        }

        boolean wasExternal = hasExternalContext(safeRadioId);
        registerExternalContext(safeRadioId, contraptionEntityId, localPos, false, false);
        HandheldSession session = handheldSessions.computeIfAbsent(safeRadioId, HandheldSession::new);
        ExternalRadioContext context = externalContexts.get(safeRadioId);
        boolean reacquireNeedsResync = false;
        if (context != null && context.localPos != null && context.needsAuthoritativeResync) {
            context.needsAuthoritativeResync = false;
            reacquireNeedsResync = true;
        }
        if (!wasExternal || reacquireNeedsResync) {
            // Freshly reacquired contraption contexts can carry stale movement NBT/client cache.
            // Wait for server-authoritative runtime before accepting external playback updates.
            beginAwaitingAuthoritativeExternalState(session);
            ModNetworking.requestRadioState(safeRadioId, net.jacobwasbeast.mediaradio.network.message.ServerboundRequestRadioStateMessage.Context.BLOCK);
        }
        if (session.awaitingAuthoritativeExternalState) {
            return;
        }

        String resolvedUrl = safe(url);
        String resolvedTitle = safe(title);
        String resolvedArtist = safe(artist);
        String resolvedThumbnail = safe(thumbnail);
        boolean resolvedPlaying = playing;
        long resolvedPositionMs = Math.max(0L, positionMs);

        // Contraption block NBT can be stale between assembly/movement ticks.
        // Never let stale NBT downgrade an already-playing session for the same track.
        if (resolvedUrl.isBlank() && !session.url.isBlank()) {
            resolvedUrl = session.url;
        }
        if (resolvedTitle.isBlank() && !session.title.isBlank()) {
            resolvedTitle = session.title;
        }
        if (resolvedArtist.isBlank() && !session.artist.isBlank()) {
            resolvedArtist = session.artist;
        }
        if (resolvedThumbnail.isBlank() && !session.thumbnail.isBlank()) {
            resolvedThumbnail = session.thumbnail;
        }
        if (!resolvedUrl.isBlank()
                && !session.url.isBlank()
                && sameTrack(resolvedUrl, session.url)
                && session.intendedPlaying
                && !playing) {
            resolvedPlaying = true;
            if (session.channel != null) {
                resolvedPositionMs = Math.max(0L, session.channel.getEstimatedPositionMs());
            } else {
                resolvedPositionMs = Math.max(resolvedPositionMs, Math.max(0L, session.pausedPositionMs));
            }
        }

        String stateKey = resolvedUrl + "|" + resolvedTitle + "|" + resolvedArtist + "|" + resolvedThumbnail + "|"
                + Mth.clamp(volume, 0f, 2f) + "|" + resolvedPlaying + "|" + contraptionEntityId + "|"
                + (localPos == null ? "null" : localPos.toShortString()) + "|"
                + (resolvedPlaying ? "moving" : resolvedPositionMs / 250L);
        if (stateKey.equals(session.lastExternalSyncKey)) {
            return;
        }

        applyRuntimeStateToSession(
                session,
                resolvedUrl,
                resolvedTitle,
                resolvedArtist,
                resolvedThumbnail,
                resolvedPositionMs,
                volume,
                resolvedPlaying
        );
        session.lastExternalSyncKey = stateKey;
    }

    public void stopAll() {
        for (HandheldSession session : handheldSessions.values()) {
            stopSessionPlayback(session);
        }
        handheldSessions.clear();
        externalRadioIds.clear();
        externalContexts.clear();
        nearbyBlockRadioIds.clear();
        activeHandheldRadioId = "";
        activeHandheldHand = InteractionHand.MAIN_HAND;

        blockChannels.values().forEach(RadioAudioChannel::stop);
        blockChannels.clear();
        blockSeekVersions.clear();
        blockRuntimeSyncKeys.clear();
        blockRuntimeSyncAtMs.clear();
        blockSilentSinceAtMs.clear();
        blockRecoveryAtMs.clear();
    }

    public long getBlockPlaybackPositionMs(BlockPos blockPos) {
        RadioAudioChannel channel = blockChannels.get(blockPos);
        return channel == null ? -1L : channel.getEstimatedPositionMs();
    }

    public long getBlockTrackDurationMs(BlockPos blockPos) {
        RadioAudioChannel channel = blockChannels.get(blockPos);
        return channel == null ? -1L : channel.getTrackDurationMs();
    }

    private void tickHandheld(Minecraft minecraft) {
        pruneOrphanedHandheldSessions(minecraft);
        syncActiveContextFromHeldHands(minecraft);
        bootstrapInventoryHandheldRuntimeSync(minecraft);

        HandheldSession session = activeSession();
        if (session == null) {
            return;
        }
        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        boolean blockScreenOpen = minecraft.screen instanceof RadioScreen radioScreen && radioScreen.isBlockModeScreen();
        if (!blockScreenOpen && !session.radioId.equals(repository.getActiveRadioId())) {
            repository.setActiveRadioId(session.radioId);
        }
        boolean activeHandheldScreenOpen = minecraft.screen instanceof RadioScreen radioScreen
                && !radioScreen.isBlockModeScreen()
                && session.radioId.equals(repository.getActiveRadioId());
        requestInitialHandheldRuntimeState(session, false);

        // While the radio is in block placement mode, keep playback paused locally.
        // We still preserve intendedPlaying so placing the radio can resume playback.
        if (isRadioHeldInPlaceMode(minecraft, session.radioId) && !activeHandheldScreenOpen) {
            if (session.channel != null && session.channel.isPlaying()) {
                session.pausedPositionMs = session.channel.getEstimatedPositionMs();
                session.pausedState = true;
                session.intendedPlaying = true;
                session.channel.pause();
            }
            persistRuntimeToSession(minecraft, session);
            return;
        }

        // Handheld radios continue playback while in inventory.
        // Orphaned radios are pruned at the start of this tick.
        if (!isExternalSession(session.radioId) && !isRadioInInventory(minecraft, session.radioId)) {
            return;
        }

        if (!shouldKeepSessionChannelActive(minecraft, session)) {
            if (session.channel != null) {
                session.pausedPositionMs = session.channel.getEstimatedPositionMs();
                stopSessionPlayback(session);
            }
            session.pausedState = !session.url.isBlank();
            persistRuntimeToSession(minecraft, session);
            return;
        }

        if (session.channel == null) {
            if (session.intendedPlaying && !session.url.isBlank()) {
                resumeSessionIfHeld(minecraft, session);
            }
            return;
        }

        if (session.channel.isPaused() && session.intendedPlaying) {
            session.channel.resume();
            session.pausedState = false;
        }

        session.channel.tick();
        long nowMs = System.currentTimeMillis();
        correctRemotePlaybackDrift(session, nowMs);

        boolean ended = session.channel.consumeNaturalEnd();
        if (!ended && hasExceededTrackDuration(session.channel)) {
            session.channel.stop();
            ended = true;
        }
        if (!ended) {
            recoverSilentSessionChannel(minecraft, session, nowMs);
            return;
        }
        stopSessionPlayback(session);
        session.pausedState = session.intendedPlaying;
        ModNetworking.requestRadioState(
                session.radioId,
                net.jacobwasbeast.mediaradio.network.message.ServerboundRequestRadioStateMessage.Context.HANDHELD
        );
    }

    private void bootstrapInventoryHandheldRuntimeSync(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        Set<String> inventoryRadioIds = new HashSet<>();
        collectInventoryRadioIds(minecraft, inventoryRadioIds);
        for (String radioId : inventoryRadioIds) {
            if (radioId == null || radioId.isBlank()) {
                continue;
            }
            HandheldSession session = handheldSessions.computeIfAbsent(radioId, HandheldSession::new);
            if (!session.awaitingAuthoritativeHandheldState
                    && session.serverSessionId.isBlank()
                    && session.lastServerRevision < 0L
                    && session.channel == null
                    && safe(session.url).isBlank()
                    && !session.intendedPlaying
                    && !session.pausedState) {
                beginAwaitingAuthoritativeHandheldState(session);
            }
            requestInitialHandheldRuntimeState(session, false);
        }
    }

    private void collectInventoryRadioIds(Minecraft minecraft, Set<String> collector) {
        if (minecraft == null || minecraft.player == null || collector == null) {
            return;
        }
        String mainRadioId = radioIdFromStack(minecraft.player.getMainHandItem());
        if (!mainRadioId.isBlank()) {
            collector.add(mainRadioId);
        }
        String offRadioId = radioIdFromStack(minecraft.player.getOffhandItem());
        if (!offRadioId.isBlank()) {
            collector.add(offRadioId);
        }
        for (ItemStack stack : minecraft.player.getInventory().items) {
            String radioId = radioIdFromStack(stack);
            if (!radioId.isBlank()) {
                collector.add(radioId);
            }
        }
        for (ItemStack stack : minecraft.player.getInventory().offhand) {
            String radioId = radioIdFromStack(stack);
            if (!radioId.isBlank()) {
                collector.add(radioId);
            }
        }
    }

    private void pruneOrphanedHandheldSessions(Minecraft minecraft) {
        if (minecraft.player == null || handheldSessions.isEmpty()) {
            return;
        }

        Set<String> orphanedRadioIds = new HashSet<>();
        for (Map.Entry<String, HandheldSession> entry : handheldSessions.entrySet()) {
            String radioId = entry.getKey();
            if (radioId == null || radioId.isBlank()) {
                orphanedRadioIds.add(radioId == null ? "" : radioId);
                continue;
            }
            if (isExternalSession(radioId)) {
                continue;
            }
            if (findRadioStackById(minecraft, radioId).isEmpty()) {
                orphanedRadioIds.add(radioId);
            }
        }

        for (String radioId : orphanedRadioIds) {
            HandheldSession orphaned = handheldSessions.remove(radioId);
            if (orphaned == null) {
                continue;
            }
            stopSessionPlayback(orphaned);
            orphaned.title = "";
            orphaned.artist = "";
            orphaned.thumbnail = "";
            orphaned.url = "";
            orphaned.lastKnownTrackDurationMs = -1L;
            orphaned.pausedState = false;
            orphaned.pausedPositionMs = 0L;
            orphaned.intendedPlaying = false;
            orphaned.lastExternalSyncKey = "";
            if (radioId.equals(activeHandheldRadioId)) {
                activeHandheldRadioId = "";
            }
        }
    }

    private void tickNonActiveExternalSessions(Minecraft minecraft) {
        List<ExternalSessionCandidate> activeCandidates = new ArrayList<>();
        for (String radioId : externalRadioIds) {
            if (radioId == null || radioId.isBlank() || radioId.equals(activeHandheldRadioId)) {
                continue;
            }
            HandheldSession session = handheldSessions.get(radioId);
            if (session == null) {
                continue;
            }
            if (!shouldKeepSessionChannelActive(minecraft, session)) {
                if (session.channel != null) {
                    session.pausedPositionMs = Math.max(0L, session.channel.getEstimatedPositionMs());
                    stopSessionPlayback(session);
                }
                session.pausedState = !session.url.isBlank();
                persistRuntimeToSession(minecraft, session);
                continue;
            }
            activeCandidates.add(new ExternalSessionCandidate(
                    radioId,
                    session,
                    resolveExternalDistanceSqr(minecraft, radioId)
            ));
        }

        activeCandidates.sort(Comparator.comparingDouble(ExternalSessionCandidate::distanceSqr));
        int activeCount = 0;
        long nowMs = System.currentTimeMillis();
        for (ExternalSessionCandidate candidate : activeCandidates) {
            String radioId = candidate.radioId();
            HandheldSession session = candidate.session();
            if (activeCount >= MAX_ACTIVE_EXTERNAL_CHANNELS) {
                if (session.channel != null) {
                    session.pausedPositionMs = Math.max(0L, session.channel.getEstimatedPositionMs());
                    stopSessionPlayback(session);
                }
                session.pausedState = !session.url.isBlank();
                persistRuntimeToSession(minecraft, session);
                continue;
            }
            activeCount++;

            if (session.channel == null) {
                if (session.intendedPlaying && !session.url.isBlank()) {
                    resumeSessionIfHeld(minecraft, session);
                }
            }
            if (session.channel == null) {
                continue;
            }
            session.channel.tick();
            correctRemotePlaybackDrift(session, nowMs);
            boolean ended = session.channel.consumeNaturalEnd();
            if (!ended && hasExceededTrackDuration(session.channel)) {
                session.channel.stop();
                ended = true;
            }
            if (ended) {
                session.pausedPositionMs = Math.max(0L, session.channel.getEstimatedPositionMs());
                stopSessionPlayback(session);
                session.pausedState = session.intendedPlaying;
                if (session.intendedPlaying && !session.url.isBlank()) {
                    ExternalRadioContext context = externalContexts.get(radioId);
                    ModNetworking.requestRadioState(
                            radioId,
                            context != null && context.localPos != null
                                    ? net.jacobwasbeast.mediaradio.network.message.ServerboundRequestRadioStateMessage.Context.BLOCK
                                    : net.jacobwasbeast.mediaradio.network.message.ServerboundRequestRadioStateMessage.Context.HANDHELD
                    );
                }
            } else {
                recoverSilentSessionChannel(minecraft, session, nowMs);
            }
            persistRuntimeToSession(minecraft, session);
        }
    }

    private void releaseInactiveLocalChannels(Minecraft minecraft) {
        for (Map.Entry<String, HandheldSession> entry : handheldSessions.entrySet()) {
            String radioId = entry.getKey();
            if (radioId == null || radioId.isBlank()) {
                continue;
            }
            if (radioId.equals(activeHandheldRadioId) || isExternalSession(radioId)) {
                continue;
            }
            HandheldSession session = entry.getValue();
            if (session == null || session.channel == null) {
                continue;
            }
            session.pausedPositionMs = Math.max(0L, session.channel.getEstimatedPositionMs());
            stopSessionPlayback(session);
            session.pausedState = !session.url.isBlank();
            persistRuntimeToSession(minecraft, session);
        }
    }

    private void pruneStaleExternalContexts(Minecraft minecraft) {
        if (minecraft.level == null || externalContexts.isEmpty()) {
            return;
        }

        long now = minecraft.level.getGameTime();
        Set<String> stale = new HashSet<>();
        for (Map.Entry<String, ExternalRadioContext> entry : externalContexts.entrySet()) {
            String radioId = entry.getKey();
            ExternalRadioContext context = entry.getValue();
            if (context == null || radioId == null || radioId.isBlank()) {
                stale.add(radioId == null ? "" : radioId);
                continue;
            }
            Entity entity = minecraft.level.getEntity(context.contraptionEntityId);
            if (entity != null) {
                if (context.localPos != null
                        && context.firstMissingGameTick >= 0L
                        && now > context.firstMissingGameTick
                        && now - context.firstMissingGameTick >= EXTERNAL_REACQUIRE_RESYNC_MIN_MISSING_TICKS) {
                    context.needsAuthoritativeResync = true;
                }
                context.firstMissingGameTick = -1L;
                context.lastSeenGameTick = now;
                continue;
            }
            if (context.firstMissingGameTick < 0L) {
                context.firstMissingGameTick = now;
            }
            long ttlTicks = context.localPos == null ? EXTERNAL_CONTEXT_TTL_HANDHELD_TICKS : EXTERNAL_CONTEXT_TTL_CONTRAPTION_TICKS;
            if (now - context.lastSeenGameTick > ttlTicks) {
                stale.add(radioId);
            }
        }

        for (String radioId : stale) {
            externalContexts.remove(radioId);
            externalRadioIds.remove(radioId);
            HandheldSession session = handheldSessions.get(radioId);
            if (session != null) {
                if (session.channel != null) {
                    session.pausedPositionMs = Math.max(0L, session.channel.getEstimatedPositionMs());
                }
                stopSessionPlayback(session);
                session.pausedState = session.intendedPlaying;
                session.lastExternalSyncKey = "";
                if (!session.intendedPlaying && (minecraft.player == null || findRadioStackById(minecraft, radioId).isEmpty())) {
                    handheldSessions.remove(radioId);
                }
            }
            if (radioId.equals(activeHandheldRadioId)) {
                activeHandheldRadioId = "";
            }
        }
    }

    private void playSession(HandheldSession session, String url, long positionMs, String displayTitle, String artist, String thumbnail) {
        String previousUrl = safe(session.url);
        session.url = url == null ? "" : url;
        if (!sameTrack(previousUrl, session.url)) {
            session.lastKnownTrackDurationMs = -1L;
        }
        session.title = displayTitle == null ? "" : displayTitle;
        session.artist = artist == null ? "" : artist;
        session.thumbnail = thumbnail == null ? "" : thumbnail;
        session.pausedPositionMs = Math.max(0L, positionMs);
        session.pausedState = false;
        session.intendedPlaying = true;
        ensureSessionChannel(Minecraft.getInstance(), session, session.pausedPositionMs, true);
    }

    private void stopSessionPlayback(HandheldSession session) {
        if (session.channel != null) {
            session.channel.stop();
            session.channel = null;
        }
        session.lastObservedChannelProgressAtMs = 0L;
        session.lastObservedChannelPositionMs = 0L;
    }

    private void resumeSessionIfHeld(Minecraft minecraft, HandheldSession session) {
        if (session == null || !session.intendedPlaying || session.url.isBlank()) {
            return;
        }
        if (!isRadioInInventory(minecraft, session.radioId)) {
            return;
        }
        if (!ensureSessionChannel(minecraft, session, session.pausedPositionMs, false)) {
            return;
        }
        if (session.channel.isPaused() || !session.channel.isPlaying()) {
            session.channel.resume();
        }
        session.pausedState = false;
        recordSessionPlaybackProgress(session, session.channel.getEstimatedPositionMs(), System.currentTimeMillis());
    }

    private void recordSessionPlaybackProgress(HandheldSession session, long positionMs, long nowMs) {
        if (session == null) {
            return;
        }
        session.lastObservedChannelPositionMs = Math.max(0L, positionMs);
        session.lastObservedChannelProgressAtMs = Math.max(0L, nowMs);
    }

    private void updateAuthoritativeTimeline(HandheldSession session, long positionMs, boolean playing, long nowMs) {
        if (session == null) {
            return;
        }
        session.authoritativePositionMs = Math.max(0L, positionMs);
        session.authoritativePositionAppliedAtMs = Math.max(0L, nowMs);
        session.authoritativePlaying = playing;
    }

    private void clearAuthoritativeTimeline(HandheldSession session) {
        if (session == null) {
            return;
        }
        session.authoritativePositionMs = 0L;
        session.authoritativePositionAppliedAtMs = 0L;
        session.authoritativePlaying = false;
    }

    private long projectedAuthoritativePositionMs(HandheldSession session, long nowMs) {
        if (session == null) {
            return 0L;
        }
        long projected = Math.max(0L, session.authoritativePositionMs);
        if (!session.authoritativePlaying) {
            return projected;
        }
        long anchorAtMs = Math.max(0L, session.authoritativePositionAppliedAtMs);
        long elapsedMs = Math.max(0L, nowMs - anchorAtMs);
        if (Long.MAX_VALUE - projected < elapsedMs) {
            return Long.MAX_VALUE;
        }
        return projected + elapsedMs;
    }

    private void correctRemotePlaybackDrift(HandheldSession session, long nowMs) {
        if (session == null
                || session.channel == null
                || !session.intendedPlaying
                || !session.authoritativePlaying
                || !shouldApplyRemotePositionCorrection(session)
                || !session.channel.isPlaying()) {
            return;
        }
        long targetPos = projectedAuthoritativePositionMs(session, nowMs);
        long durationMs = session.channel.getTrackDurationMs();
        if (durationMs > 0L && durationMs != Long.MAX_VALUE) {
            targetPos = Math.min(targetPos, durationMs);
        }
        long localPos = Math.max(0L, session.channel.getEstimatedPositionMs());
        long behindMs = targetPos - localPos;
        if (behindMs < REMOTE_PLAYING_DRIFT_CORRECTION_MS) {
            return;
        }
        if (nowMs - session.lastRemoteSeekAtMs < REMOTE_DRIFT_CORRECTION_COOLDOWN_MS) {
            return;
        }
        session.channel.seekTo(targetPos, false);
        session.pausedPositionMs = Math.max(0L, targetPos);
        session.lastRemoteSeekAtMs = nowMs;
        recordSessionPlaybackProgress(session, targetPos, nowMs);
    }

    private boolean recoverSilentSessionChannel(Minecraft minecraft, HandheldSession session, long nowMs) {
        if (session == null
                || session.channel == null
                || session.url.isBlank()
                || session.pausedState
                || !session.intendedPlaying) {
            return false;
        }

        RadioAudioChannel channel = session.channel;
        long observedPosition = Math.max(0L, channel.getEstimatedPositionMs());
        if (channel.hasPersistentSourceFailure(CHANNEL_STALL_RECOVERY_THRESHOLD_MS)) {
            if (nowMs - session.lastHardRecoveryAttemptAtMs < CHANNEL_HARD_RECOVERY_COOLDOWN_MS) {
                return false;
            }
            session.lastHardRecoveryAttemptAtMs = nowMs;
            long resumePosition = Math.max(observedPosition, Math.max(0L, session.pausedPositionMs));
            stopSessionPlayback(session);
            if (!ensureSessionChannel(minecraft, session, resumePosition, true)) {
                return false;
            }
            session.pausedState = false;
            session.pausedPositionMs = resumePosition;
            recordSessionPlaybackProgress(session, resumePosition, nowMs);
            return true;
        }
        if (channel.isPlaying()) {
            if (observedPosition > session.lastObservedChannelPositionMs + 32L
                    || nowMs - session.lastObservedChannelProgressAtMs >= 1_000L) {
                recordSessionPlaybackProgress(session, observedPosition, nowMs);
            }
            return false;
        }
        if (channel.isPaused()) {
            return false;
        }

        long stallForMs = nowMs - Math.max(0L, session.lastObservedChannelProgressAtMs);
        if (stallForMs < CHANNEL_STALL_RECOVERY_THRESHOLD_MS) {
            return false;
        }

        long resumePosition = Math.max(observedPosition, Math.max(0L, session.pausedPositionMs));
        if (nowMs - session.lastSoftRecoveryAttemptAtMs >= CHANNEL_SOFT_RECOVERY_COOLDOWN_MS) {
            session.lastSoftRecoveryAttemptAtMs = nowMs;
            channel.play(session.url, resumePosition);
            channel.setDisplayTitle(session.title);
            session.pausedState = false;
            session.pausedPositionMs = resumePosition;
            recordSessionPlaybackProgress(session, resumePosition, nowMs);
            return true;
        }

        if (stallForMs < CHANNEL_HARD_RECOVERY_COOLDOWN_MS
                || nowMs - session.lastHardRecoveryAttemptAtMs < CHANNEL_HARD_RECOVERY_COOLDOWN_MS) {
            return false;
        }

        session.lastHardRecoveryAttemptAtMs = nowMs;
        stopSessionPlayback(session);
        if (!ensureSessionChannel(minecraft, session, resumePosition, true)) {
            return false;
        }
        session.pausedState = false;
        session.pausedPositionMs = resumePosition;
        recordSessionPlaybackProgress(session, resumePosition, nowMs);
        return true;
    }

    private boolean ensureSessionChannel(Minecraft minecraft, HandheldSession session, long startPositionMs, boolean forceRestart) {
        if (session == null || session.url == null || session.url.isBlank()) {
            return false;
        }
        boolean shouldBePositional = shouldUsePositionalChannel(minecraft, session);
        boolean recreate = session.channel == null || session.channel.isPositional() != shouldBePositional;
        if (recreate) {
            stopSessionPlayback(session);
            session.channel = createSessionChannel(session, shouldBePositional);
        }
        if (session.channel == null) {
            return false;
        }

        session.channel.setDisplayTitle(session.title);
        long clampedPosition = Math.max(0L, startPositionMs);
        boolean trackMismatch = !session.url.equals(session.channel.getCurrentUrl());
        if (forceRestart || recreate || trackMismatch) {
            session.channel.play(session.url, clampedPosition);
            session.pausedPositionMs = clampedPosition;
            recordSessionPlaybackProgress(session, clampedPosition, System.currentTimeMillis());
        }
        return true;
    }

    private void reconfigureSessionChannelMode(HandheldSession session, boolean shouldBePositional) {
        if (session == null || session.channel == null || session.channel.isPositional() == shouldBePositional) {
            return;
        }

        long resumePosition = session.channel.getEstimatedPositionMs();
        boolean shouldKeepPlaying = session.intendedPlaying;
        stopSessionPlayback(session);
        session.pausedPositionMs = Math.max(0L, resumePosition);
        if (session.url == null || session.url.isBlank()) {
            return;
        }
        if (!shouldKeepPlaying) {
            session.pausedState = true;
            return;
        }
        if (!ensureSessionChannel(Minecraft.getInstance(), session, session.pausedPositionMs, true)) {
            return;
        }
        session.pausedState = false;
        session.channel.resume();
        recordSessionPlaybackProgress(session, session.pausedPositionMs, System.currentTimeMillis());
    }

    private RadioAudioChannel createSessionChannel(HandheldSession session, boolean shouldBePositional) {
        if (shouldBePositional) {
            return new RadioAudioChannel(
                    true,
                    () -> resolveExternalSourcePosition(session.radioId),
                    () -> resolveExternalSessionVolume(session, session.radioId),
                    resolveExternalMaxDistance(session.radioId)
            );
        }
        return new RadioAudioChannel(
                false,
                () -> Vec3.ZERO,
                () -> Mth.clamp(session.volume * ClientAudioSettings.get().selfHandheldVolume(), 0f, 2f),
                1f
        );
    }

    private float resolveExternalSessionVolume(HandheldSession session, String radioId) {
        float baseVolume = Mth.clamp(session == null ? 1.0f : session.volume, 0f, 2f);
        ExternalRadioContext context = externalContexts.get(radioId);
        ClientAudioSettings settings = ClientAudioSettings.get();
        if (context == null) {
            return baseVolume;
        }
        if (context.inventoryPlayback && !settings.hearInventoryPlayerRadios()) {
            return 0f;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return 0f;
        }
        Vec3 sourcePos = resolveExternalSourcePosition(context);
        double maxDistanceSqr = context.localPos == null ? HANDHELD_EXTERNAL_MAX_DISTANCE_SQR : CONTRAPTION_EXTERNAL_MAX_DISTANCE_SQR;
        if (sourcePos == null || minecraft.player.position().distanceToSqr(sourcePos) > maxDistanceSqr) {
            return 0f;
        }
        if (context.localPos != null) {
            return Mth.clamp(baseVolume * settings.blockRadioVolume(), 0f, 2f);
        }
        return Mth.clamp(baseVolume * settings.otherPlayersHandheldVolume(), 0f, 2f);
    }

    private float resolveExternalMaxDistance(String radioId) {
        ExternalRadioContext context = externalContexts.get(radioId);
        if (context != null && context.localPos == null) {
            return HANDHELD_EXTERNAL_MAX_DISTANCE;
        }
        return CONTRAPTION_EXTERNAL_MAX_DISTANCE;
    }

    private double resolveExternalDistanceSqr(Minecraft minecraft, String radioId) {
        if (minecraft == null || minecraft.player == null) {
            return Double.MAX_VALUE;
        }
        ExternalRadioContext context = externalContexts.get(radioId);
        if (context == null) {
            return Double.MAX_VALUE;
        }
        Vec3 sourcePos = resolveExternalSourcePosition(context);
        if (sourcePos == null) {
            return Double.MAX_VALUE;
        }
        return minecraft.player.position().distanceToSqr(sourcePos);
    }

    private boolean hasRelevantRuntimeSource(Minecraft minecraft, String radioId) {
        if (radioId == null || radioId.isBlank() || minecraft == null || minecraft.player == null || minecraft.level == null) {
            return false;
        }
        if (!findRadioStackById(minecraft, radioId).isEmpty()) {
            return true;
        }
        if (nearbyBlockRadioIds.contains(radioId)) {
            return true;
        }
        if (isExternalSession(radioId)) {
            // External contexts are server-advertised relevance windows. Accept runtime
            // snapshots immediately after context activation to avoid first-packet drops.
            return true;
        }
        return hasNearbyExternalSource(minecraft, radioId);
    }

    private boolean hasNearbyExternalSource(Minecraft minecraft, String radioId) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null || radioId == null || radioId.isBlank()) {
            return false;
        }
        ExternalRadioContext context = externalContexts.get(radioId);
        if (context == null) {
            return false;
        }
        Vec3 sourcePos = resolveExternalSourcePosition(context);
        if (sourcePos == null) {
            return false;
        }
        double maxDistanceSqr = context.localPos == null ? HANDHELD_EXTERNAL_MAX_DISTANCE_SQR : CONTRAPTION_EXTERNAL_MAX_DISTANCE_SQR;
        return minecraft.player.position().distanceToSqr(sourcePos) <= maxDistanceSqr;
    }

    private void syncActiveContextFromHeldHands(Minecraft minecraft) {
        String mainRadioId = radioIdFromStack(minecraft.player.getMainHandItem());
        String offRadioId = radioIdFromStack(minecraft.player.getOffhandItem());

        if (activeHandheldRadioId.isBlank()) {
            if (!mainRadioId.isBlank()) {
                setHandheldContext(mainRadioId, InteractionHand.MAIN_HAND);
            } else if (!offRadioId.isBlank()) {
                setHandheldContext(offRadioId, InteractionHand.OFF_HAND);
            }
            return;
        }

        // Keep current session context while that radio still exists in inventory.
        if (isRadioInInventory(minecraft, activeHandheldRadioId)) {
            return;
        }

        if (!mainRadioId.isBlank()) {
            setHandheldContext(mainRadioId, InteractionHand.MAIN_HAND);
            return;
        }

        if (!offRadioId.isBlank()) {
            setHandheldContext(offRadioId, InteractionHand.OFF_HAND);
            return;
        }

        // No valid held context remains.
        activeHandheldRadioId = "";
    }

    private boolean isRadioInInventory(Minecraft minecraft, String radioId) {
        if (isExternalSession(radioId)) {
            return true;
        }
        return !findRadioStackById(minecraft, radioId).isEmpty();
    }

    private boolean isExternalSession(String radioId) {
        return radioId != null && !radioId.isBlank() && externalRadioIds.contains(radioId);
    }

    private void registerExternalContext(
            String radioId,
            int contraptionEntityId,
            BlockPos localPos,
            boolean inventoryPlayback,
            boolean manualControl
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        long nowTick = minecraft.level == null ? 0L : minecraft.level.getGameTime();
        ExternalRadioContext existing = externalContexts.get(radioId);
        if (existing != null && existing.matches(contraptionEntityId, localPos)) {
            if (existing.localPos != null
                    && existing.lastSeenGameTick > 0L
                    && nowTick > existing.lastSeenGameTick
                    && nowTick - existing.lastSeenGameTick >= EXTERNAL_REACQUIRE_RESYNC_MIN_MISSING_TICKS) {
                existing.needsAuthoritativeResync = true;
            }
            existing.firstMissingGameTick = -1L;
            existing.lastSeenGameTick = nowTick;
            existing.inventoryPlayback = inventoryPlayback;
            existing.manualControl = existing.manualControl || manualControl;
            externalRadioIds.add(radioId);
            return;
        }

        if (existing != null) {
            boolean existingIsSpatial = existing.localPos != null;
            boolean incomingIsSpatial = localPos != null;
            // Avoid replacing a contraption/block context with a generic listener context.
            if (existingIsSpatial && !incomingIsSpatial) {
                existing.lastSeenGameTick = nowTick;
                existing.firstMissingGameTick = -1L;
                existing.inventoryPlayback = existing.inventoryPlayback || inventoryPlayback;
                existing.manualControl = existing.manualControl || manualControl;
                externalRadioIds.add(radioId);
                return;
            }
        }

        ExternalRadioContext context = new ExternalRadioContext(contraptionEntityId, localPos, inventoryPlayback, manualControl);
        context.lastSeenGameTick = nowTick;
        externalContexts.put(radioId, context);
        externalRadioIds.add(radioId);
    }

    public void primeRuntimeStateForRadio(
            String radioId,
            String sessionId,
            long revision,
            String url,
            String title,
            String artist,
            String thumbnail,
            long positionMs,
            float volume,
            boolean playing
    ) {
        primeRuntimeStateForRadio(radioId, sessionId, revision, url, title, artist, thumbnail, positionMs, volume, playing, 0L, false, false);
    }

    public void primeRuntimeStateForRadio(
            String radioId,
            String url,
            String title,
            String artist,
            String thumbnail,
            long positionMs,
            float volume,
            boolean playing
    ) {
        primeRuntimeStateForRadio(radioId, radioId, -1L, url, title, artist, thumbnail, positionMs, volume, playing, 0L, false, false);
    }

    public void primeRuntimeStateForRadio(
            String radioId,
            String sessionId,
            long revision,
            String url,
            String title,
            String artist,
            String thumbnail,
            long positionMs,
            float volume,
            boolean playing,
            long serverSentAtMs,
            boolean forcePositionSync,
            boolean seekEvent
    ) {
        String safeRadioId = safeRadioId(radioId);
        if (safeRadioId.isBlank()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (!hasRelevantRuntimeSource(minecraft, safeRadioId)) {
            return;
        }
        HandheldSession session = handheldSessions.computeIfAbsent(safeRadioId, HandheldSession::new);
        String safeSessionId = safe(sessionId).isBlank() ? safeRadioId : safe(sessionId);
        if (!safeSessionId.equals(session.serverSessionId)) {
            session.serverSessionId = safeSessionId;
            session.lastServerRevision = -1L;
        }
        if (revision >= 0L) {
            if (session.lastServerRevision > revision) {
                return;
            }
            session.lastServerRevision = revision;
        }
        if (serverSentAtMs > 0L) {
            if (session.lastServerSentAtMs > 0L && serverSentAtMs < session.lastServerSentAtMs) {
                return;
            }
            session.lastServerSentAtMs = Math.max(session.lastServerSentAtMs, serverSentAtMs);
        }
        if (session.awaitingAuthoritativeExternalState) {
            session.awaitingAuthoritativeExternalState = false;
            // Force next movement sync compare against this authoritative baseline.
            session.lastExternalSyncKey = "";
        }
        session.awaitingAuthoritativeHandheldState = false;
        session.lastHandheldRuntimeStateRequestAtMs = 0L;
        applyRuntimeStateToSession(session, url, title, artist, thumbnail, positionMs, volume, playing, forcePositionSync, seekEvent);
    }

    public void primeRuntimeStateForRadio(
            String radioId,
            String url,
            String title,
            String artist,
            String thumbnail,
            long positionMs,
            float volume,
            boolean playing,
            long serverSentAtMs,
            boolean forcePositionSync,
            boolean seekEvent
    ) {
        primeRuntimeStateForRadio(
                radioId,
                radioId,
                -1L,
                url,
                title,
                artist,
                thumbnail,
                positionMs,
                volume,
                playing,
                serverSentAtMs,
                forcePositionSync,
                seekEvent
        );
    }

    private void applyRuntimeStateToSession(
            HandheldSession session,
            String url,
            String title,
            String artist,
            String thumbnail,
            long positionMs,
            float volume,
            boolean playing
    ) {
        applyRuntimeStateToSession(session, url, title, artist, thumbnail, positionMs, volume, playing, false, false);
    }

    private void applyRuntimeStateToSession(
            HandheldSession session,
            String url,
            String title,
            String artist,
            String thumbnail,
            long positionMs,
            float volume,
            boolean playing,
            boolean forcePositionSync,
            boolean seekEvent
    ) {
        if (session == null) {
            return;
        }

        String previousUrl = safe(session.url);
        session.url = safe(url);
        if (!sameTrack(previousUrl, session.url)) {
            session.lastKnownTrackDurationMs = -1L;
        }
        session.title = safe(title);
        session.artist = safe(artist);
        session.thumbnail = safe(thumbnail);
        session.volume = Mth.clamp(volume, 0f, 2f);
        session.pausedPositionMs = Math.max(0L, positionMs);
        long nowMs = System.currentTimeMillis();
        updateAuthoritativeTimeline(session, session.pausedPositionMs, playing, nowMs);
        session.pausedState = !playing && (!session.url.isBlank() || !session.title.isBlank());
        session.intendedPlaying = playing;

        if (session.url.isBlank()) {
            stopSessionPlayback(session);
            session.pausedState = false;
            clearAuthoritativeTimeline(session);
            return;
        }

        boolean shouldBePositional = shouldUsePositionalChannel(Minecraft.getInstance(), session);
        if (playing && !ensureSessionChannel(Minecraft.getInstance(), session, session.pausedPositionMs, false)) {
            return;
        }

        if (session.channel != null) {
            reconfigureSessionChannelMode(session, shouldBePositional);
            if (session.channel == null) {
                return;
            }
            if (!session.title.isBlank()) {
                session.channel.setDisplayTitle(session.title);
            }
            boolean sameTrack = sameTrack(session.url, session.channel.getCurrentUrl());
            if (!sameTrack) {
                if (!shouldApplyRemotePositionCorrection(session) || forcePositionSync) {
                    sameTrack = ensureSessionChannel(Minecraft.getInstance(), session, session.pausedPositionMs, true);
                }
            }

            if (sameTrack && (shouldApplyRemotePositionCorrection(session) || forcePositionSync || seekEvent)) {
                long localPos = session.channel.getEstimatedPositionMs();
                long targetPos = Math.max(0L, session.pausedPositionMs);
                long drift = Math.abs(localPos - targetPos);
                long syncNowMs = System.currentTimeMillis();
                boolean cooldownPassed = syncNowMs - session.lastRemoteSeekAtMs >= REMOTE_SEEK_COOLDOWN_MS;
                if (seekEvent && drift >= REMOTE_FORCE_SYNC_MIN_DRIFT_MS) {
                    session.channel.seekTo(targetPos, !playing);
                    session.lastRemoteSeekAtMs = syncNowMs;
                } else if (forcePositionSync && !playing && drift >= REMOTE_PAUSED_DRIFT_CORRECTION_MS) {
                    // Force sync for paused snapshots should align exactly.
                    session.channel.seekTo(targetPos, true);
                    session.lastRemoteSeekAtMs = syncNowMs;
                } else if (!playing && drift >= REMOTE_PAUSED_DRIFT_CORRECTION_MS && cooldownPassed) {
                    session.channel.seekTo(targetPos, true);
                    session.lastRemoteSeekAtMs = syncNowMs;
                }
            }

            if (playing) {
                if (session.channel.isPaused() || !session.channel.isPlaying()) {
                    session.channel.resume();
                }
                session.pausedState = false;
                recordSessionPlaybackProgress(session, session.channel.getEstimatedPositionMs(), System.currentTimeMillis());
            } else {
                if (!session.channel.isPaused()) {
                    session.channel.pause();
                }
                session.pausedState = true;
            }
        }
    }

    private boolean shouldApplyRemotePositionCorrection(HandheldSession session) {
        if (session == null || session.radioId == null || session.radioId.isBlank()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        // If this client owns the radio item, avoid forcing seeks from echoed server snapshots.
        return findRadioStackById(minecraft, session.radioId).isEmpty();
    }

    private boolean shouldUsePositionalChannel(Minecraft minecraft, HandheldSession session) {
        if (session == null || session.radioId == null || session.radioId.isBlank()) {
            return false;
        }
        if (!isExternalSession(session.radioId)) {
            return false;
        }
        if (minecraft == null || minecraft.player == null) {
            return true;
        }
        // Local radios should remain listener-relative even if another endpoint shares the same session id.
        return findRadioStackById(minecraft, session.radioId).isEmpty();
    }

    private void pruneDetachedRuntimeSessions(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null || handheldSessions.isEmpty()) {
            return;
        }
        List<String> detached = new ArrayList<>();
        for (Map.Entry<String, HandheldSession> entry : handheldSessions.entrySet()) {
            String radioId = entry.getKey();
            if (radioId == null || radioId.isBlank()) {
                detached.add(radioId == null ? "" : radioId);
                continue;
            }
            if (hasRelevantRuntimeSource(minecraft, radioId)) {
                continue;
            }
            HandheldSession session = entry.getValue();
            if (session == null) {
                detached.add(radioId);
                continue;
            }
            if (session.channel == null
                    && safe(session.url).isBlank()
                    && !session.intendedPlaying
                    && !session.pausedState) {
                continue;
            }
            detached.add(radioId);
        }
        for (String radioId : detached) {
            clearDetachedRuntimeSession(radioId);
        }
    }

    private void clearDetachedRuntimeSession(String radioId) {
        String safeRadioId = safeRadioId(radioId);
        if (safeRadioId.isBlank()) {
            handheldSessions.remove(radioId == null ? "" : radioId);
            return;
        }
        HandheldSession session = handheldSessions.get(safeRadioId);
        if (session == null) {
            return;
        }
        if (session.channel != null) {
            session.pausedPositionMs = Math.max(0L, session.channel.getEstimatedPositionMs());
        }
        stopSessionPlayback(session);
        session.title = "";
        session.artist = "";
        session.thumbnail = "";
        session.url = "";
        session.lastKnownTrackDurationMs = -1L;
        session.pausedState = false;
        session.pausedPositionMs = 0L;
        session.intendedPlaying = false;
        session.lastExternalSyncKey = "";
        session.awaitingAuthoritativeHandheldState = false;
        session.lastHandheldRuntimeStateRequestAtMs = 0L;
        clearAuthoritativeTimeline(session);
        if (!isExternalSession(safeRadioId)) {
            handheldSessions.remove(safeRadioId);
        }
    }

    private boolean shouldKeepSessionChannelActive(Minecraft minecraft, HandheldSession session) {
        if (session == null || safe(session.url).isBlank()) {
            return false;
        }
        boolean activeSession = session.radioId != null && session.radioId.equals(activeHandheldRadioId);
        if (!session.intendedPlaying) {
            // Only keep paused channels for the active local handheld session.
            return activeSession && !isExternalSession(session.radioId);
        }
        if (!isExternalSession(session.radioId)) {
            return activeSession;
        }
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            return false;
        }
        // Remote contexts that are currently inaudible should not keep decoding/holding
        // audio sources; they can be recreated instantly when they become audible again.
        return resolveExternalSessionVolume(session, session.radioId) > 0.0001f;
    }

    private Vec3 resolveExternalSourcePosition(String radioId) {
        return resolveExternalSourcePosition(externalContexts.get(radioId));
    }

    private Vec3 resolveExternalSourcePosition(ExternalRadioContext context) {
        if (context == null) {
            return Vec3.ZERO;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return context.lastKnownPos;
        }

        Entity entity = minecraft.level.getEntity(context.contraptionEntityId);
        if (entity == null) {
            return context.lastKnownPos;
        }

        Vec3 position = entity.position();
        if (context.localPos != null) {
            Vec3 localCenter = Vec3.atCenterOf(context.localPos);
            float partialTicks = minecraft.getTimer().getGameTimeDeltaPartialTick(true);
            try {
                Object transformed = entity.getClass()
                        .getMethod("toGlobalVector", Vec3.class, float.class)
                        .invoke(entity, localCenter, partialTicks);
                if (transformed instanceof Vec3 transformedVec) {
                    position = transformedVec;
                }
            } catch (ReflectiveOperationException ignored) {
                try {
                    Object transformed = entity.getClass()
                            .getMethod("toGlobalVector", Vec3.class, float.class, boolean.class)
                            .invoke(entity, localCenter, partialTicks, false);
                    if (transformed instanceof Vec3 transformedVec) {
                        position = transformedVec;
                    }
                } catch (ReflectiveOperationException ignoredAgain) {
                    position = entity.position().add(localCenter);
                }
            }
        }

        long nowNanos = System.nanoTime();
        Vec3 smoothed = context.smoothedPos;
        if (smoothed == null) {
            context.smoothedPos = position;
            context.lastKnownPos = position;
            context.lastSmoothNanos = nowNanos;
            return position;
        }

        if (smoothed.distanceToSqr(position) >= EXTERNAL_POSITION_TELEPORT_SNAP_DISTANCE_SQR) {
            context.smoothedPos = position;
            context.lastKnownPos = position;
            context.lastSmoothNanos = nowNanos;
            return position;
        }

        long lastSmoothNanos = context.lastSmoothNanos;
        context.lastSmoothNanos = nowNanos;
        if (lastSmoothNanos <= 0L) {
            context.smoothedPos = position;
            context.lastKnownPos = position;
            return position;
        }

        double dtSeconds = (nowNanos - lastSmoothNanos) / 1_000_000_000.0D;
        dtSeconds = Math.max(0.0D, Math.min(EXTERNAL_POSITION_MAX_DT_SECONDS, dtSeconds));
        double alpha = 1.0D - Math.exp(-EXTERNAL_POSITION_SMOOTHING_HZ * dtSeconds);
        alpha = Math.max(0.0D, Math.min(1.0D, alpha));
        Vec3 filtered = smoothed.lerp(position, alpha);
        context.smoothedPos = filtered;
        context.lastKnownPos = filtered;
        return filtered;
    }

    private void beginAwaitingAuthoritativeExternalState(HandheldSession session) {
        if (session == null || session.awaitingAuthoritativeExternalState) {
            return;
        }
        session.awaitingAuthoritativeExternalState = true;
        session.lastServerRevision = -1L;
        session.lastServerSentAtMs = 0L;
        session.lastExternalSyncKey = "";
    }

    private void beginAwaitingAuthoritativeHandheldState(HandheldSession session) {
        if (session == null) {
            return;
        }
        session.awaitingAuthoritativeHandheldState = true;
        session.lastHandheldRuntimeStateRequestAtMs = 0L;
    }

    private void requestInitialHandheldRuntimeState(HandheldSession session, boolean immediate) {
        if (session == null || session.radioId == null || session.radioId.isBlank() || !session.awaitingAuthoritativeHandheldState) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (!immediate && nowMs - session.lastHandheldRuntimeStateRequestAtMs < HANDHELD_INITIAL_SYNC_RETRY_MS) {
            return;
        }
        session.lastHandheldRuntimeStateRequestAtMs = nowMs;
        ModNetworking.requestRadioState(
                session.radioId,
                net.jacobwasbeast.mediaradio.network.message.ServerboundRequestRadioStateMessage.Context.HANDHELD
        );
    }

    private void persistRuntimeToSession(Minecraft minecraft, HandheldSession session) {
        if (session == null || minecraft.player == null || session.radioId.isBlank()) {
            return;
        }
        // Runtime progression is server-authoritative. Client should only send explicit commands.
    }

    private void sendHandheldControlCommand(
            HandheldSession session,
            ServerboundRadioControlMessage.Action action,
            String url,
            String title,
            String artist,
            String thumbnail,
            long positionMs,
            long trackDurationMs
    ) {
        if (session == null || session.radioId == null || session.radioId.isBlank() || action == null) {
            return;
        }
        ModNetworking.sendBlockRadioControl(new ServerboundRadioControlMessage(
                null,
                session.radioId,
                ServerboundRadioControlMessage.Context.HANDHELD,
                action,
                safe(url),
                safe(title),
                safe(artist),
                safe(thumbnail),
                Mth.clamp(session.volume, 0f, 2f),
                Math.max(0L, positionMs),
                trackDurationMs,
                Math.max(-1L, session.lastServerRevision)
        ));
    }

    private ItemStack findRadioStackById(Minecraft minecraft, String targetRadioId) {
        if (targetRadioId == null || targetRadioId.isBlank()) {
            return ItemStack.EMPTY;
        }

        ItemStack main = minecraft.player.getMainHandItem();
        if (main.is(ModItems.RADIO_ITEM) && targetRadioId.equals(RadioItem.getRadioId(main))) {
            return main;
        }

        ItemStack off = minecraft.player.getOffhandItem();
        if (off.is(ModItems.RADIO_ITEM) && targetRadioId.equals(RadioItem.getRadioId(off))) {
            return off;
        }

        for (ItemStack stack : minecraft.player.getInventory().items) {
            if (!stack.is(ModItems.RADIO_ITEM)) {
                continue;
            }
            if (targetRadioId.equals(RadioItem.getRadioId(stack))) {
                return stack;
            }
        }
        for (ItemStack stack : minecraft.player.getInventory().offhand) {
            if (stack.is(ModItems.RADIO_ITEM) && targetRadioId.equals(RadioItem.getRadioId(stack))) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private boolean isRadioHeldInHands(Minecraft minecraft, String radioId) {
        return !getHeldRadioStackById(minecraft, radioId).isEmpty();
    }

    private boolean isRadioHeldInPlaceMode(Minecraft minecraft, String radioId) {
        ItemStack heldStack = getHeldRadioStackById(minecraft, radioId);
        return !heldStack.isEmpty() && RadioItem.isPlaceMode(heldStack);
    }

    private ItemStack getHeldRadioStackById(Minecraft minecraft, String radioId) {
        if (minecraft.player == null || radioId == null || radioId.isBlank()) {
            return ItemStack.EMPTY;
        }
        ItemStack main = minecraft.player.getMainHandItem();
        if (main.is(ModItems.RADIO_ITEM) && radioId.equals(RadioItem.getRadioId(main))) {
            return main;
        }
        ItemStack off = minecraft.player.getOffhandItem();
        if (off.is(ModItems.RADIO_ITEM) && radioId.equals(RadioItem.getRadioId(off))) {
            return off;
        }
        return ItemStack.EMPTY;
    }

    private String radioIdFromStack(ItemStack stack) {
        if (stack == null || !stack.is(ModItems.RADIO_ITEM)) {
            return "";
        }
        return safe(RadioItem.getRadioId(stack));
    }

    private HandheldSession activeSession() {
        if (activeHandheldRadioId == null || activeHandheldRadioId.isBlank()) {
            return null;
        }
        return handheldSessions.get(activeHandheldRadioId);
    }

    private HandheldSession activeOrCreateSession() {
        String radioId = activeHandheldRadioId;
        if (radioId == null || radioId.isBlank()) {
            radioId = safeRadioId(ClientMediaRepository.getInstance().getActiveRadioId());
            if (radioId.isBlank()) {
                return null;
            }
            activeHandheldRadioId = radioId;
        }
        return handheldSessions.computeIfAbsent(radioId, HandheldSession::new);
    }

    private HandheldSession readSession() {
        HandheldSession active = activeSession();
        if (active != null) {
            return active;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            String mainId = radioIdFromStack(minecraft.player.getMainHandItem());
            if (!mainId.isBlank()) {
                return handheldSessions.get(mainId);
            }
            String offId = radioIdFromStack(minecraft.player.getOffhandItem());
            if (!offId.isBlank()) {
                return handheldSessions.get(offId);
            }
        }

        return null;
    }

    private boolean sameTrack(String left, String right) {
        return trackSyncKey(left).equals(trackSyncKey(right));
    }

    private String trackSyncKey(String url) {
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

    private String queryParam(String rawQuery, String key) {
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

    private String safeRadioId(String value) {
        return value == null ? "" : value;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void scanBlockRadios(Minecraft minecraft) {
        Set<BlockPos> activePositions = new HashSet<>();
        List<BlockRadioCandidate> candidates = new ArrayList<>();
        Set<String> nearbyRadioIds = new HashSet<>();
        BlockPos playerPos = minecraft.player.blockPosition();
        BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();
        int horizontalRange = 30;
        int verticalRange = 16;
        int minY = Math.max(minecraft.level.getMinBuildHeight(), playerPos.getY() - verticalRange);
        int maxY = Math.min(minecraft.level.getMaxBuildHeight() - 1, playerPos.getY() + verticalRange);

        for (int x = playerPos.getX() - horizontalRange; x <= playerPos.getX() + horizontalRange; x++) {
            for (int z = playerPos.getZ() - horizontalRange; z <= playerPos.getZ() + horizontalRange; z++) {
                for (int y = minY; y <= maxY; y++) {
                    mutableBlockPos.set(x, y, z);
                    if (!(minecraft.level.getBlockEntity(mutableBlockPos) instanceof RadioBlockEntity radioBlockEntity)) {
                        continue;
                    }
                    String radioId = safeRadioId(radioBlockEntity.getRadioId());
                    if (!radioId.isBlank()) {
                        nearbyRadioIds.add(radioId);
                    }

                    BlockPos blockPos = radioBlockEntity.getBlockPos();
                    if (!radioBlockEntity.isPlaying() || radioBlockEntity.getMediaUrl().isBlank()) {
                        cleanupBlockChannel(blockPos);
                        continue;
                    }

                    double distanceSqr = minecraft.player.distanceToSqr(
                            blockPos.getX() + 0.5D,
                            blockPos.getY() + 0.5D,
                            blockPos.getZ() + 0.5D
                    );
                    candidates.add(new BlockRadioCandidate(blockPos.immutable(), radioBlockEntity, distanceSqr));
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(BlockRadioCandidate::distanceSqr));
        int remainingBudget = MAX_ACTIVE_BLOCK_CHANNELS;
        for (BlockRadioCandidate candidate : candidates) {
            BlockPos blockPos = candidate.blockPos();
            RadioBlockEntity radioBlockEntity = candidate.blockEntity();
            if (radioBlockEntity == null || radioBlockEntity.isRemoved() || !radioBlockEntity.isPlaying() || radioBlockEntity.getMediaUrl().isBlank()) {
                cleanupBlockChannel(blockPos);
                continue;
            }

            float channelVolume = resolveBlockChannelVolume(radioBlockEntity.getVolume());
            boolean audible = candidate.distanceSqr() <= BLOCK_CHANNEL_ACTIVE_DISTANCE_SQR && channelVolume > MIN_ACTIVE_CHANNEL_GAIN;
            if (!audible || remainingBudget <= 0) {
                cleanupBlockChannel(blockPos);
                continue;
            }

            remainingBudget--;
            activePositions.add(blockPos);

            RadioAudioChannel channel = blockChannels.computeIfAbsent(blockPos,
                    ignored -> new RadioAudioChannel(
                            true,
                            () -> Vec3.atCenterOf(blockPos),
                            () -> resolveBlockChannelVolume(radioBlockEntity.getVolume()),
                            BLOCK_CHANNEL_MAX_DISTANCE));

            long targetPosition = radioBlockEntity.getPlaybackPositionMs();
            int seekVersion = radioBlockEntity.getSeekVersion();
            Integer previousSeekVersion = blockSeekVersions.get(blockPos);
            boolean seekVersionChanged = previousSeekVersion == null || previousSeekVersion != seekVersion;
            long delta = Math.abs(channel.getEstimatedPositionMs() - targetPosition);
            if (!radioBlockEntity.getMediaUrl().equals(channel.getCurrentUrl()) || delta > 2500L || seekVersionChanged) {
                channel.setDisplayTitle(radioBlockEntity.getMediaTitle());
                channel.play(radioBlockEntity.getMediaUrl(), targetPosition);
            }
            blockSeekVersions.put(blockPos, seekVersion);
        }

        Set<BlockPos> stale = new HashSet<>(blockChannels.keySet());
        stale.removeAll(activePositions);
        for (BlockPos stalePos : stale) {
            cleanupBlockChannel(stalePos);
        }
        nearbyBlockRadioIds.clear();
        nearbyBlockRadioIds.addAll(nearbyRadioIds);
    }

    private void cleanupBlockChannel(BlockPos blockPos) {
        if (blockPos == null) {
            return;
        }
        RadioAudioChannel channel = blockChannels.remove(blockPos);
        if (channel != null) {
            channel.stop();
        }
        blockSeekVersions.remove(blockPos);
        blockRuntimeSyncKeys.remove(blockPos);
        blockRuntimeSyncAtMs.remove(blockPos);
        blockSilentSinceAtMs.remove(blockPos);
        blockRecoveryAtMs.remove(blockPos);
    }

    private void tickBlockChannels(Minecraft minecraft) {
        if (minecraft.level == null) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        Set<BlockPos> staleChannels = new HashSet<>();
        for (Map.Entry<BlockPos, RadioAudioChannel> entry : blockChannels.entrySet()) {
            BlockPos blockPos = entry.getKey();
            RadioAudioChannel channel = entry.getValue();
            if (!(minecraft.level.getBlockEntity(blockPos) instanceof RadioBlockEntity radioBlockEntity)) {
                staleChannels.add(blockPos);
                continue;
            }
            channel.tick();
            syncBlockRuntimeStateToServer(minecraft, blockPos, channel);
            boolean ended = channel.consumeNaturalEnd();
            if (!ended && hasExceededTrackDuration(channel)) {
                channel.stop();
                ended = true;
            }
            if (!ended) {
                if (radioBlockEntity.isPlaying() && !safe(radioBlockEntity.getMediaUrl()).isBlank()) {
                    if (channel.isPlaying() || channel.isPaused()) {
                        blockSilentSinceAtMs.remove(blockPos);
                    } else {
                        long silentSince = blockSilentSinceAtMs.computeIfAbsent(blockPos, ignored -> nowMs);
                        long lastRecoveryAt = blockRecoveryAtMs.getOrDefault(blockPos, 0L);
                        if (nowMs - silentSince >= CHANNEL_STALL_RECOVERY_THRESHOLD_MS
                                && nowMs - lastRecoveryAt >= CHANNEL_SOFT_RECOVERY_COOLDOWN_MS) {
                            long resumePosition = Math.max(channel.getEstimatedPositionMs(), Math.max(0L, radioBlockEntity.getPlaybackPositionMs()));
                            channel.setDisplayTitle(radioBlockEntity.getMediaTitle());
                            channel.play(radioBlockEntity.getMediaUrl(), resumePosition);
                            blockSeekVersions.put(blockPos, radioBlockEntity.getSeekVersion());
                            blockRecoveryAtMs.put(blockPos, nowMs);
                            blockSilentSinceAtMs.put(blockPos, nowMs);
                        }
                    }
                }
                continue;
            }
            blockSilentSinceAtMs.remove(blockPos);
            if (radioBlockEntity.isPlaying() && !safe(radioBlockEntity.getMediaUrl()).isBlank()) {
                channel.setDisplayTitle(radioBlockEntity.getMediaTitle());
                channel.play(radioBlockEntity.getMediaUrl(), Math.max(0L, radioBlockEntity.getPlaybackPositionMs()));
                blockSeekVersions.put(blockPos, radioBlockEntity.getSeekVersion());
                continue;
            }
            staleChannels.add(blockPos);
        }
        for (BlockPos blockPos : staleChannels) {
            cleanupBlockChannel(blockPos);
        }
    }

    private boolean hasExceededTrackDuration(RadioAudioChannel channel) {
        long duration = channel.getTrackDurationMs();
        if (duration <= 0L) {
            return false;
        }
        if (channel.isPaused()) {
            return false;
        }
        long position = channel.getEstimatedPositionMs();
        return position + 250L >= duration;
    }

    private void syncBlockRuntimeStateToServer(Minecraft minecraft, BlockPos blockPos, RadioAudioChannel channel) {
        if (minecraft.level == null || minecraft.player == null || blockPos == null || channel == null) {
            return;
        }
        if (!(minecraft.level.getBlockEntity(blockPos) instanceof RadioBlockEntity radioBlockEntity)) {
            blockRuntimeSyncKeys.remove(blockPos);
            blockRuntimeSyncAtMs.remove(blockPos);
            return;
        }
        if (!radioBlockEntity.isPlaying() || radioBlockEntity.getMediaUrl().isBlank()) {
            blockRuntimeSyncKeys.remove(blockPos);
            blockRuntimeSyncAtMs.remove(blockPos);
            return;
        }

        long durationMs = channel.getTrackDurationMs();
        if (durationMs <= 0L || durationMs == Long.MAX_VALUE) {
            return;
        }
        long positionMs = Math.max(0L, channel.getEstimatedPositionMs());
        String radioId = safe(radioBlockEntity.getRadioId());
        if (radioId.isBlank()) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        long positionBucket = positionMs / 500L;
        long durationBucket = durationMs / 1_000L;
        String syncKey = radioId + "|" + safe(radioBlockEntity.getMediaUrl()) + "|" + positionBucket + "|" + durationBucket;
        String lastKey = blockRuntimeSyncKeys.get(blockPos);
        Long lastSentAtMs = blockRuntimeSyncAtMs.get(blockPos);
        boolean isDue = lastSentAtMs == null || nowMs - lastSentAtMs >= BLOCK_RUNTIME_SYNC_INTERVAL_MS;
        if (!isDue && syncKey.equals(lastKey)) {
            return;
        }

        ModNetworking.sendBlockRadioControl(new ServerboundRadioControlMessage(
                blockPos,
                radioId,
                ServerboundRadioControlMessage.Action.SYNC_RUNTIME,
                safe(radioBlockEntity.getMediaUrl()),
                safe(radioBlockEntity.getMediaTitle()),
                safe(radioBlockEntity.getMediaArtist()),
                safe(radioBlockEntity.getMediaThumbnail()),
                radioBlockEntity.getVolume(),
                positionMs,
                durationMs
        ));
        blockRuntimeSyncKeys.put(blockPos, syncKey);
        blockRuntimeSyncAtMs.put(blockPos, nowMs);
    }

    private float resolveBlockChannelVolume(float sourceVolume) {
        return Mth.clamp(sourceVolume * ClientAudioSettings.get().blockRadioVolume(), 0f, 2f);
    }

    private static class HandheldSession {
        private final String radioId;
        private RadioAudioChannel channel;
        private float volume = 1.0f;
        private InteractionHand preferredHand = InteractionHand.MAIN_HAND;
        private String title = "";
        private String artist = "";
        private String thumbnail = "";
        private String url = "";
        private boolean pausedState;
        private long pausedPositionMs;
        private long lastKnownTrackDurationMs = -1L;
        private boolean intendedPlaying;
        private String lastExternalSyncKey = "";
        private String serverSessionId = "";
        private long lastServerRevision = -1L;
        private long lastRemoteSeekAtMs;
        private long lastServerSentAtMs;
        private boolean awaitingAuthoritativeHandheldState;
        private long lastHandheldRuntimeStateRequestAtMs;
        private long authoritativePositionMs;
        private long authoritativePositionAppliedAtMs;
        private boolean authoritativePlaying;
        private long lastObservedChannelPositionMs;
        private long lastObservedChannelProgressAtMs;
        private long lastSoftRecoveryAttemptAtMs;
        private long lastHardRecoveryAttemptAtMs;
        private int seekSerial;
        private boolean awaitingAuthoritativeExternalState;

        private HandheldSession(String radioId) {
            this.radioId = radioId;
        }
    }

    private static class ExternalRadioContext {
        private final int contraptionEntityId;
        private final BlockPos localPos;
        private volatile boolean inventoryPlayback;
        private volatile boolean manualControl;
        private volatile boolean needsAuthoritativeResync;
        private volatile Vec3 lastKnownPos = Vec3.ZERO;
        private volatile Vec3 smoothedPos;
        private volatile long lastSmoothNanos;
        private volatile long lastSeenGameTick;
        private volatile long firstMissingGameTick = -1L;

        private ExternalRadioContext(int contraptionEntityId, BlockPos localPos, boolean inventoryPlayback, boolean manualControl) {
            this.contraptionEntityId = contraptionEntityId;
            this.localPos = localPos;
            this.inventoryPlayback = inventoryPlayback;
            this.manualControl = manualControl;
        }

        private boolean matches(int entityId, BlockPos blockPos) {
            if (this.contraptionEntityId != entityId) {
                return false;
            }
            if (this.localPos == null) {
                return blockPos == null;
            }
            return this.localPos.equals(blockPos);
        }
    }

    private record BlockRadioCandidate(
            BlockPos blockPos,
            RadioBlockEntity blockEntity,
            double distanceSqr
    ) {
    }

    private record ExternalSessionCandidate(
            String radioId,
            HandheldSession session,
            double distanceSqr
    ) {
    }

    public record HandheldRenderState(
            String url,
            String title,
            String artist,
            String thumbnail,
            long positionMs,
            long durationMs,
            float volume,
            boolean playing,
            boolean paused
    ) {
    }
}
