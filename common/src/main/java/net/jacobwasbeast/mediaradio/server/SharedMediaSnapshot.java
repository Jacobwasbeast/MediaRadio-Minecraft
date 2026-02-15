package net.jacobwasbeast.mediaradio.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SharedMediaSnapshot {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int MAX_JSON_LENGTH = 256_000;

    public final Map<String, MediaEntry> library = new LinkedHashMap<>();
    public final Map<String, PlaylistEntry> playlists = new LinkedHashMap<>();

    public String toJson() {
        return GSON.toJson(this);
    }

    public static SharedMediaSnapshot fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new SharedMediaSnapshot();
        }
        try {
            SharedMediaSnapshot parsed = GSON.fromJson(json, SharedMediaSnapshot.class);
            return parsed == null ? new SharedMediaSnapshot() : parsed.sanitize();
        } catch (Exception ignored) {
            return new SharedMediaSnapshot();
        }
    }

    public SharedMediaSnapshot sanitize() {
        library.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().url.isBlank());
        playlists.entrySet().removeIf(entry -> entry.getValue() == null);
        library.values().forEach(MediaEntry::sanitize);
        playlists.values().forEach(PlaylistEntry::sanitize);
        return this;
    }

    public MediaEntry upsertMedia(String url, String title, String artist, String thumbnail, List<String> tags) {
        String id = idForUrl(url);
        MediaEntry entry = library.computeIfAbsent(id, ignored -> new MediaEntry());
        entry.id = id;
        entry.url = safe(url);
        entry.title = safe(title);
        entry.artist = safe(artist);
        entry.thumbnail = safe(thumbnail);
        entry.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
        entry.sanitize();
        return entry;
    }

    public PlaylistEntry createPlaylist(String name) {
        String id = "playlist_" + Integer.toHexString(Math.abs(name.hashCode())) + "_" + Integer.toHexString(playlists.size() + 1);
        PlaylistEntry playlistEntry = new PlaylistEntry();
        playlistEntry.id = id;
        playlistEntry.name = safe(name);
        playlists.put(id, playlistEntry);
        return playlistEntry;
    }

    @NotNull
    public static String idForUrl(String url) {
        String safe = safe(url);
        if (safe.isBlank()) {
            return "media_empty";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(safe.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("media_");
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception ignored) {
            return "media_" + Integer.toHexString(safe.hashCode());
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static class MediaEntry {
        public String id = "";
        public String url = "";
        public String title = "";
        public String artist = "";
        public String thumbnail = "";
        public List<String> tags = new ArrayList<>();

        public void sanitize() {
            id = safe(id);
            url = safe(url);
            title = safe(title);
            artist = safe(artist);
            thumbnail = safe(thumbnail);
            if (tags == null) {
                tags = new ArrayList<>();
            }
        }
    }

    public static class PlaylistEntry {
        public String id = "";
        public String name = "";
        public String thumbnail = "";
        public List<String> mediaIds = new ArrayList<>();

        public void sanitize() {
            id = safe(id);
            name = safe(name);
            thumbnail = safe(thumbnail);
            if (mediaIds == null) {
                mediaIds = new ArrayList<>();
            }
        }
    }
}
