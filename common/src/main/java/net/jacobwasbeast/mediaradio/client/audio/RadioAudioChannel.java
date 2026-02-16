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
import java.util.function.Supplier;

import static org.lwjgl.openal.AL10.AL_BUFFERS_PROCESSED;
import static org.lwjgl.openal.AL10.AL_FALSE;
import static org.lwjgl.openal.AL10.AL_FORMAT_MONO16;
import static org.lwjgl.openal.AL10.AL_FORMAT_STEREO16;
import static org.lwjgl.openal.AL10.AL_GAIN;
import static org.lwjgl.openal.AL10.AL_MAX_DISTANCE;
import static org.lwjgl.openal.AL10.AL_PLAYING;
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

    private int sourceId = -1;
    private boolean paused;
    private boolean stopping;
    private boolean decoderEnded;
    private boolean endedNaturally;

    private String currentUrl = "";
    private String displayTitle = "";
    private long desiredStartPositionMs;
    private long lastStartMillis;
    private long pausedPositionMs = -1L;
    private long trackDurationMs = -1L;

    private CompletableFuture<?> decodeTask;

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
        if (paused && pausedPositionMs >= 0L) {
            return pausedPositionMs;
        }
        if (lastStartMillis <= 0L) {
            return desiredStartPositionMs;
        }
        return desiredStartPositionMs + Math.max(0L, System.currentTimeMillis() - lastStartMillis);
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
        pcmQueue.clear();
        queuedBuffers.clear();
        launchDecoder(currentUrl, desiredStartPositionMs);
    }

    public void pause() {
        paused = true;
        pausedPositionMs = getEstimatedPositionMs();
        if (sourceId != -1) {
            alSourcePause(sourceId);
        }
    }

    public void resume() {
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
        endedNaturally = naturalEnd;
    }

    public void tick() {
        if (stopping) {
            return;
        }

        if (sourceId == -1 && !currentUrl.isBlank()) {
            sourceId = alGenSources();
            applySourceSettings();
        }

        if (sourceId == -1) {
            return;
        }

        applySourceSettings();
        releaseProcessedBuffers(false);
        enqueuePendingBuffers();

        if (paused) {
            return;
        }

        if (alGetSourcei(sourceId, AL_SOURCE_STATE) != AL_PLAYING && !queuedBuffers.isEmpty()) {
            alSourcePlay(sourceId);
        }

        if (decoderEnded && pcmQueue.isEmpty() && queuedBuffers.isEmpty()) {
            stopInternal(true);
        }
    }

    private void launchDecoder(String url, long startPositionMs) {
        decodeTask = CompletableFuture.runAsync(() -> {
            try {
                var track = LavaPlayerAccess.get().loadTrack(url).join();
                if (track == null) {
                    decoderEnded = true;
                    return;
                }

                var openedTrack = LavaPlayerAccess.get().openTrack(track, startPositionMs);
                lastStartMillis = System.currentTimeMillis();
                trackDurationMs = openedTrack.durationMs();
                if (trackDurationMs <= 0L && openedTrack.info() != null) {
                    trackDurationMs = openedTrack.info().length;
                }
                if (displayTitle.isBlank() && openedTrack.info() != null && openedTrack.info().title != null) {
                    displayTitle = openedTrack.info().title;
                }

                readAudioLoop(openedTrack.stream(), openedTrack.player());
            } catch (Exception exception) {
                MediaRadio.LOGGER.error("Failed to decode audio for {}", url, exception);
            } finally {
                decoderEnded = true;
            }
        }, DECODE_EXECUTOR);
    }

    private void readAudioLoop(AudioInputStream audioInputStream, com.sedmelluq.discord.lavaplayer.player.AudioPlayer player) {
        byte[] readBuffer = new byte[LavaPlayerAccess.BYTES_PER_SECOND];
        try (audioInputStream) {
            while (!stopping && !Thread.currentThread().isInterrupted()) {
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
                    if (stopping || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    Thread.sleep(2L);
                }
            }
        } catch (Exception exception) {
            if (!stopping) {
                MediaRadio.LOGGER.error("Audio decode stream failed", exception);
            }
        } finally {
            player.destroy();
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
}
