package net.jacobwasbeast.mediaradio.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public class SharedMediaSnapshot {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int MAX_JSON_LENGTH = 4_000_000;

    public final Map<String, MediaEntry> library = new LinkedHashMap<>();
    public final Map<String, PlaylistEntry> playlists = new LinkedHashMap<>();
    public final List<String> deletedPlaylistIds = new ArrayList<>();

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
        if (deletedPlaylistIds != null) {
            deletedPlaylistIds.replaceAll(SharedMediaSnapshot::safe);
            deletedPlaylistIds.removeIf(String::isBlank);
            List<String> deduplicated = new ArrayList<>();
            for (String playlistId : deletedPlaylistIds) {
                if (!deduplicated.contains(playlistId)) {
                    deduplicated.add(playlistId);
                }
            }
            deletedPlaylistIds.clear();
            deletedPlaylistIds.addAll(deduplicated);
        }
        return this;
    }

    public MediaEntry upsertMedia(String url, String title, String artist, String thumbnail, List<String> tags) {
        return upsertMedia(url, title, artist, thumbnail, tags, false);
    }

    public MediaEntry upsertMedia(String url, String title, String artist, String thumbnail, List<String> tags, boolean hiddenFromLibrary) {
        String id = idForUrl(url);
        boolean existed = library.containsKey(id);
        MediaEntry entry = library.computeIfAbsent(id, ignored -> new MediaEntry());
        entry.id = id;
        entry.url = safe(url);
        entry.title = safe(title);
        entry.artist = safe(artist);
        entry.thumbnail = safe(thumbnail);
        entry.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
        // `hiddenFromLibrary` means playlist-only entry.
        // Never hide an already-visible entry when importing playlists.
        if (hiddenFromLibrary) {
            if (!existed) {
                entry.hiddenFromLibrary = true;
            }
        } else {
            entry.hiddenFromLibrary = false;
        }
        entry.sanitize();
        return entry;
    }

    public PlaylistEntry createPlaylist(String name, String ownerId, String ownerName, PlaylistAccess access) {
        String id;
        do {
            id = "playlist_" + UUID.randomUUID().toString().replace("-", "");
        } while (playlists.containsKey(id));
        PlaylistEntry playlistEntry = new PlaylistEntry();
        playlistEntry.id = id;
        playlistEntry.name = safe(name);
        playlistEntry.ownerId = safe(ownerId);
        playlistEntry.ownerName = safe(ownerName);
        playlistEntry.access = access == null ? PlaylistAccess.PRIVATE : access;
        playlists.put(id, playlistEntry);
        return playlistEntry;
    }

    public PlaylistEntry createPlaylist(String name) {
        return createPlaylist(name, "", "", PlaylistAccess.PRIVATE);
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
        public boolean hiddenFromLibrary = false;
        public String ownerId = "";

        public void sanitize() {
            id = safe(id);
            url = safe(url);
            title = safe(title);
            artist = safe(artist);
            thumbnail = safe(thumbnail);
            ownerId = safe(ownerId);
            if (tags == null) {
                tags = new ArrayList<>();
            }
        }

        public boolean isOwnedBy(String playerId) {
            String safePlayerId = safe(playerId);
            return !ownerId.isBlank() && ownerId.equals(safePlayerId);
        }
    }

    public static class PlaylistEntry {
        public String id = "";
        public String name = "";
        public String thumbnail = "";
        public List<String> mediaIds = new ArrayList<>();
        public String ownerId = "";
        public String ownerName = "";
        public PlaylistAccess access = PlaylistAccess.PRIVATE;
        public List<String> invitedPlayerIds = new ArrayList<>();
        public List<String> invitedPlayerNames = new ArrayList<>();

        public void sanitize() {
            id = safe(id);
            name = safe(name);
            thumbnail = safe(thumbnail);
            ownerId = safe(ownerId);
            ownerName = safe(ownerName);
            if (access == null) {
                access = PlaylistAccess.PRIVATE;
            }
            if (mediaIds == null) {
                mediaIds = new ArrayList<>();
            }
            if (invitedPlayerIds == null) {
                invitedPlayerIds = new ArrayList<>();
            }
            if (invitedPlayerNames == null) {
                invitedPlayerNames = new ArrayList<>();
            }
            invitedPlayerIds.replaceAll(SharedMediaSnapshot::safe);
            invitedPlayerNames.replaceAll(SharedMediaSnapshot::safe);
            invitedPlayerIds.removeIf(String::isBlank);
            invitedPlayerNames.removeIf(String::isBlank);
            if (ownerId.isBlank() && !ownerName.isBlank()) {
                // Legacy/default playlists without explicit owner remain editable by everyone.
                access = PlaylistAccess.GLOBAL;
            }
        }

        public boolean isOwnedBy(String playerId) {
            String safePlayerId = safe(playerId);
            return !ownerId.isBlank() && ownerId.equals(safePlayerId);
        }

        public boolean canEdit(String playerId) {
            if (ownerId.isBlank()) {
                return true;
            }
            return isOwnedBy(playerId);
        }

        public boolean canView(String playerId, String playerName) {
            if (ownerId.isBlank()) {
                return true;
            }
            if (isOwnedBy(playerId)) {
                return true;
            }
            return switch (access) {
                case GLOBAL -> true;
                case PRIVATE -> false;
                case INVITES -> isInvited(playerId, playerName);
            };
        }

        public boolean isInvited(String playerId, String playerName) {
            String safePlayerId = safe(playerId);
            if (!safePlayerId.isBlank() && invitedPlayerIds.stream().anyMatch(id -> id.equals(safePlayerId))) {
                return true;
            }
            String normalizedName = safe(playerName).toLowerCase(Locale.ROOT);
            if (normalizedName.isBlank()) {
                return false;
            }
            return invitedPlayerNames.stream()
                    .filter(Objects::nonNull)
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .anyMatch(name -> name.equals(normalizedName));
        }

        public void setInvites(List<String> playerNames) {
            invitedPlayerNames.clear();
            invitedPlayerIds.clear();
            if (playerNames == null) {
                return;
            }
            for (String name : playerNames) {
                String safeName = safe(name);
                if (!safeName.isBlank() && !invitedPlayerNames.contains(safeName)) {
                    invitedPlayerNames.add(safeName);
                }
            }
        }

        public List<String> invitesView() {
            if (invitedPlayerNames.isEmpty()) {
                return Collections.emptyList();
            }
            return Collections.unmodifiableList(invitedPlayerNames);
        }
    }

    public enum PlaylistAccess {
        PRIVATE,
        INVITES,
        GLOBAL;

        public String label() {
            return switch (this) {
                case PRIVATE -> "Private";
                case INVITES -> "Invites";
                case GLOBAL -> "Global";
            };
        }
    }
}
