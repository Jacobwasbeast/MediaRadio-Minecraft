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
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LavaPlayerAccess {

    public static final int SAMPLE_RATE = 48000;
    public static final int BYTES_PER_SECOND = SAMPLE_RATE * 2 * 2;
    private static final Pattern YOUTUBE_WATCH_PATTERN = Pattern.compile("(?:youtu\\.be/|youtube\\.com/(?:watch\\?v=|shorts/|live/))([A-Za-z0-9_-]{11})");
    private static final Pattern YOUTUBE_V_PARAM_PATTERN = Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})");

    private static final LavaPlayerAccess INSTANCE = new LavaPlayerAccess();

    private final AudioDataFormat audioDataFormat = new Pcm16AudioDataFormat(2, SAMPLE_RATE, 960, false);
    private final AudioPlayerManager audioPlayerManager;

    private LavaPlayerAccess() {
        audioPlayerManager = new DefaultAudioPlayerManager();
        audioPlayerManager.setFrameBufferDuration(1000);
        audioPlayerManager.setPlayerCleanupThreshold(Long.MAX_VALUE);
        audioPlayerManager.getConfiguration().setResamplingQuality(AudioConfiguration.ResamplingQuality.HIGH);
        audioPlayerManager.getConfiguration().setOutputFormat(audioDataFormat);
        AudioSourceManagers.registerLocalSource(audioPlayerManager);
        tryRegisterYoutubeSource();
        registerNonYoutubeRemoteSources();
        logRegisteredSourceManagers();
    }

    public static LavaPlayerAccess get() {
        return INSTANCE;
    }

    public CompletableFuture<AudioTrack> loadTrack(String identifier) {
        String normalizedIdentifier = normalizeIdentifier(identifier);
        CompletableFuture<AudioTrack> future = new CompletableFuture<>();
        audioPlayerManager.loadItemOrdered(this, normalizedIdentifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                future.complete(track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.getSelectedTrack() != null) {
                    future.complete(playlist.getSelectedTrack());
                } else if (!playlist.getTracks().isEmpty()) {
                    future.complete(playlist.getTracks().get(0));
                } else {
                    future.complete(null);
                }
            }

            @Override
            public void noMatches() {
                future.complete(null);
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                future.completeExceptionally(exception);
            }
        });
        return future.orTimeout(20, TimeUnit.SECONDS);
    }

    public CompletableFuture<List<SearchResult>> searchYoutube(String query, int maxResults) {
        String safeQuery = query == null ? "" : query.trim();
        if (safeQuery.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }

        int limit = Math.max(1, maxResults);
        CompletableFuture<List<SearchResult>> future = new CompletableFuture<>();
        audioPlayerManager.loadItemOrdered(this, "ytsearch:" + safeQuery, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                future.complete(List.of(toSearchResult(track)));
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
                future.complete(results);
            }

            @Override
            public void noMatches() {
                future.complete(List.of());
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                future.completeExceptionally(exception);
            }
        });
        return future.orTimeout(20, TimeUnit.SECONDS);
    }

    public CompletableFuture<List<SearchResult>> loadPlaylistTracks(String identifier, int maxTracks) {
        String normalizedIdentifier = normalizeIdentifier(identifier);
        if (normalizedIdentifier.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }

        int limit = Math.max(1, maxTracks);
        CompletableFuture<List<SearchResult>> future = new CompletableFuture<>();
        audioPlayerManager.loadItemOrdered(this, normalizedIdentifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                future.complete(List.of(toSearchResult(track)));
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
                future.complete(results);
            }

            @Override
            public void noMatches() {
                future.complete(List.of());
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                future.completeExceptionally(exception);
            }
        });
        return future.orTimeout(25, TimeUnit.SECONDS);
    }

    public OpenedTrack openTrack(AudioTrack sourceTrack, long positionMs) {
        AudioPlayer audioPlayer = audioPlayerManager.createPlayer();
        AudioTrack track = sourceTrack.makeClone();
        if (positionMs > 0L) {
            track.setPosition(positionMs);
        }
        long durationMs = sourceTrack.getDuration();
        if (durationMs <= 0L) {
            durationMs = track.getDuration();
        }
        audioPlayer.startTrack(track, false);
        AudioInputStream stream = AudioPlayerInputStream.createStream(audioPlayer, audioDataFormat, 3000L, true);
        return new OpenedTrack(audioPlayer, stream, track, track.getInfo(), durationMs);
    }

    private void tryRegisterYoutubeSource() {
        try {
            // Exact same construction style as IamMusicPlayer_FIX-1.20.1.
            YoutubeAudioSourceManager sourceManager = new YoutubeAudioSourceManager();
            audioPlayerManager.registerSourceManager((AudioSourceManager) sourceManager);
        } catch (Exception exception) {
            MediaRadio.LOGGER.warn("YouTube source manager not available, YouTube playback may fail", exception);
        }
    }

    private void registerNonYoutubeRemoteSources() {
        // Do NOT use AudioSourceManagers.registerRemoteSources(...) with Lavaplayer 2.2.6.
        // It auto-registers the legacy Lavaplayer YouTube source and causes cipher failures.
        audioPlayerManager.registerSourceManager(new HttpAudioSourceManager());
    }

    private void logRegisteredSourceManagers() {
        String sourceList = audioPlayerManager.getSourceManagers().stream()
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
