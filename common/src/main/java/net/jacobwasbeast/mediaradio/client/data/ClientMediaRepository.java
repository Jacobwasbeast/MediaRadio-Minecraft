package net.jacobwasbeast.mediaradio.client.data;

import net.jacobwasbeast.mediaradio.MediaRadio;
import net.jacobwasbeast.mediaradio.network.ModNetworking;
import net.jacobwasbeast.mediaradio.server.SharedMediaSnapshot;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class ClientMediaRepository {

    private static final ClientMediaRepository INSTANCE = new ClientMediaRepository();

    private SharedMediaSnapshot snapshot = new SharedMediaSnapshot();
    private final List<String> queueMediaIds = new ArrayList<>();
    private int queueIndex = -1;

    public static ClientMediaRepository getInstance() {
        return INSTANCE;
    }

    public static void initialize() {
        INSTANCE.loadCache();
    }

    public static void applyServerSnapshot(String json) {
        INSTANCE.applySnapshotJson(json, true);
    }

    public synchronized void reset() {
        snapshot = new SharedMediaSnapshot();
        queueMediaIds.clear();
        queueIndex = -1;
    }

    public synchronized SharedMediaSnapshot getSnapshot() {
        return snapshot;
    }

    public synchronized List<SharedMediaSnapshot.MediaEntry> getSortedLibrary() {
        return snapshot.library.values().stream()
                .sorted(Comparator.comparing(entry -> entry.title == null || entry.title.isBlank() ? entry.url : entry.title, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public synchronized List<SharedMediaSnapshot.PlaylistEntry> getSortedPlaylists() {
        return snapshot.playlists.values().stream()
                .sorted(Comparator.comparing(entry -> entry.name == null || entry.name.isBlank() ? entry.id : entry.name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public synchronized List<SharedMediaSnapshot.MediaEntry> getPlaylistMedia(String playlistId) {
        SharedMediaSnapshot.PlaylistEntry playlistEntry = snapshot.playlists.get(playlistId);
        if (playlistEntry == null || playlistEntry.mediaIds == null) {
            return List.of();
        }

        List<SharedMediaSnapshot.MediaEntry> entries = new ArrayList<>();
        for (String mediaId : playlistEntry.mediaIds) {
            SharedMediaSnapshot.MediaEntry mediaEntry = snapshot.library.get(mediaId);
            if (mediaEntry != null) {
                entries.add(mediaEntry);
            }
        }
        return entries;
    }

    public synchronized SharedMediaSnapshot.MediaEntry upsertMedia(String url, String title, String artist, String thumbnail, List<String> tags) {
        SharedMediaSnapshot.MediaEntry mediaEntry = snapshot.upsertMedia(url, title, artist, thumbnail, tags);
        persistAndUpload();
        return mediaEntry;
    }

    public synchronized SharedMediaSnapshot.MediaEntry findByUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        return snapshot.library.get(SharedMediaSnapshot.idForUrl(url));
    }

    public synchronized void removeMedia(String mediaId) {
        snapshot.library.remove(mediaId);
        snapshot.playlists.values().forEach(playlist -> playlist.mediaIds.removeIf(mediaId::equals));
        queueMediaIds.removeIf(mediaId::equals);
        if (queueIndex >= queueMediaIds.size()) {
            queueIndex = queueMediaIds.size() - 1;
        }
        persistAndUpload();
    }

    public synchronized String createPlaylist(String name) {
        SharedMediaSnapshot.PlaylistEntry playlistEntry = snapshot.createPlaylist(name);
        persistAndUpload();
        return playlistEntry.id;
    }

    public synchronized void deletePlaylist(String playlistId) {
        snapshot.playlists.remove(playlistId);
        persistAndUpload();
    }

    public synchronized void addMediaToPlaylist(String playlistId, String mediaId) {
        SharedMediaSnapshot.PlaylistEntry playlistEntry = snapshot.playlists.get(playlistId);
        if (playlistEntry == null || !snapshot.library.containsKey(mediaId)) {
            return;
        }
        if (!playlistEntry.mediaIds.contains(mediaId)) {
            playlistEntry.mediaIds.add(mediaId);
            persistAndUpload();
        }
    }

    public synchronized void removeMediaFromPlaylist(String playlistId, int index) {
        SharedMediaSnapshot.PlaylistEntry playlistEntry = snapshot.playlists.get(playlistId);
        if (playlistEntry == null || index < 0 || index >= playlistEntry.mediaIds.size()) {
            return;
        }
        playlistEntry.mediaIds.remove(index);
        persistAndUpload();
    }

    public synchronized void setQueueFromPlaylist(String playlistId) {
        SharedMediaSnapshot.PlaylistEntry playlistEntry = snapshot.playlists.get(playlistId);
        queueMediaIds.clear();
        queueIndex = -1;
        if (playlistEntry == null || playlistEntry.mediaIds == null || playlistEntry.mediaIds.isEmpty()) {
            return;
        }
        queueMediaIds.addAll(playlistEntry.mediaIds.stream().filter(snapshot.library::containsKey).toList());
        queueIndex = queueMediaIds.isEmpty() ? -1 : 0;
    }

    public synchronized void enqueue(String mediaId) {
        if (!snapshot.library.containsKey(mediaId)) {
            return;
        }
        queueMediaIds.add(mediaId);
        if (queueIndex < 0) {
            queueIndex = 0;
        }
    }

    public synchronized SharedMediaSnapshot.MediaEntry getCurrentQueueEntry() {
        if (queueIndex < 0 || queueIndex >= queueMediaIds.size()) {
            return null;
        }
        return snapshot.library.get(queueMediaIds.get(queueIndex));
    }

    public synchronized List<SharedMediaSnapshot.MediaEntry> getQueueEntries() {
        List<SharedMediaSnapshot.MediaEntry> entries = new ArrayList<>();
        for (String mediaId : queueMediaIds) {
            SharedMediaSnapshot.MediaEntry entry = snapshot.library.get(mediaId);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    public synchronized int getQueueIndex() {
        return queueIndex;
    }

    public synchronized SharedMediaSnapshot.MediaEntry setQueueIndex(int index) {
        if (index < 0 || index >= queueMediaIds.size()) {
            return null;
        }
        queueIndex = index;
        return getCurrentQueueEntry();
    }

    public synchronized void removeQueueIndex(int index) {
        if (index < 0 || index >= queueMediaIds.size()) {
            return;
        }

        queueMediaIds.remove(index);
        if (queueMediaIds.isEmpty()) {
            queueIndex = -1;
            return;
        }

        if (queueIndex > index) {
            queueIndex--;
        } else if (queueIndex >= queueMediaIds.size()) {
            queueIndex = queueMediaIds.size() - 1;
        }
    }

    public synchronized void moveQueueIndex(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= queueMediaIds.size() || toIndex < 0 || toIndex >= queueMediaIds.size()) {
            return;
        }
        if (fromIndex == toIndex) {
            return;
        }

        String mediaId = queueMediaIds.remove(fromIndex);
        queueMediaIds.add(toIndex, mediaId);

        if (queueIndex == fromIndex) {
            queueIndex = toIndex;
        } else if (fromIndex < queueIndex && toIndex >= queueIndex) {
            queueIndex--;
        } else if (fromIndex > queueIndex && toIndex <= queueIndex) {
            queueIndex++;
        }
    }

    public synchronized SharedMediaSnapshot.MediaEntry nextQueueEntry() {
        if (queueMediaIds.isEmpty()) {
            return null;
        }
        queueIndex++;
        if (queueIndex >= queueMediaIds.size()) {
            queueIndex = 0;
        }
        return getCurrentQueueEntry();
    }

    public synchronized SharedMediaSnapshot.MediaEntry previousQueueEntry() {
        if (queueMediaIds.isEmpty()) {
            return null;
        }
        queueIndex--;
        if (queueIndex < 0) {
            queueIndex = queueMediaIds.size() - 1;
        }
        return getCurrentQueueEntry();
    }

    public synchronized void uploadSnapshotNow() {
        ModNetworking.uploadSharedSnapshot(snapshot.toJson());
    }

    private synchronized void applySnapshotJson(String json, boolean saveCache) {
        snapshot = SharedMediaSnapshot.fromJson(json);
        if (saveCache) {
            saveCache();
        }
    }

    private synchronized void persistAndUpload() {
        snapshot.sanitize();
        saveCache();
        ModNetworking.uploadSharedSnapshot(snapshot.toJson());
    }

    private void loadCache() {
        Path cacheFile = getCacheFile();
        if (!Files.exists(cacheFile)) {
            return;
        }
        try {
            String json = Files.readString(cacheFile);
            applySnapshotJson(json, false);
        } catch (IOException exception) {
            MediaRadio.LOGGER.warn("Failed to load client media cache", exception);
        }
    }

    private void saveCache() {
        Path cacheFile = getCacheFile();
        try {
            Files.createDirectories(Objects.requireNonNull(cacheFile.getParent()));
            Files.writeString(cacheFile, snapshot.toJson());
        } catch (IOException exception) {
            MediaRadio.LOGGER.warn("Failed to save client media cache", exception);
        }
    }

    private Path getCacheFile() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("mediaradio-client-cache.json");
    }
}
