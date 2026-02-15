package net.jacobwasbeast.mediaradio.client.data;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import net.jacobwasbeast.mediaradio.MediaRadio;
import net.jacobwasbeast.mediaradio.network.ModNetworking;
import net.jacobwasbeast.mediaradio.server.SharedMediaSnapshot;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ClientMediaRepository {

    private static final ClientMediaRepository INSTANCE = new ClientMediaRepository();
    private static final String DEFAULT_RADIO_ID = "default";

    private final Gson gson = new Gson();
    private SharedMediaSnapshot snapshot = new SharedMediaSnapshot();
    private final Map<String, QueueState> queuesByRadioId = new HashMap<>();
    private String activeRadioId = DEFAULT_RADIO_ID;

    public static ClientMediaRepository getInstance() {
        return INSTANCE;
    }

    public static void initialize() {
        INSTANCE.loadCache();
    }

    public static void applyServerSnapshot(String json) {
        INSTANCE.applySnapshotJson(json, true);
    }

    public synchronized void setActiveRadioId(String radioId) {
        String safe = radioId == null || radioId.isBlank() ? DEFAULT_RADIO_ID : radioId;
        activeRadioId = safe;
        queuesByRadioId.computeIfAbsent(activeRadioId, ignored -> new QueueState());
        sanitizeQueue(activeQueueState());
    }

    public synchronized String getActiveRadioId() {
        return activeRadioId;
    }

    public synchronized void reset() {
        snapshot = new SharedMediaSnapshot();
        queuesByRadioId.clear();
        activeRadioId = DEFAULT_RADIO_ID;
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
        for (QueueState queueState : queuesByRadioId.values()) {
            queueState.queueMediaIds.removeIf(mediaId::equals);
            sanitizeQueue(queueState);
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
        QueueState queueState = activeQueueState();
        SharedMediaSnapshot.PlaylistEntry playlistEntry = snapshot.playlists.get(playlistId);
        queueState.queueMediaIds.clear();
        queueState.queueIndex = -1;
        if (playlistEntry == null || playlistEntry.mediaIds == null || playlistEntry.mediaIds.isEmpty()) {
            return;
        }
        queueState.queueMediaIds.addAll(playlistEntry.mediaIds.stream().filter(snapshot.library::containsKey).toList());
        queueState.queueIndex = queueState.queueMediaIds.isEmpty() ? -1 : 0;
    }

    public synchronized void enqueue(String mediaId) {
        QueueState queueState = activeQueueState();
        if (!snapshot.library.containsKey(mediaId)) {
            return;
        }
        queueState.queueMediaIds.add(mediaId);
        if (queueState.queueIndex < 0) {
            queueState.queueIndex = 0;
        }
    }

    public synchronized SharedMediaSnapshot.MediaEntry getCurrentQueueEntry() {
        QueueState queueState = activeQueueState();
        if (queueState.queueIndex < 0 || queueState.queueIndex >= queueState.queueMediaIds.size()) {
            return null;
        }
        return snapshot.library.get(queueState.queueMediaIds.get(queueState.queueIndex));
    }

    public synchronized List<SharedMediaSnapshot.MediaEntry> getQueueEntries() {
        QueueState queueState = activeQueueState();
        List<SharedMediaSnapshot.MediaEntry> entries = new ArrayList<>();
        for (String mediaId : queueState.queueMediaIds) {
            SharedMediaSnapshot.MediaEntry entry = snapshot.library.get(mediaId);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    public synchronized int getQueueIndex() {
        return activeQueueState().queueIndex;
    }

    public synchronized SharedMediaSnapshot.MediaEntry setQueueIndex(int index) {
        QueueState queueState = activeQueueState();
        if (index < 0 || index >= queueState.queueMediaIds.size()) {
            return null;
        }
        queueState.queueIndex = index;
        return getCurrentQueueEntry();
    }

    public synchronized void removeQueueIndex(int index) {
        QueueState queueState = activeQueueState();
        if (index < 0 || index >= queueState.queueMediaIds.size()) {
            return;
        }

        queueState.queueMediaIds.remove(index);
        sanitizeQueue(queueState);
    }

    public synchronized void moveQueueIndex(int fromIndex, int toIndex) {
        QueueState queueState = activeQueueState();
        if (fromIndex < 0 || fromIndex >= queueState.queueMediaIds.size() || toIndex < 0 || toIndex >= queueState.queueMediaIds.size()) {
            return;
        }
        if (fromIndex == toIndex) {
            return;
        }

        String mediaId = queueState.queueMediaIds.remove(fromIndex);
        queueState.queueMediaIds.add(toIndex, mediaId);

        if (queueState.queueIndex == fromIndex) {
            queueState.queueIndex = toIndex;
        } else if (fromIndex < queueState.queueIndex && toIndex >= queueState.queueIndex) {
            queueState.queueIndex--;
        } else if (fromIndex > queueState.queueIndex && toIndex <= queueState.queueIndex) {
            queueState.queueIndex++;
        }
    }

    public synchronized SharedMediaSnapshot.MediaEntry nextQueueEntry() {
        QueueState queueState = activeQueueState();
        if (queueState.queueMediaIds.isEmpty()) {
            return null;
        }
        queueState.queueIndex++;
        if (queueState.queueIndex >= queueState.queueMediaIds.size()) {
            queueState.queueIndex = 0;
        }
        return getCurrentQueueEntry();
    }

    public synchronized SharedMediaSnapshot.MediaEntry previousQueueEntry() {
        QueueState queueState = activeQueueState();
        if (queueState.queueMediaIds.isEmpty()) {
            return null;
        }
        queueState.queueIndex--;
        if (queueState.queueIndex < 0) {
            queueState.queueIndex = queueState.queueMediaIds.size() - 1;
        }
        return getCurrentQueueEntry();
    }

    public synchronized void uploadSnapshotNow() {
        ModNetworking.uploadSharedSnapshot(snapshot.toJson());
    }

    private synchronized void applySnapshotJson(String json, boolean saveCache) {
        snapshot = SharedMediaSnapshot.fromJson(json);
        for (QueueState queueState : queuesByRadioId.values()) {
            sanitizeQueue(queueState);
        }
        if (saveCache) {
            saveCache();
        }
    }

    private synchronized void persistAndUpload() {
        snapshot.sanitize();
        for (QueueState queueState : queuesByRadioId.values()) {
            sanitizeQueue(queueState);
        }
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
            tryLoadCache(json);
        } catch (IOException exception) {
            MediaRadio.LOGGER.warn("Failed to load client media cache", exception);
        }
    }

    private synchronized void tryLoadCache(String json) {
        try {
            CacheModel cacheModel = gson.fromJson(json, CacheModel.class);
            if (cacheModel != null) {
                if (cacheModel.snapshotJson != null && !cacheModel.snapshotJson.isBlank()) {
                    snapshot = SharedMediaSnapshot.fromJson(cacheModel.snapshotJson);
                }

                activeRadioId = cacheModel.activeRadioId == null || cacheModel.activeRadioId.isBlank() ? DEFAULT_RADIO_ID : cacheModel.activeRadioId;
                queuesByRadioId.clear();
                if (cacheModel.queuesByRadioId != null) {
                    for (Map.Entry<String, QueueStateModel> entry : cacheModel.queuesByRadioId.entrySet()) {
                        QueueStateModel model = entry.getValue();
                        QueueState queueState = new QueueState();
                        if (model != null && model.queueMediaIds != null) {
                            queueState.queueMediaIds.addAll(model.queueMediaIds);
                        }
                        queueState.queueIndex = model == null ? -1 : model.queueIndex;
                        sanitizeQueue(queueState);
                        queuesByRadioId.put(entry.getKey(), queueState);
                    }
                }

                // Migration from previous per-radio cache format.
                if ((cacheModel.snapshotJson == null || cacheModel.snapshotJson.isBlank()) && cacheModel.radios != null && !cacheModel.radios.isEmpty()) {
                    RadioStateModel active = cacheModel.radios.get(activeRadioId);
                    if (active == null) {
                        active = cacheModel.radios.values().stream().findFirst().orElse(null);
                    }
                    if (active != null && active.snapshotJson != null) {
                        snapshot = SharedMediaSnapshot.fromJson(active.snapshotJson);
                    }
                    for (Map.Entry<String, RadioStateModel> entry : cacheModel.radios.entrySet()) {
                        QueueState queueState = new QueueState();
                        RadioStateModel model = entry.getValue();
                        if (model != null && model.queueMediaIds != null) {
                            queueState.queueMediaIds.addAll(model.queueMediaIds);
                        }
                        queueState.queueIndex = model == null ? -1 : model.queueIndex;
                        sanitizeQueue(queueState);
                        queuesByRadioId.put(entry.getKey(), queueState);
                    }
                }

                queuesByRadioId.computeIfAbsent(activeRadioId, ignored -> new QueueState());
                return;
            }
        } catch (JsonSyntaxException ignored) {
        }

        // Backward compatibility with the oldest single-snapshot cache format.
        snapshot = SharedMediaSnapshot.fromJson(json);
        queuesByRadioId.clear();
        queuesByRadioId.put(DEFAULT_RADIO_ID, new QueueState());
        activeRadioId = DEFAULT_RADIO_ID;
    }

    private synchronized void saveCache() {
        Path cacheFile = getCacheFile();
        try {
            Files.createDirectories(Objects.requireNonNull(cacheFile.getParent()));
            CacheModel model = new CacheModel();
            model.activeRadioId = activeRadioId;
            model.snapshotJson = snapshot.toJson();
            model.queuesByRadioId = new HashMap<>();
            for (Map.Entry<String, QueueState> entry : queuesByRadioId.entrySet()) {
                QueueState queueState = entry.getValue();
                QueueStateModel queueModel = new QueueStateModel();
                queueModel.queueMediaIds = new ArrayList<>(queueState.queueMediaIds);
                queueModel.queueIndex = queueState.queueIndex;
                model.queuesByRadioId.put(entry.getKey(), queueModel);
            }
            Files.writeString(cacheFile, gson.toJson(model));
        } catch (IOException exception) {
            MediaRadio.LOGGER.warn("Failed to save client media cache", exception);
        }
    }

    private synchronized QueueState activeQueueState() {
        return queuesByRadioId.computeIfAbsent(activeRadioId, ignored -> new QueueState());
    }

    private void sanitizeQueue(QueueState queueState) {
        queueState.queueMediaIds.removeIf(mediaId -> !snapshot.library.containsKey(mediaId));
        if (queueState.queueMediaIds.isEmpty()) {
            queueState.queueIndex = -1;
        } else if (queueState.queueIndex < 0 || queueState.queueIndex >= queueState.queueMediaIds.size()) {
            queueState.queueIndex = 0;
        }
    }

    private Path getCacheFile() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("mediaradio-client-cache.json");
    }

    private static class QueueState {
        private final List<String> queueMediaIds = new ArrayList<>();
        private int queueIndex = -1;
    }

    private static class CacheModel {
        private String activeRadioId;
        private String snapshotJson;
        private Map<String, QueueStateModel> queuesByRadioId;
        // legacy per-radio cache format from previous build
        private Map<String, RadioStateModel> radios;
    }

    private static class QueueStateModel {
        private List<String> queueMediaIds;
        private int queueIndex;
    }

    private static class RadioStateModel {
        private String snapshotJson;
        private List<String> queueMediaIds;
        private int queueIndex;
    }
}
