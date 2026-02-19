package net.jacobwasbeast.mediaradio.client.audio;

import net.jacobwasbeast.mediaradio.MediaRadio;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioInputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.lwjgl.openal.AL10.AL_BUFFERS_PROCESSED;
import static org.lwjgl.openal.AL10.AL_FALSE;
import static org.lwjgl.openal.AL10.AL_FORMAT_MONO16;
import static org.lwjgl.openal.AL10.AL_FORMAT_STEREO16;
import static org.lwjgl.openal.AL10.AL_GAIN;
import static org.lwjgl.openal.AL10.AL_MAX_DISTANCE;
import static org.lwjgl.openal.AL10.AL_PLAYING;
import static org.lwjgl.openal.AL10.AL_STOPPED;
import static org.lwjgl.openal.AL10.AL_POSITION;
import static org.lwjgl.openal.AL10.AL_REFERENCE_DISTANCE;
import static org.lwjgl.openal.AL10.AL_ROLLOFF_FACTOR;
import static org.lwjgl.openal.AL10.AL_SOURCE_RELATIVE;
import static org.lwjgl.openal.AL10.AL_SOURCE_STATE;
import static org.lwjgl.openal.AL10.AL_TRUE;
import static org.lwjgl.openal.AL10.alBufferData;
import static org.lwjgl.openal.AL10.alDeleteBuffers;
import static org.lwjgl.openal.AL10.alDeleteSources;
import static org.lwjgl.openal.AL10.alGenBuffers;
import static org.lwjgl.openal.AL10.alGenSources;
import static org.lwjgl.openal.AL10.alGetSourcei;
import static org.lwjgl.openal.AL10.alSource3f;
import static org.lwjgl.openal.AL10.alSourcePause;
import static org.lwjgl.openal.AL10.alSourcePlay;
import static org.lwjgl.openal.AL10.alSourceQueueBuffers;
import static org.lwjgl.openal.AL10.alSourceStop;
import static org.lwjgl.openal.AL10.alSourceUnqueueBuffers;
import static org.lwjgl.openal.AL10.alSourcef;
import static org.lwjgl.openal.AL10.alSourcei;
import static org.lwjgl.openal.AL10.alDistanceModel;
import static org.lwjgl.openal.AL11.AL_LINEAR_DISTANCE;

public class RadioAudioChannel {
    private static boolean distanceModelConfigured;
    private static final long STALL_RECOVERY_STARTUP_GRACE_MS = 4_000L;
    private static final long STALL_RECOVERY_THRESHOLD_MS = 6_500L;
    private static final long STALL_RECOVERY_COOLDOWN_MS = 10_000L;
    private static final long SOURCE_CREATE_RETRY_COOLDOWN_MS = 1_000L;
    private static final long SOURCE_CREATE_LOG_COOLDOWN_MS = 10_000L;

