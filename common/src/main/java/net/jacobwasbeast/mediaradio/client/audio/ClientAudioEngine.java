package net.jacobwasbeast.mediaradio.client.audio;

import net.jacobwasbeast.mediaradio.block.entity.RadioBlockEntity;
import net.jacobwasbeast.mediaradio.client.data.ClientMediaRepository;
import net.jacobwasbeast.mediaradio.client.screen.RadioScreen;
import net.jacobwasbeast.mediaradio.item.RadioItem;
import net.jacobwasbeast.mediaradio.network.ModNetworking;
import net.jacobwasbeast.mediaradio.network.message.ServerboundHandheldStateMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundRadioControlMessage;
import net.jacobwasbeast.mediaradio.registry.ModItems;
import net.jacobwasbeast.mediaradio.server.SharedMediaSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClientAudioEngine {

    private static final ClientAudioEngine INSTANCE = new ClientAudioEngine();
    private static final double EXTERNAL_POSITION_SMOOTHING_HZ = 12.0D;
    private static final double EXTERNAL_POSITION_TELEPORT_SNAP_DISTANCE_SQR = 64.0D;
    private static final double EXTERNAL_POSITION_MAX_DT_SECONDS = 0.1D;
    private static final long REMOTE_PLAYING_DRIFT_CORRECTION_MS = 12_000L;
    private static final long REMOTE_PAUSED_DRIFT_CORRECTION_MS = 500L;
    private static final long REMOTE_FORCE_SYNC_MIN_DRIFT_MS = 250L;
    private static final long REMOTE_SEEK_EVENT_MIN_DRIFT_MS = 1_250L;
    private static final long REMOTE_SEEK_COOLDOWN_MS = 10_000L;

    private final Map<BlockPos, RadioAudioChannel> blockChannels = new ConcurrentHashMap<>();
    private final Map<BlockPos, Integer> blockSeekVersions = new ConcurrentHashMap<>();
    private final Map<BlockPos, Integer> blockEndSuppressTicks = new ConcurrentHashMap<>();

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

        tickBlockEndSuppression();
        pruneStaleExternalContexts(minecraft);
        tickHandheld(minecraft);
        tickNonActiveExternalSessions(minecraft);

        blockScanTicker++;
        if (blockScanTicker % 5 == 0) {
            scanBlockRadios(minecraft);
        }

        tickBlockChannels(minecraft);
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
        playSession(session, url, positionMs, displayTitle, artist, thumbnail);

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            persistRuntimeToSession(minecraft, session);
        }
    }

    public void togglePauseHandheld() {
        HandheldSession session = activeSession();
        if (session == null) {
            return;
        }

        if (session.channel == null) {
            if (session.pausedState && !session.url.isBlank()) {
                session.intendedPlaying = true;
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.player != null) {
                    resumeSessionIfHeld(minecraft, session);
                    persistRuntimeToSession(minecraft, session);
                }
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

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            persistRuntimeToSession(minecraft, session);
        }
    }

    public void stopHandheld() {
        HandheldSession session = activeSession();
        if (session == null) {
            return;
        }
        stopSessionPlayback(session);
        session.intendedPlaying = false;
        session.pausedState = false;
        session.pausedPositionMs = 0L;
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
        session.intendedPlaying = false;
        session.pausedState = false;
        session.pausedPositionMs = 0L;
        session.lastSyncedRuntimeKey = "";
    }

    public void setHandheldVolume(float volume) {
        HandheldSession session = activeOrCreateSession();
        if (session == null) {
            return;
        }
        session.volume = Mth.clamp(volume, 0f, 2f);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            persistRuntimeToSession(minecraft, session);
        }
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
        if (session.channel == null) {
            session.pausedPositionMs = clamped;
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                persistRuntimeToSession(minecraft, session);
            }
            return;
        }

        session.channel.seekTo(clamped, session.channel.isPaused());
        session.pausedPositionMs = clamped;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            persistRuntimeToSession(minecraft, session);
        }
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
        long duration = -1L;
        boolean playing = false;
        boolean paused = session.pausedState;

        if (session.channel != null) {
            title = session.channel.getDisplayTitle();
            position = session.channel.getEstimatedPositionMs();
            duration = session.channel.getTrackDurationMs();
            playing = session.channel.isPlaying();
            paused = session.channel.isPaused();
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
            if (previous != null && previous.channel != null && previous.channel.isPlaying()) {
                previous.pausedPositionMs = previous.channel.getEstimatedPositionMs();
                previous.pausedState = true;
                // Keep intent so it resumes when switching back to that radio.
                previous.intendedPlaying = true;
                previous.channel.pause();
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
    }

    public void setExternalContext(String radioId, int contraptionEntityId, BlockPos localPos) {
        String safeRadioId = safeRadioId(radioId);
        if (safeRadioId.isBlank()) {
            return;
        }
        registerExternalContext(safeRadioId, contraptionEntityId, localPos);
        HandheldSession session = handheldSessions.computeIfAbsent(safeRadioId, HandheldSession::new);
        if (session != null) {
            reconfigureSessionChannelMode(session, true);
        }
    }

    public boolean hasExternalContext(String radioId) {
        return isExternalSession(radioId);
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
        if (session != null) {
            reconfigureSessionChannelMode(session, false);
        }
        externalContexts.remove(safeRadioId);
        externalRadioIds.remove(safeRadioId);
        Minecraft minecraft = Minecraft.getInstance();
        if (session != null && (minecraft.player == null || findRadioStackById(minecraft, safeRadioId).isEmpty())) {
            handheldSessions.remove(safeRadioId);
        }
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
            reconfigureSessionChannelMode(session, externalContexts.get(session.radioId) != null);
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
        registerExternalContext(safeRadioId, contraptionEntityId, localPos);
        if (!wasExternal) {
            ModNetworking.requestRadioState(safeRadioId);
        }
        HandheldSession session = handheldSessions.computeIfAbsent(safeRadioId, HandheldSession::new);

        String stateKey = safe(url) + "|" + safe(title) + "|" + safe(artist) + "|" + safe(thumbnail) + "|"
                + Mth.clamp(volume, 0f, 2f) + "|" + playing + "|" + contraptionEntityId + "|"
                + (localPos == null ? "null" : localPos.toShortString()) + "|"
                + (playing ? "moving" : Math.max(0L, positionMs) / 250L);
        if (stateKey.equals(session.lastExternalSyncKey)) {
            return;
        }

        applyRuntimeStateToSession(session, url, title, artist, thumbnail, positionMs, volume, playing);
        session.lastExternalSyncKey = stateKey;
    }

    public void stopAll() {
        for (HandheldSession session : handheldSessions.values()) {
            stopSessionPlayback(session);
        }
        handheldSessions.clear();
        externalRadioIds.clear();
        externalContexts.clear();
        activeHandheldRadioId = "";
        activeHandheldHand = InteractionHand.MAIN_HAND;

        blockChannels.values().forEach(RadioAudioChannel::stop);
        blockChannels.clear();
        blockSeekVersions.clear();
        blockEndSuppressTicks.clear();
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

        HandheldSession session = activeSession();
        if (session == null) {
            return;
        }
        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        boolean blockScreenOpen = minecraft.screen instanceof RadioScreen radioScreen && radioScreen.isBlockModeScreen();
        if (!blockScreenOpen && !session.radioId.equals(repository.getActiveRadioId())) {
            repository.setActiveRadioId(session.radioId);
        }

        // While the radio is in block placement mode, keep playback paused locally.
        // We still preserve intendedPlaying so placing the radio can resume playback.
        if (isRadioHeldInPlaceMode(minecraft, session.radioId)) {
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

        if (session.channel == null) {
            if (session.intendedPlaying && !session.url.isBlank()) {
                resumeSessionIfHeld(minecraft, session);
            }
            persistRuntimeToSession(minecraft, session);
            return;
        }

        if (session.channel.isPaused() && session.intendedPlaying) {
            session.channel.resume();
            session.pausedState = false;
        }

        session.channel.tick();

        boolean ended = session.channel.consumeNaturalEnd();
        if (!ended && hasExceededTrackDuration(session.channel)) {
            session.channel.stop();
            ended = true;
        }
        if (!ended) {
            persistRuntimeToSession(minecraft, session);
            return;
        }

        String previousActiveRadioId = repository.getActiveRadioId();
        boolean switchedContext = !session.radioId.equals(previousActiveRadioId);
        if (switchedContext) {
            repository.setActiveRadioId(session.radioId);
        }
        SharedMediaSnapshot.MediaEntry next;
        try {
            alignQueueIndexToCurrentUrl(repository, session.url);
            next = repository.nextQueueEntry();
        } finally {
            if (switchedContext) {
                repository.setActiveRadioId(previousActiveRadioId);
            }
        }
        if (next != null && next.url != null && !next.url.isBlank()) {
            playSession(session, next.url, 0L, next.title, next.artist, next.thumbnail);
        } else {
            stopSessionPlayback(session);
            session.url = "";
            session.title = "";
            session.artist = "";
            session.thumbnail = "";
            session.pausedPositionMs = 0L;
            session.pausedState = false;
            session.intendedPlaying = false;
        }
        persistRuntimeToSession(minecraft, session);
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
            orphaned.pausedState = false;
            orphaned.pausedPositionMs = 0L;
            orphaned.intendedPlaying = false;
            orphaned.lastSyncedRuntimeKey = "";
            orphaned.lastExternalSyncKey = "";
            if (radioId.equals(activeHandheldRadioId)) {
                activeHandheldRadioId = "";
            }
        }
    }

    private void tickNonActiveExternalSessions(Minecraft minecraft) {
        for (String radioId : externalRadioIds) {
            if (radioId == null || radioId.isBlank() || radioId.equals(activeHandheldRadioId)) {
                continue;
            }
            HandheldSession session = handheldSessions.get(radioId);
            if (session == null || session.channel == null) {
                continue;
            }
            session.channel.tick();
            boolean ended = session.channel.consumeNaturalEnd();
            if (!ended && hasExceededTrackDuration(session.channel)) {
                session.channel.stop();
                ended = true;
            }
            if (ended) {
                stopSessionPlayback(session);
                session.intendedPlaying = false;
                session.pausedState = false;
            }
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
                context.lastSeenGameTick = now;
                continue;
            }
            if (now - context.lastSeenGameTick > 40L) {
                stale.add(radioId);
            }
        }

        for (String radioId : stale) {
            externalContexts.remove(radioId);
            externalRadioIds.remove(radioId);
            HandheldSession session = handheldSessions.get(radioId);
            if (session != null) {
                stopSessionPlayback(session);
                session.intendedPlaying = false;
                session.pausedState = false;
                session.lastExternalSyncKey = "";
                if (minecraft.player == null || findRadioStackById(minecraft, radioId).isEmpty()) {
                    handheldSessions.remove(radioId);
                }
            }
            if (radioId.equals(activeHandheldRadioId)) {
                activeHandheldRadioId = "";
            }
        }
    }

    private void playSession(HandheldSession session, String url, long positionMs, String displayTitle, String artist, String thumbnail) {
        ExternalRadioContext externalContext = externalContexts.get(session.radioId);
        boolean shouldBePositional = externalContext != null;
        if (session.channel == null || session.channel.isPositional() != shouldBePositional) {
            if (session.channel != null) {
                session.channel.stop();
            }
            if (shouldBePositional) {
                session.channel = new RadioAudioChannel(
                        true,
                        () -> resolveExternalSourcePosition(externalContext),
                        () -> session.volume,
                        30f
                );
            } else {
                session.channel = new RadioAudioChannel(false, () -> Vec3.ZERO, () -> session.volume, 1f);
            }
        }
        session.channel.setDisplayTitle(displayTitle);
        session.channel.play(url, positionMs);
        session.url = url == null ? "" : url;
        session.title = displayTitle == null ? "" : displayTitle;
        session.artist = artist == null ? "" : artist;
        session.thumbnail = thumbnail == null ? "" : thumbnail;
        session.pausedPositionMs = Math.max(0L, positionMs);
        session.pausedState = false;
        session.intendedPlaying = true;
    }

    private void stopSessionPlayback(HandheldSession session) {
        if (session.channel != null) {
            session.channel.stop();
            session.channel = null;
        }
    }

    private void resumeSessionIfHeld(Minecraft minecraft, HandheldSession session) {
        if (session == null || !session.intendedPlaying || session.url.isBlank()) {
            return;
        }
        if (!isRadioInInventory(minecraft, session.radioId)) {
            return;
        }

        ExternalRadioContext externalContext = externalContexts.get(session.radioId);
        boolean shouldBePositional = externalContext != null;
        if (session.channel == null) {
            if (shouldBePositional) {
                session.channel = new RadioAudioChannel(
                        true,
                        () -> resolveExternalSourcePosition(externalContext),
                        () -> session.volume,
                        30f
                );
            } else {
                session.channel = new RadioAudioChannel(false, () -> Vec3.ZERO, () -> session.volume, 1f);
            }
            session.channel.setDisplayTitle(session.title);
            session.channel.play(session.url, Math.max(0L, session.pausedPositionMs));
            session.pausedState = false;
            return;
        }

        if (session.channel.isPositional() != shouldBePositional) {
            session.channel.stop();
            if (shouldBePositional) {
                session.channel = new RadioAudioChannel(
                        true,
                        () -> resolveExternalSourcePosition(externalContext),
                        () -> session.volume,
                        30f
                );
            } else {
                session.channel = new RadioAudioChannel(false, () -> Vec3.ZERO, () -> session.volume, 1f);
            }
            session.channel.setDisplayTitle(session.title);
            session.channel.play(session.url, Math.max(0L, session.pausedPositionMs));
            session.pausedState = false;
            return;
        }

        if (!session.url.equals(session.channel.getCurrentUrl())) {
            session.channel.setDisplayTitle(session.title);
            session.channel.play(session.url, Math.max(0L, session.pausedPositionMs));
            session.pausedState = false;
            return;
        }

        if (session.channel.isPaused()) {
            session.channel.resume();
            session.pausedState = false;
        }
    }

    private void reconfigureSessionChannelMode(HandheldSession session, boolean shouldBePositional) {
        if (session == null || session.channel == null || session.channel.isPositional() == shouldBePositional) {
            return;
        }

        long resumePosition = session.channel.getEstimatedPositionMs();
        boolean shouldKeepPlaying = session.intendedPlaying;
        String activeUrl = session.url;
        String activeTitle = session.title;
        stopSessionPlayback(session);
        if (activeUrl == null || activeUrl.isBlank()) {
            return;
        }

        ExternalRadioContext externalContext = externalContexts.get(session.radioId);
        if (shouldBePositional) {
            session.channel = new RadioAudioChannel(
                    true,
                    () -> resolveExternalSourcePosition(externalContext),
                    () -> session.volume,
                    30f
            );
        } else {
            session.channel = new RadioAudioChannel(false, () -> Vec3.ZERO, () -> session.volume, 1f);
        }
        session.channel.setDisplayTitle(activeTitle);
        session.channel.play(activeUrl, Math.max(0L, resumePosition));
        if (!shouldKeepPlaying) {
            session.channel.pause();
            session.pausedState = true;
        } else {
            session.pausedState = false;
            session.channel.resume();
        }
        session.intendedPlaying = shouldKeepPlaying;
        session.pausedPositionMs = Math.max(0L, resumePosition);
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

    private void registerExternalContext(String radioId, int contraptionEntityId, BlockPos localPos) {
        Minecraft minecraft = Minecraft.getInstance();
        long nowTick = minecraft.level == null ? 0L : minecraft.level.getGameTime();
        ExternalRadioContext existing = externalContexts.get(radioId);
        if (existing != null && existing.matches(contraptionEntityId, localPos)) {
            existing.lastSeenGameTick = nowTick;
            externalRadioIds.add(radioId);
            return;
        }

        ExternalRadioContext context = new ExternalRadioContext(contraptionEntityId, localPos);
        context.lastSeenGameTick = nowTick;
        externalContexts.put(radioId, context);
        externalRadioIds.add(radioId);
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
        primeRuntimeStateForRadio(radioId, url, title, artist, thumbnail, positionMs, volume, playing, 0L, false, false);
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
        String safeRadioId = safeRadioId(radioId);
        if (safeRadioId.isBlank()) {
            return;
        }
        HandheldSession session = handheldSessions.computeIfAbsent(safeRadioId, HandheldSession::new);
        if (serverSentAtMs > 0L) {
            if (session.lastServerSentAtMs > 0L && serverSentAtMs + 100L < session.lastServerSentAtMs) {
                return;
            }
            session.lastServerSentAtMs = Math.max(session.lastServerSentAtMs, serverSentAtMs);
        }
        applyRuntimeStateToSession(session, url, title, artist, thumbnail, positionMs, volume, playing, forcePositionSync, seekEvent);
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

        session.url = safe(url);
        session.title = safe(title);
        session.artist = safe(artist);
        session.thumbnail = safe(thumbnail);
        session.volume = Mth.clamp(volume, 0f, 2f);
        session.pausedPositionMs = Math.max(0L, positionMs);
        session.pausedState = !playing && (!session.url.isBlank() || !session.title.isBlank());
        session.intendedPlaying = playing;

        boolean shouldBePositional = externalContexts.get(session.radioId) != null;
        if (session.channel == null && shouldBePositional && playing && !session.url.isBlank()) {
            ExternalRadioContext externalContext = externalContexts.get(session.radioId);
            session.channel = new RadioAudioChannel(
                    true,
                    () -> resolveExternalSourcePosition(externalContext),
                    () -> session.volume,
                    30f
            );
            session.channel.setDisplayTitle(session.title);
            session.channel.play(session.url, session.pausedPositionMs);
            return;
        }

        if (session.channel != null) {
            reconfigureSessionChannelMode(session, shouldBePositional);
            if (!session.title.isBlank()) {
                session.channel.setDisplayTitle(session.title);
            }
            boolean sameTrack = !session.url.isBlank() && sameTrack(session.url, session.channel.getCurrentUrl());
            if (!session.url.isBlank() && !sameTrack) {
                if (!shouldApplyRemotePositionCorrection(session) || forcePositionSync) {
                    session.channel.play(session.url, session.pausedPositionMs);
                    sameTrack = true;
                }
            }

            if (sameTrack && shouldApplyRemotePositionCorrection(session)) {
                long localPos = session.channel.getEstimatedPositionMs();
                long targetPos = Math.max(0L, session.pausedPositionMs);
                long drift = Math.abs(localPos - targetPos);
                long nowMs = System.currentTimeMillis();
                boolean cooldownPassed = nowMs - session.lastRemoteSeekAtMs >= REMOTE_SEEK_COOLDOWN_MS;
                if (seekEvent && drift >= REMOTE_SEEK_EVENT_MIN_DRIFT_MS) {
                    session.channel.seekTo(targetPos, !playing);
                    session.lastRemoteSeekAtMs = nowMs;
                } else if (forcePositionSync && !playing && drift >= REMOTE_PAUSED_DRIFT_CORRECTION_MS) {
                    // Force sync for paused snapshots should align exactly.
                    session.channel.seekTo(targetPos, true);
                    session.lastRemoteSeekAtMs = nowMs;
                } else if (!playing && drift >= REMOTE_PAUSED_DRIFT_CORRECTION_MS && cooldownPassed) {
                    session.channel.seekTo(targetPos, true);
                    session.lastRemoteSeekAtMs = nowMs;
                }
            }

            if (playing) {
                session.channel.resume();
                session.pausedState = false;
            } else {
                session.channel.pause();
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
            float partialTicks = minecraft.getFrameTime();
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

    private void persistRuntimeToSession(Minecraft minecraft, HandheldSession session) {
        if (session == null || minecraft.player == null) {
            return;
        }
        if (session.radioId.isBlank()) {
            return;
        }

        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        String queueStateJson = repository.exportQueueStateJsonForRadioId(session.radioId);
        long position = session.channel == null ? Math.max(0L, session.pausedPositionMs) : session.channel.getEstimatedPositionMs();
        SharedMediaSnapshot.MediaEntry current = repository.getCurrentQueueEntryForRadioId(session.radioId);
        String resolvedUrl = current != null && current.url != null && !current.url.isBlank() ? current.url : session.url;
        String resolvedTitle = current != null && current.title != null && !current.title.isBlank() ? current.title : session.title;
        String resolvedArtist = current != null && current.artist != null && !current.artist.isBlank() ? current.artist : session.artist;
        String resolvedThumbnail = current != null && current.thumbnail != null && !current.thumbnail.isBlank() ? current.thumbnail : session.thumbnail;

        ItemStack stack = findRadioStackById(minecraft, session.radioId);
        // Only the client that actually has this radio item should upload handheld runtime.
        // Remote listeners (external context only) must never echo runtime back to server.
        if (stack.isEmpty() || !stack.is(ModItems.RADIO_ITEM)) {
            return;
        }

        String runtimeRadioId = safeRadioId(RadioItem.getRadioId(stack));
        if (runtimeRadioId.isBlank()) {
            runtimeRadioId = session.radioId;
        }
        syncRuntimeStateToServer(session, runtimeRadioId, resolvedUrl, resolvedTitle, resolvedArtist, resolvedThumbnail, queueStateJson, position, session.volume);
    }

    private void syncRuntimeStateToServer(
            HandheldSession session,
            String radioId,
            String url,
            String title,
            String artist,
            String thumbnail,
            String queueStateJson,
            long position,
            float volume
    ) {
        if (radioId == null || radioId.isBlank()) {
            return;
        }
        long positionBucket = Math.max(0L, position) / 500L;
        float clampedVolume = Mth.clamp(volume, 0f, 2f);
        boolean shouldPlay = session.intendedPlaying && !safe(url).isBlank();
        String stateKey = radioId + "|" + safe(url) + "|" + safe(title) + "|" + safe(artist) + "|" + safe(thumbnail) + "|"
                + queueStateJson.hashCode() + "|" + positionBucket + "|" + clampedVolume + "|" + shouldPlay + "|" + session.seekSerial;
        if (stateKey.equals(session.lastSyncedRuntimeKey)) {
            return;
        }

        ModNetworking.sendHandheldState(new ServerboundHandheldStateMessage(
                radioId,
                safe(url),
                safe(title),
                safe(artist),
                safe(thumbnail),
                queueStateJson == null ? "" : queueStateJson,
                clampedVolume,
                Math.max(0L, position),
                Math.max(0, session.seekSerial),
                shouldPlay
        ));
        session.lastSyncedRuntimeKey = stateKey;
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

                    BlockPos blockPos = radioBlockEntity.getBlockPos();
                    if (!radioBlockEntity.isPlaying() || radioBlockEntity.getMediaUrl().isBlank()) {
                        RadioAudioChannel existing = blockChannels.remove(blockPos);
                        if (existing != null) {
                            existing.stop();
                        }
                        blockSeekVersions.remove(blockPos);
                        continue;
                    }

                    Integer suppressTicks = blockEndSuppressTicks.get(blockPos);
                    if (suppressTicks != null && suppressTicks > 0) {
                        continue;
                    }

                    activePositions.add(blockPos);

                    RadioAudioChannel channel = blockChannels.computeIfAbsent(blockPos,
                            ignored -> new RadioAudioChannel(
                                    true,
                                    () -> Vec3.atCenterOf(blockPos),
                                    radioBlockEntity::getVolume,
                                    30f));

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
            }
        }

        Set<BlockPos> stale = new HashSet<>(blockChannels.keySet());
        stale.removeAll(activePositions);
        for (BlockPos stalePos : stale) {
            RadioAudioChannel channel = blockChannels.remove(stalePos);
            if (channel != null) {
                channel.stop();
            }
            blockSeekVersions.remove(stalePos);
        }
    }

    private void tickBlockChannels(Minecraft minecraft) {
        Set<BlockPos> endedNaturally = new HashSet<>();
        for (Map.Entry<BlockPos, RadioAudioChannel> entry : blockChannels.entrySet()) {
            RadioAudioChannel channel = entry.getValue();
            channel.tick();
            boolean ended = channel.consumeNaturalEnd();
            if (!ended && hasExceededTrackDuration(channel)) {
                channel.stop();
                ended = true;
            }
            if (ended) {
                endedNaturally.add(entry.getKey());
            }
        }

        for (BlockPos blockPos : endedNaturally) {
            handleBlockNaturalEnd(minecraft, blockPos);
        }
    }

    private void handleBlockNaturalEnd(Minecraft minecraft, BlockPos blockPos) {
        RadioAudioChannel channel = blockChannels.remove(blockPos);
        if (channel != null) {
            channel.stop();
        }

        if (minecraft.level == null) {
            return;
        }
        if (!(minecraft.level.getBlockEntity(blockPos) instanceof RadioBlockEntity radioBlockEntity)) {
            return;
        }

        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        String radioId = safe(radioBlockEntity.getRadioId());
        String previousActiveRadioId = repository.getActiveRadioId();
        boolean switchedContext = !radioId.isBlank() && !radioId.equals(previousActiveRadioId);

        try {
            if (!radioId.isBlank() && switchedContext) {
                repository.setActiveRadioId(radioId);
            }

            String queueStateJson = radioBlockEntity.getQueueStateJson();
            if (!queueStateJson.isBlank()) {
                repository.importActiveQueueStateJson(queueStateJson);
            }

            alignQueueIndexToCurrentUrl(repository, safe(radioBlockEntity.getMediaUrl()));
            SharedMediaSnapshot.MediaEntry next = repository.nextQueueEntry();
            if (next != null && next.url != null && !next.url.isBlank()) {
                ModNetworking.sendBlockRadioControl(new ServerboundRadioControlMessage(
                        blockPos,
                        ServerboundRadioControlMessage.Action.PLAY_URL,
                        next.url,
                        safe(next.title),
                        safe(next.artist),
                        safe(next.thumbnail),
                        radioBlockEntity.getVolume(),
                        0L
                ));
                ModNetworking.sendBlockRadioControl(new ServerboundRadioControlMessage(
                        blockPos,
                        ServerboundRadioControlMessage.Action.UPDATE_QUEUE_STATE,
                        repository.exportActiveQueueStateJson(),
                        "",
                        "",
                        "",
                        radioBlockEntity.getVolume(),
                        0L
                ));
                blockEndSuppressTicks.remove(blockPos);
                return;
            }
        } finally {
            if (switchedContext) {
                repository.setActiveRadioId(previousActiveRadioId);
            }
        }

        blockEndSuppressTicks.put(blockPos, 40);
        sendBlockStopIfPlaying(minecraft, blockPos);
    }

    private void sendBlockStopIfPlaying(Minecraft minecraft, BlockPos blockPos) {
        if (minecraft.level == null) {
            return;
        }
        if (!(minecraft.level.getBlockEntity(blockPos) instanceof RadioBlockEntity radioBlockEntity)) {
            return;
        }
        if (!radioBlockEntity.isPlaying()) {
            return;
        }
        ModNetworking.sendBlockRadioControl(new ServerboundRadioControlMessage(
                blockPos,
                ServerboundRadioControlMessage.Action.STOP,
                "",
                "",
                "",
                "",
                radioBlockEntity.getVolume(),
                radioBlockEntity.getPlaybackPositionMs()
        ));
    }

    private void tickBlockEndSuppression() {
        blockEndSuppressTicks.forEach((blockPos, ticks) -> {
            if (ticks == null || ticks <= 1) {
                blockEndSuppressTicks.remove(blockPos);
            } else {
                blockEndSuppressTicks.put(blockPos, ticks - 1);
            }
        });
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

    private void alignQueueIndexToCurrentUrl(ClientMediaRepository repository, String currentUrl) {
        if (repository == null || currentUrl == null || currentUrl.isBlank()) {
            return;
        }
        SharedMediaSnapshot.MediaEntry current = repository.getCurrentQueueEntry();
        if (current != null && current.url != null && currentUrl.equals(current.url)) {
            return;
        }
        var queueEntries = repository.getQueueEntries();
        int matchedIndex = -1;
        for (int i = 0; i < queueEntries.size(); i++) {
            SharedMediaSnapshot.MediaEntry entry = queueEntries.get(i);
            if (entry == null || entry.url == null) {
                continue;
            }
            if (currentUrl.equals(entry.url)) {
                if (matchedIndex != -1) {
                    // Ambiguous URL (duplicate entries) - preserve existing queue pointer.
                    return;
                }
                matchedIndex = i;
            }
        }
        if (matchedIndex != -1) {
            repository.setQueueIndex(matchedIndex);
        }
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
        private boolean intendedPlaying;
        private String lastSyncedRuntimeKey = "";
        private String lastExternalSyncKey = "";
        private long lastRemoteSeekAtMs;
        private long lastServerSentAtMs;
        private int seekSerial;

        private HandheldSession(String radioId) {
            this.radioId = radioId;
        }
    }

    private static class ExternalRadioContext {
        private final int contraptionEntityId;
        private final BlockPos localPos;
        private volatile Vec3 lastKnownPos = Vec3.ZERO;
        private volatile Vec3 smoothedPos;
        private volatile long lastSmoothNanos;
        private volatile long lastSeenGameTick;

        private ExternalRadioContext(int contraptionEntityId, BlockPos localPos) {
            this.contraptionEntityId = contraptionEntityId;
            this.localPos = localPos;
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
