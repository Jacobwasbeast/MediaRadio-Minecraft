package net.jacobwasbeast.mediaradio.client.media;

import net.jacobwasbeast.mediaradio.server.SharedMediaSnapshot;

public final class PlaybackDisplayResolver {

    private PlaybackDisplayResolver() {
    }

    public static DisplayInfo resolve(
            String playbackUrl,
            String title,
            String artist,
            String thumbnail,
            SharedMediaSnapshot.MediaEntry currentQueueEntry
    ) {
        String resolvedUrl = safe(playbackUrl);
        String resolvedTitle = safe(title);
        String resolvedArtist = safe(artist);
        String resolvedThumbnail = safe(thumbnail);

        if (currentQueueEntry == null) {
            return new DisplayInfo(resolvedTitle, resolvedArtist, resolvedThumbnail);
        }

        String queueUrl = safe(currentQueueEntry.url);
        String queueTitle = safe(currentQueueEntry.title);
        if (queueTitle.isBlank()) {
            queueTitle = queueUrl;
        }
        String queueArtist = safe(currentQueueEntry.artist);
        String queueThumbnail = MediaMetadataResolver.bestThumbnail(currentQueueEntry.thumbnail, queueUrl);

        boolean titleLooksLikeUrl = resolvedTitle.startsWith("http://") || resolvedTitle.startsWith("https://");
        boolean artistMissing = resolvedArtist.isBlank() || "Unknown Artist".equalsIgnoreCase(resolvedArtist);
        boolean likelySameTrack = urlsMatch(resolvedUrl, queueUrl);

        if (!likelySameTrack && !titleLooksLikeUrl && !artistMissing) {
            return new DisplayInfo(resolvedTitle, resolvedArtist, resolvedThumbnail);
        }

        if (!queueTitle.isBlank() && (resolvedTitle.isBlank() || titleLooksLikeUrl || likelySameTrack)) {
            resolvedTitle = queueTitle;
        }
        if (!queueArtist.isBlank() && (artistMissing || likelySameTrack)) {
            resolvedArtist = queueArtist;
        }
        if (!queueThumbnail.isBlank() && (resolvedThumbnail.isBlank() || likelySameTrack)) {
            resolvedThumbnail = queueThumbnail;
        }

        return new DisplayInfo(resolvedTitle, resolvedArtist, resolvedThumbnail);
    }

    private static boolean urlsMatch(String first, String second) {
        String a = normalizeUrlForCompare(first);
        String b = normalizeUrlForCompare(second);
        if (a.isBlank() || b.isBlank()) {
            return false;
        }
        if (a.equalsIgnoreCase(b)) {
            return true;
        }
        String aThumb = MediaMetadataResolver.bestThumbnail("", a);
        String bThumb = MediaMetadataResolver.bestThumbnail("", b);
        return !aThumb.isBlank() && aThumb.equalsIgnoreCase(bThumb);
    }

    private static String normalizeUrlForCompare(String value) {
        String normalized = safe(value);
        int fragmentIndex = normalized.indexOf('#');
        if (fragmentIndex >= 0) {
            normalized = normalized.substring(0, fragmentIndex);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record DisplayInfo(
            String title,
            String artist,
            String thumbnail
    ) {
    }
}