    private static final ExecutorService DECODE_EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
        private int counter;

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "MediaRadio-Decode-" + counter++);
            thread.setDaemon(true);
            return thread;
        }
    });

    private final boolean spatial;
    private final Supplier<Vec3> positionSupplier;
    private final Supplier<Float> volumeSupplier;
    private final float maxDistance;

    private final ArrayBlockingQueue<byte[]> pcmQueue = new ArrayBlockingQueue<>(40);
    private final Queue<Integer> queuedBuffers = new ArrayDeque<>();
    private final AtomicLong decodeGeneration = new AtomicLong();

    private int sourceId = -1;
    private volatile boolean paused;
    private volatile boolean stopping;
    private volatile boolean decoderEnded;
    private volatile boolean endedNaturally;

    private volatile String currentUrl = "";
    private volatile String displayTitle = "";
    private volatile long desiredStartPositionMs;
    private volatile long lastStartMillis;
    private volatile long pausedPositionMs = -1L;
    private volatile long trackDurationMs = -1L;
    private volatile com.sedmelluq.discord.lavaplayer.track.AudioTrack activeLavaTrack;
    private volatile long playbackStartedAtMs;
    private volatile long lastPlaybackProgressAtMs;
    private volatile long stallDetectedAtMs;
    private volatile long lastRecoveryAttemptAtMs;
    private volatile long nextSourceCreateRetryAtMs;
    private volatile long lastSourceCreateErrorLogAtMs;

    private volatile CompletableFuture<?> decodeTask;

    public RadioAudioChannel(boolean spatial, Supplier<Vec3> positionSupplier, Supplier<Float> volumeSupplier, float maxDistance) {
        this.spatial = spatial;
        this.positionSupplier = positionSupplier;
        this.volumeSupplier = volumeSupplier;
        this.maxDistance = maxDistance;
    }

    public String getCurrentUrl() {
        return currentUrl;
    }

    public String getDisplayTitle() {
        return displayTitle;
    }

    public void setDisplayTitle(String displayTitle) {
        this.displayTitle = displayTitle == null ? "" : displayTitle;
    }

    public long getEstimatedPositionMs() {
        long estimate;
        if (paused && pausedPositionMs >= 0L) {
            estimate = pausedPositionMs;
        } else if (lastStartMillis <= 0L) {
            estimate = desiredStartPositionMs;
        } else {
            estimate = desiredStartPositionMs + Math.max(0L, System.currentTimeMillis() - lastStartMillis);
        }
        if (trackDurationMs > 0L) {
            return Math.min(trackDurationMs, Math.max(0L, estimate));
        }
        return Math.max(0L, estimate);
    }

    public long getTrackDurationMs() {
        return trackDurationMs;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isPlaying() {
        return sourceId != -1 && alGetSourcei(sourceId, AL_SOURCE_STATE) == AL_PLAYING;
    }

    public boolean isPositional() {
        return spatial;
    }

    public void play(String url, long startPositionMs) {
        stopInternal(false);
        currentUrl = url == null ? "" : url;
        desiredStartPositionMs = Math.max(0L, startPositionMs);
        pausedPositionMs = -1L;
        trackDurationMs = -1L;
        paused = false;
        stopping = false;
        decoderEnded = false;
        endedNaturally = false;
        playbackStartedAtMs = System.currentTimeMillis();
        lastPlaybackProgressAtMs = playbackStartedAtMs;
        stallDetectedAtMs = 0L;
        nextSourceCreateRetryAtMs = 0L;
        lastSourceCreateErrorLogAtMs = 0L;
        pcmQueue.clear();
        queuedBuffers.clear();
        long generation = decodeGeneration.incrementAndGet();
        launchDecoder(currentUrl, desiredStartPositionMs, generation);
    }

    public void pause() {
        paused = true;
        pausedPositionMs = getEstimatedPositionMs();
        if (sourceId != -1) {
            alSourcePause(sourceId);
        }
    }

    public void resume() {
        if (!paused) {
            if (sourceId != -1 && !queuedBuffers.isEmpty() && alGetSourcei(sourceId, AL_SOURCE_STATE) != AL_PLAYING) {
                alSourcePlay(sourceId);
            }
            return;
        }
        if (pausedPositionMs >= 0L) {
            desiredStartPositionMs = pausedPositionMs;
            lastStartMillis = System.currentTimeMillis();
            pausedPositionMs = -1L;
        }
        paused = false;
        if (sourceId != -1 && !queuedBuffers.isEmpty()) {
            alSourcePlay(sourceId);
        }
    }

    public void seekTo(long positionMs, boolean keepPaused) {
        if (currentUrl.isBlank()) {
            return;
        }

        long clamped = Math.max(0L, positionMs);
        if (trackDurationMs > 0L) {
            clamped = Math.min(trackDurationMs, clamped);
        }
        play(currentUrl, clamped);
        if (keepPaused) {
            pause();
        }
    }

    public void stop() {
        stopInternal(false);
    }

    public boolean consumeNaturalEnd() {
        if (!endedNaturally) {
            return false;
        }
        endedNaturally = false;
        return true;
    }

    private void stopInternal(boolean naturalEnd) {
        decodeGeneration.incrementAndGet();
        stopping = true;
        if (decodeTask != null) {
            decodeTask.cancel(true);
            decodeTask = null;
        }

        if (sourceId != -1) {
            alSourceStop(sourceId);
            releaseProcessedBuffers(true);
            alDeleteSources(sourceId);
            sourceId = -1;
        }

        pcmQueue.clear();
        while (!queuedBuffers.isEmpty()) {
            Integer id = queuedBuffers.poll();
            if (id != null) {
                alDeleteBuffers(id);
            }
        }

        currentUrl = "";
        lastStartMillis = 0L;
        pausedPositionMs = -1L;
        trackDurationMs = -1L;
        activeLavaTrack = null;
        playbackStartedAtMs = 0L;
        lastPlaybackProgressAtMs = 0L;
        stallDetectedAtMs = 0L;
        lastRecoveryAttemptAtMs = 0L;
        nextSourceCreateRetryAtMs = 0L;
        lastSourceCreateErrorLogAtMs = 0L;
        endedNaturally = naturalEnd;
    }

    public void tick() {
        if (stopping) {
            return;
        }

        if (sourceId == -1 && !currentUrl.isBlank()) {
            long nowMs = System.currentTimeMillis();
            if (nowMs < nextSourceCreateRetryAtMs) {
                return;
            }
            try {
                sourceId = alGenSources();
            } catch (RuntimeException exception) {
                sourceId = -1;
                nextSourceCreateRetryAtMs = nowMs + SOURCE_CREATE_RETRY_COOLDOWN_MS;
                maybeLogSourceCreateFailure(exception);
                return;
            }
            if (sourceId <= 0) {
                sourceId = -1;
                nextSourceCreateRetryAtMs = nowMs + SOURCE_CREATE_RETRY_COOLDOWN_MS;
                maybeLogSourceCreateFailure(null);
                return;
            }
            applySourceSettings();
        }

        if (sourceId == -1) {
            return;
        }

        applySourceSettings();
        refreshTrackDurationFromLavaTrack();
        releaseProcessedBuffers(false);
        enqueuePendingBuffers();

        if (paused) {
            return;
        }

        int sourceState = alGetSourcei(sourceId, AL_SOURCE_STATE);
        if (sourceState != AL_PLAYING && !queuedBuffers.isEmpty()) {
            alSourcePlay(sourceId);
            sourceState = alGetSourcei(sourceId, AL_SOURCE_STATE);
        }
        if (sourceState == AL_PLAYING || !queuedBuffers.isEmpty() || !pcmQueue.isEmpty()) {
            lastPlaybackProgressAtMs = System.currentTimeMillis();
            stallDetectedAtMs = 0L;
        }

        // On some OpenAL backends, EOF can leave queued buffers lingering in STOPPED state.
        // Force-drain when decode has ended so natural-end can always be emitted.
        if (decoderEnded && pcmQueue.isEmpty() && sourceState == AL_STOPPED && !queuedBuffers.isEmpty()) {
            releaseProcessedBuffers(true);
            sourceState = alGetSourcei(sourceId, AL_SOURCE_STATE);
        }

        if (decoderEnded && pcmQueue.isEmpty() && queuedBuffers.isEmpty() && sourceState != AL_PLAYING) {
            stopInternal(true);
            return;
        }
        attemptStallRecovery(sourceState);
    }

    private void attemptStallRecovery(int sourceState) {
        if (paused || stopping || currentUrl.isBlank()) {
            stallDetectedAtMs = 0L;
            return;
        }
        if (decoderEnded) {
            return;
        }

        long now = System.currentTimeMillis();
        if (playbackStartedAtMs > 0L && now - playbackStartedAtMs < STALL_RECOVERY_STARTUP_GRACE_MS) {
            return;
        }
        if (lastPlaybackProgressAtMs > 0L && now - lastPlaybackProgressAtMs < STALL_RECOVERY_THRESHOLD_MS) {
            return;
        }
        if (sourceState == AL_PLAYING || !queuedBuffers.isEmpty() || !pcmQueue.isEmpty()) {
            stallDetectedAtMs = 0L;
            return;
        }
        if (stallDetectedAtMs <= 0L) {
            stallDetectedAtMs = now;
            return;
        }
        if (now - stallDetectedAtMs < STALL_RECOVERY_THRESHOLD_MS) {
            return;
        }
        if (now - lastRecoveryAttemptAtMs < STALL_RECOVERY_COOLDOWN_MS) {
            return;
        }

        String resumeUrl = currentUrl;
        if (resumeUrl.isBlank()) {
            return;
        }
        long resumePositionMs = getEstimatedPositionMs();
        String resumeTitle = displayTitle;
        lastRecoveryAttemptAtMs = now;
        MediaRadio.LOGGER.warn("Recovering stalled audio channel for {}", resumeUrl);
        play(resumeUrl, resumePositionMs);
        if (!resumeTitle.isBlank()) {
            setDisplayTitle(resumeTitle);
        }
    }

    private void launchDecoder(String url, long startPositionMs, long generation) {
        decodeTask = CompletableFuture.runAsync(() -> {
            try {
                if (!isGenerationCurrent(generation)) {
                    return;
                }
                var track = LavaPlayerAccess.get().loadTrack(url).join();
                if (!isGenerationCurrent(generation)) {
                    return;
                }
                if (track == null) {
                    markDecoderEnded(generation);
                    return;
                }

                var openedTrack = LavaPlayerAccess.get().openTrack(track, startPositionMs);
                if (!isGenerationCurrent(generation)) {
                    openedTrack.player().destroy();
                    return;
                }
                lastStartMillis = System.currentTimeMillis();
                activeLavaTrack = openedTrack.track();
                trackDurationMs = sanitizeDurationMs(
                        openedTrack.durationMs(),
                        openedTrack.info(),
                        track.getDuration(),
                        track.getInfo() == null ? -1L : track.getInfo().length,
                        openedTrack.track() == null ? -1L : openedTrack.track().getDuration(),
                        openedTrack.track() == null || openedTrack.track().getInfo() == null ? -1L : openedTrack.track().getInfo().length
                );
                if (trackDurationMs <= 0L) {
                    var info = openedTrack.info();
                    MediaRadio.LOGGER.debug(
                            "Unknown track duration url={} info.uri={} info.identifier={} info.isStream={} opened.durationMs={} opened.info.length={} loaded.durationMs={} loaded.info.length={} active.durationMs={} active.info.length={}",
                            url,
                            info == null ? "" : info.uri,
                            info == null ? "" : info.identifier,
                            info != null && info.isStream,
                            openedTrack.durationMs(),
                            info == null ? -1L : info.length,
                            track.getDuration(),
                            track.getInfo() == null ? -1L : track.getInfo().length,
                            openedTrack.track() == null ? -1L : openedTrack.track().getDuration(),
                            openedTrack.track() == null || openedTrack.track().getInfo() == null ? -1L : openedTrack.track().getInfo().length
                    );
                }
                if (displayTitle.isBlank() && openedTrack.info() != null && openedTrack.info().title != null) {
                    displayTitle = openedTrack.info().title;
                }

                readAudioLoop(openedTrack.stream(), openedTrack.player(), generation);
            } catch (Exception exception) {
                if (isGenerationCurrent(generation)) {
                    MediaRadio.LOGGER.error("Failed to decode audio for {}", url, exception);
                }
            } finally {
                markDecoderEnded(generation);
            }
        }, DECODE_EXECUTOR);
    }

    private void readAudioLoop(AudioInputStream audioInputStream, com.sedmelluq.discord.lavaplayer.player.AudioPlayer player, long generation) {
        byte[] readBuffer = new byte[LavaPlayerAccess.BYTES_PER_SECOND];
        try (audioInputStream) {
            while (isGenerationCurrent(generation) && !Thread.currentThread().isInterrupted()) {
                int read = audioInputStream.read(readBuffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                byte[] chunk = new byte[read];
                System.arraycopy(readBuffer, 0, chunk, 0, read);
                while (!pcmQueue.offer(chunk)) {
                    if (!isGenerationCurrent(generation) || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    Thread.sleep(2L);
                }
            }
        } catch (Exception exception) {
            if (isGenerationCurrent(generation) && !stopping) {
                MediaRadio.LOGGER.error("Audio decode stream failed", exception);
            }
        } finally {
            player.destroy();
        }
    }

    private boolean isGenerationCurrent(long generation) {
        return decodeGeneration.get() == generation;
    }

    private void markDecoderEnded(long generation) {
        if (isGenerationCurrent(generation)) {
            decoderEnded = true;
        }
    }

    private long sanitizeDurationMs(
            long openedDurationMs,
            com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo openedInfo,
            long loadedTrackDurationMs,
            long loadedInfoLengthMs,
            long activeTrackDurationMs,
            long activeInfoLengthMs
    ) {
        long result = normalizeDuration(openedDurationMs);
        if (result <= 0L) {
            result = normalizeDuration(openedInfo == null ? -1L : openedInfo.length);
        }
        if (result <= 0L) {
            result = normalizeDuration(loadedTrackDurationMs);
        }
        if (result <= 0L) {
            result = normalizeDuration(loadedInfoLengthMs);
        }
        if (result <= 0L) {
            result = normalizeDuration(activeTrackDurationMs);
        }
        if (result <= 0L) {
            result = normalizeDuration(activeInfoLengthMs);
        }

        if (result <= 0L) {
            return -1L;
        }
        return result;
    }

    private long normalizeDuration(long value) {
        return (value <= 0L || value == Long.MAX_VALUE) ? -1L : value;
    }

    private void refreshTrackDurationFromLavaTrack() {
        if (trackDurationMs > 0L) {
            return;
        }
        var track = activeLavaTrack;
        if (track == null) {
            return;
        }

        long refreshed = normalizeDuration(track.getDuration());
        if (refreshed <= 0L) {
            var info = track.getInfo();
            refreshed = normalizeDuration(info == null ? -1L : info.length);
        }
        if (refreshed > 0L) {
            trackDurationMs = refreshed;
        }
    }

    private void enqueuePendingBuffers() {
        while (queuedBuffers.size() < 10) {
            byte[] data = pcmQueue.poll();
            if (data == null) {
                return;
            }

            int alFormat = AL_FORMAT_STEREO16;
            if (spatial) {
                data = downmixStereo16ToMono16(data);
                alFormat = AL_FORMAT_MONO16;
            }

            int bufferId = alGenBuffers();
            ByteBuffer byteBuffer = BufferUtils.createByteBuffer(data.length);
            byteBuffer.put(data);
            byteBuffer.flip();
            alBufferData(bufferId, alFormat, byteBuffer, LavaPlayerAccess.SAMPLE_RATE);

            alSourceQueueBuffers(sourceId, bufferId);
            queuedBuffers.add(bufferId);
        }
    }

    private byte[] downmixStereo16ToMono16(byte[] stereo) {
        int frameCount = stereo.length / 4;
        if (frameCount <= 0) {
            return stereo;
        }

        byte[] mono = new byte[frameCount * 2];
        for (int frame = 0; frame < frameCount; frame++) {
            int stereoIndex = frame * 4;
            short left = (short) ((stereo[stereoIndex + 1] << 8) | (stereo[stereoIndex] & 0xFF));
            short right = (short) ((stereo[stereoIndex + 3] << 8) | (stereo[stereoIndex + 2] & 0xFF));
            int mixed = (left + right) / 2;

            int monoIndex = frame * 2;
            mono[monoIndex] = (byte) (mixed & 0xFF);
            mono[monoIndex + 1] = (byte) ((mixed >> 8) & 0xFF);
        }
        return mono;
    }

    private void releaseProcessedBuffers(boolean all) {
        if (sourceId == -1) {
            return;
        }

        int processed = all ? queuedBuffers.size() : alGetSourcei(sourceId, AL_BUFFERS_PROCESSED);
        for (int i = 0; i < processed; i++) {
            if (queuedBuffers.isEmpty()) {
                break;
            }
            int unqueuedBufferId = alSourceUnqueueBuffers(sourceId);
            queuedBuffers.poll();
            alDeleteBuffers(unqueuedBufferId);
        }
    }

    private void applySourceSettings() {
        if (sourceId == -1) {
            return;
        }

        float volume = volumeSupplier.get() == null ? 1.0f : Math.max(0f, Math.min(2f, volumeSupplier.get()));
        alSourcef(sourceId, AL_GAIN, volume);

        if (spatial) {
            Vec3 position = positionSupplier.get();
            if (position == null) {
                position = Vec3.ZERO;
            }
            alSourcei(sourceId, AL_SOURCE_RELATIVE, AL_FALSE);
            if (!distanceModelConfigured) {
                alDistanceModel(AL_LINEAR_DISTANCE);
                distanceModelConfigured = true;
            }
            alSource3f(sourceId, AL_POSITION, (float) position.x, (float) position.y, (float) position.z);
            alSourcef(sourceId, AL_MAX_DISTANCE, maxDistance);
            alSourcef(sourceId, AL_REFERENCE_DISTANCE, 1.75f);
            alSourcef(sourceId, AL_ROLLOFF_FACTOR, 1f);
        } else {
            alSourcei(sourceId, AL_SOURCE_RELATIVE, AL_TRUE);
            alSource3f(sourceId, AL_POSITION, 0f, 0f, 0f);
        }
    }

    private void maybeLogSourceCreateFailure(Throwable throwable) {
        long now = System.currentTimeMillis();
        if (now - lastSourceCreateErrorLogAtMs < SOURCE_CREATE_LOG_COOLDOWN_MS) {
            return;
        }
        lastSourceCreateErrorLogAtMs = now;
        if (throwable == null) {
            MediaRadio.LOGGER.warn("OpenAL source allocation failed for radio channel. Retrying.");
            return;
        }
        MediaRadio.LOGGER.warn("OpenAL source allocation failed for radio channel. Retrying.", throwable);
    }
}
