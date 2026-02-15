package net.jacobwasbeast.mediaradio.client.media;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.jacobwasbeast.mediaradio.client.audio.LavaPlayerAccess;
import net.jacobwasbeast.mediaradio.client.data.ClientMediaRepository;
import net.jacobwasbeast.mediaradio.server.SharedMediaSnapshot;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MediaMetadataResolver {

    private static final Pattern YOUTUBE_WATCH_PATTERN = Pattern.compile("(?:youtu\\.be/|youtube\\.com/(?:watch\\?v=|shorts/|live/))([A-Za-z0-9_-]{11})");
    private static final Pattern YOUTUBE_V_PARAM_PATTERN = Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})");

    private MediaMetadataResolver() {
    }

    public static CompletableFuture<ResolvedMediaInfo> resolve(String url, String requestedTitle, String requestedArtist, String requestedThumbnail) {
        String safeUrl = safe(url);
        if (safeUrl.isBlank()) {
            return CompletableFuture.completedFuture(new ResolvedMediaInfo("", "", "", ""));
        }

        String title = safe(requestedTitle);
        String artist = safe(requestedArtist);
        String thumbnail = safe(requestedThumbnail);

        SharedMediaSnapshot.MediaEntry existing = ClientMediaRepository.getInstance().findByUrl(safeUrl);
        if (existing != null) {
            if (title.isBlank()) {
                title = safe(existing.title);
            }
            if (artist.isBlank()) {
                artist = safe(existing.artist);
            }
            if (thumbnail.isBlank()) {
                thumbnail = safe(existing.thumbnail);
            }
        }

        if (thumbnail.isBlank()) {
            thumbnail = inferYoutubeThumbnail(safeUrl);
        }

        if (!title.isBlank() && !artist.isBlank() && !thumbnail.isBlank()) {
            return CompletableFuture.completedFuture(new ResolvedMediaInfo(safeUrl, title, artist, thumbnail));
        }

        final String currentTitle = title;
        final String currentArtist = artist;
        final String currentThumbnail = thumbnail;

        return LavaPlayerAccess.get().loadTrack(safeUrl)
                .handle((track, error) -> mergeInfo(safeUrl, currentTitle, currentArtist, currentThumbnail, track));
    }

    public static String bestThumbnail(String requestedThumbnail, String url) {
        String provided = normalizeThumbnailUrl(safe(requestedThumbnail));
        if (!provided.isBlank()) {
            return provided;
        }
        return inferYoutubeThumbnail(url);
    }

    private static ResolvedMediaInfo mergeInfo(String url, String title, String artist, String thumbnail, AudioTrack track) {
        String resolvedTitle = title;
        String resolvedArtist = artist;
        String resolvedThumbnail = thumbnail;

        if (track != null) {
            AudioTrackInfo info = track.getInfo();
            if (resolvedTitle.isBlank()) {
                resolvedTitle = safe(info.title);
            }
            if (resolvedArtist.isBlank()) {
                resolvedArtist = safe(info.author);
            }
            if (resolvedThumbnail.isBlank()) {
                resolvedThumbnail = inferYoutubeThumbnail(safe(info.uri));
            }
        }

        if (resolvedTitle.isBlank()) {
            resolvedTitle = url;
        }
        if (resolvedArtist.isBlank()) {
            resolvedArtist = "Unknown Artist";
        }
        return new ResolvedMediaInfo(url, resolvedTitle, resolvedArtist, resolvedThumbnail);
    }

    private static String inferYoutubeThumbnail(String value) {
        String source = safe(value);
        if (source.isBlank()) {
            return "";
        }

        String id = extractYoutubeId(source);
        if (id.isBlank()) {
            return "";
        }
        return "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
    }

    private static String normalizeThumbnailUrl(String value) {
        if (value.isBlank()) {
            return value;
        }
        String normalized = value;
        if (normalized.contains("i.yting.com")) {
            normalized = normalized.replace("i.yting.com", "i.ytimg.com");
        }
        return normalized;
    }

    private static String extractYoutubeId(String source) {
        Matcher matcher = YOUTUBE_WATCH_PATTERN.matcher(source);
        if (matcher.find()) {
            return matcher.group(1);
        }

        Matcher vMatcher = YOUTUBE_V_PARAM_PATTERN.matcher(source);
        if (vMatcher.find()) {
            return vMatcher.group(1);
        }

        if (source.matches("^[A-Za-z0-9_-]{11}$")) {
            return source;
        }

        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record ResolvedMediaInfo(String url, String title, String artist, String thumbnail) {
    }
}
