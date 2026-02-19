package net.jacobwasbeast.mediaradio.client.audio;

import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.AudioPlayerInputStream;
import com.sedmelluq.discord.lavaplayer.format.Pcm16AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import net.jacobwasbeast.mediaradio.MediaRadio;

import javax.sound.sampled.AudioInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LavaPlayerAccess {

    public static final int SAMPLE_RATE = 48000;
    public static final int BYTES_PER_SECOND = SAMPLE_RATE * 2 * 2;
    private static final long TRACK_LOAD_TIMEOUT_SECONDS = 20L;
    private static final long PLAYLIST_LOAD_TIMEOUT_SECONDS = 25L;
    private static final int TIMEOUT_FAILURES_BEFORE_RESET = 3;
    private static final long MANAGER_RESET_COOLDOWN_MS = 20_000L;
    private static final Pattern YOUTUBE_WATCH_PATTERN = Pattern.compile("(?:youtu\\.be/|youtube\\.com/(?:watch\\?v=|shorts/|live/))([A-Za-z0-9_-]{11})");
    private static final Pattern YOUTUBE_V_PARAM_PATTERN = Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})");

    private static final LavaPlayerAccess INSTANCE = new LavaPlayerAccess();

    private final AudioDataFormat audioDataFormat = new Pcm16AudioDataFormat(2, SAMPLE_RATE, 960, false);
    private final Object managerResetLock = new Object();
    private final AtomicInteger consecutiveTimeoutFailures = new AtomicInteger();
    private volatile AudioPlayerManager audioPlayerManager;
    private volatile long lastManagerResetAtMs;

    private LavaPlayerAccess() {
        audioPlayerManager = createConfiguredManager();
    }

    public static LavaPlayerAccess get() {
        return INSTANCE;
    }

    public CompletableFuture<AudioTrack> loadTrack(String identifier) {
        String normalizedIdentifier = normalizeIdentifier(identifier);
        CompletableFuture<AudioTrack> future = new CompletableFuture<>();
        Future<Void> loadFuture = audioPlayerManager.loadItem(normalizedIdentifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                if (future.complete(track)) {
                    recordLoadSuccess();
                }
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                AudioTrack loadedTrack;
                if (playlist.getSelectedTrack() != null) {
                    loadedTrack = playlist.getSelectedTrack();
                } else if (!playlist.getTracks().isEmpty()) {
                    loadedTrack = playlist.getTracks().get(0);
                } else {
                    loadedTrack = null;
                }
                if (future.complete(loadedTrack)) {
                    recordLoadSuccess();
                }
            }

            @Override
            public void noMatches() {
                if (future.complete(null)) {
                    recordLoadSuccess();
                }
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                if (future.completeExceptionally(exception)) {
                    recordLoadFailure(normalizedIdentifier, exception);
                }
            }
        });
        return withTimeoutGuard(normalizedIdentifier, future, loadFuture, TRACK_LOAD_TIMEOUT_SECONDS);
    }

    public CompletableFuture<List<SearchResult>> searchYoutube(String query, int maxResults) {
        String safeQuery = query == null ? "" : query.trim();
        if (safeQuery.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }

        int limit = Math.max(1, maxResults);
        CompletableFuture<List<SearchResult>> future = new CompletableFuture<>();
        String identifier = "ytsearch:" + safeQuery;
        Future<Void> loadFuture = audioPlayerManager.loadItem(identifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                if (future.complete(List.of(toSearchResult(track)))) {
                    recordLoadSuccess();
                }
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                List<SearchResult> results = new ArrayList<>();
                for (AudioTrack track : playlist.getTracks()) {
                    if (track == null) {
                        continue;
                    }
                    results.add(toSearchResult(track));
                    if (results.size() >= limit) {
                        break;
                    }
                }
                if (future.complete(results)) {
                    recordLoadSuccess();
                }
            }

            @Override
            public void noMatches() {
                if (future.complete(List.of())) {
                    recordLoadSuccess();
                }
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                if (future.completeExceptionally(exception)) {
                    recordLoadFailure(identifier, exception);
                }
            }
        });
        return withTimeoutGuard(identifier, future, loadFuture, TRACK_LOAD_TIMEOUT_SECONDS);
    }

    public CompletableFuture<List<SearchResult>> loadPlaylistTracks(String identifier, int maxTracks) {
        String normalizedIdentifier = normalizeIdentifier(identifier);
        if (normalizedIdentifier.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }

        int limit = Math.max(1, maxTracks);
        CompletableFuture<List<SearchResult>> future = new CompletableFuture<>();
        Future<Void> loadFuture = audioPlayerManager.loadItem(normalizedIdentifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                if (future.complete(List.of(toSearchResult(track)))) {
                    recordLoadSuccess();
                }
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                List<SearchResult> results = new ArrayList<>();
                for (AudioTrack track : playlist.getTracks()) {
                    if (track == null) {
                        continue;
                    }
                    results.add(toSearchResult(track));
                    if (results.size() >= limit) {
                        break;
                    }
                }
                if (future.complete(results)) {
                    recordLoadSuccess();
                }
            }

            @Override
            public void noMatches() {
                if (future.complete(List.of())) {
                    recordLoadSuccess();
                }
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                if (future.completeExceptionally(exception)) {
                    recordLoadFailure(normalizedIdentifier, exception);
                }
            }
        });
        return withTimeoutGuard(normalizedIdentifier, future, loadFuture, PLAYLIST_LOAD_TIMEOUT_SECONDS);
    }

    public OpenedTrack openTrack(AudioTrack sourceTrack, long positionMs) {
        AudioPlayer audioPlayer = audioPlayerManager.createPlayer();
        AudioTrack track = sourceTrack.makeClone();
        long durationMs = sourceTrack.getDuration();
        if (durationMs <= 0L) {
            durationMs = track.getDuration();
        }
        long normalizedPositionMs = Math.max(0L, positionMs);
        if (durationMs > 0L && durationMs != Long.MAX_VALUE && normalizedPositionMs >= durationMs) {
            normalizedPositionMs = normalizedPositionMs % durationMs;
        }
        if (normalizedPositionMs > 0L) {
            track.setPosition(normalizedPositionMs);
        }
        audioPlayer.startTrack(track, false);
        AudioInputStream stream = AudioPlayerInputStream.createStream(audioPlayer, audioDataFormat, 3000L, true);
        return new OpenedTrack(audioPlayer, stream, track, track.getInfo(), durationMs);
    }

    private AudioPlayerManager createConfiguredManager() {
        AudioPlayerManager manager = new DefaultAudioPlayerManager();
        manager.setFrameBufferDuration(1000);
        manager.setPlayerCleanupThreshold(Long.MAX_VALUE);
        manager.getConfiguration().setResamplingQuality(AudioConfiguration.ResamplingQuality.HIGH);
        manager.getConfiguration().setOutputFormat(audioDataFormat);
        AudioSourceManagers.registerLocalSource(manager);
        tryRegisterYoutubeSource(manager);
        registerNonYoutubeRemoteSources(manager);
        logRegisteredSourceManagers(manager);
        return manager;
    }

    private <T> CompletableFuture<T> withTimeoutGuard(String identifier, CompletableFuture<T> resultFuture, Future<Void> loadFuture, long timeoutSeconds) {
        return resultFuture
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .whenComplete((ignored, throwable) -> {
                    if (throwable == null) {
                        return;
                    }
                    if (loadFuture != null && !loadFuture.isDone()) {
                        loadFuture.cancel(true);
                    }
                    Throwable cause = unwrapCompletionThrowable(throwable);
                    if (cause instanceof FriendlyException) {
                        return;
                    }
                    recordLoadFailure(identifier, cause);
                });
    }

    private Throwable unwrapCompletionThrowable(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? throwable : current;
    }

    private void recordLoadSuccess() {
        consecutiveTimeoutFailures.set(0);
    }

    private void recordLoadFailure(String identifier, Throwable throwable) {
        Throwable cause = unwrapCompletionThrowable(throwable);
        boolean timeout = cause instanceof TimeoutException;
        boolean loadQueueRejected = cause instanceof FriendlyException friendlyException
                && friendlyException.getMessage() != null
                && friendlyException.getMessage().contains("queue is full");
        if (!timeout && !loadQueueRejected) {
            return;
        }

        int failures = consecutiveTimeoutFailures.incrementAndGet();
        if (timeout) {
            MediaRadio.LOGGER.warn("Timed out loading media identifier {} (consecutive timeouts: {})", identifier, failures);
        }
        if (failures < TIMEOUT_FAILURES_BEFORE_RESET) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastManagerResetAtMs < MANAGER_RESET_COOLDOWN_MS) {
            return;
        }

        synchronized (managerResetLock) {
            int currentFailures = consecutiveTimeoutFailures.get();
            long nowLocked = System.currentTimeMillis();
            if (currentFailures < TIMEOUT_FAILURES_BEFORE_RESET || nowLocked - lastManagerResetAtMs < MANAGER_RESET_COOLDOWN_MS) {
                return;
            }

            AudioPlayerManager previous = audioPlayerManager;
            AudioPlayerManager replacement = createConfiguredManager();
            audioPlayerManager = replacement;
            lastManagerResetAtMs = nowLocked;
            consecutiveTimeoutFailures.set(0);
            MediaRadio.LOGGER.warn(
                    "Reset client audio load manager after repeated load failures (last identifier: {})",
                    identifier,
                    cause
            );
            try {
                previous.shutdown();
            } catch (Exception shutdownError) {
                MediaRadio.LOGGER.warn("Failed to shutdown previous audio load manager after reset", shutdownError);
            }
        }
    }

    private void tryRegisterYoutubeSource(AudioPlayerManager manager) {
        try {
            // Exact same construction style as IamMusicPlayer_FIX-1.20.1.
            YoutubeAudioSourceManager sourceManager = new YoutubeAudioSourceManager();
            manager.registerSourceManager((AudioSourceManager) sourceManager);
        } catch (Exception exception) {
            MediaRadio.LOGGER.warn("YouTube source manager not available, YouTube playback may fail", exception);
        }
    }

    private void registerNonYoutubeRemoteSources(AudioPlayerManager manager) {
        // Do NOT use AudioSourceManagers.registerRemoteSources(...) with Lavaplayer 2.2.6.
        // It auto-registers the legacy Lavaplayer YouTube source and causes cipher failures.
        manager.registerSourceManager(new HttpAudioSourceManager());
    }

    private void logRegisteredSourceManagers(AudioPlayerManager manager) {
        String sourceList = manager.getSourceManagers().stream()
                .map(source -> source.getClass().getName())
                .collect(Collectors.joining(", "));
        MediaRadio.LOGGER.info("Registered audio sources: {}", sourceList);
    }

    private String normalizeIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(identifier.trim());
            String host = uri.getHost();
            if (host == null) {
                return identifier.trim();
            }

            String normalizedHost = host.toLowerCase();
            if ("youtu.be".equals(normalizedHost)) {
                String path = uri.getPath();
                if (path != null && path.length() > 1) {
                    String videoId = path.substring(1);
                    return "https://www.youtube.com/watch?v=" + videoId;
                }
            }
        } catch (Exception ignored) {
        }
        return identifier.trim();
    }

    private SearchResult toSearchResult(AudioTrack track) {
        AudioTrackInfo info = track.getInfo();
        String identifier = normalizeIdentifier(info.uri == null || info.uri.isBlank() ? info.identifier : info.uri);
        String title = info.title == null || info.title.isBlank() ? identifier : info.title;
        String artist = info.author == null ? "" : info.author;
        String thumbnail = inferYoutubeThumbnail(identifier.isBlank() ? info.identifier : identifier);
        long duration = info.length > 0L ? info.length : track.getDuration();
        return new SearchResult(identifier, title, artist, thumbnail, duration, info.isStream);
    }

    private String inferYoutubeThumbnail(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }

        Matcher matcher = YOUTUBE_WATCH_PATTERN.matcher(source);
        if (matcher.find()) {
            return "https://i.ytimg.com/vi/" + matcher.group(1) + "/hqdefault.jpg";
        }

        Matcher vMatcher = YOUTUBE_V_PARAM_PATTERN.matcher(source);
        if (vMatcher.find()) {
            return "https://i.ytimg.com/vi/" + vMatcher.group(1) + "/hqdefault.jpg";
        }

        if (source.matches("^[A-Za-z0-9_-]{11}$")) {
            return "https://i.ytimg.com/vi/" + source + "/hqdefault.jpg";
        }
        return "";
    }

    public record OpenedTrack(AudioPlayer player, AudioInputStream stream, AudioTrack track, AudioTrackInfo info, long durationMs) {
    }

    public record SearchResult(String identifier, String title, String artist, String thumbnail, long durationMs, boolean stream) {
    }
}
