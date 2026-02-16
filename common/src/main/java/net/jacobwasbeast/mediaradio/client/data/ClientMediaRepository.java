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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
        activeRadioId = safeRadioId(radioId);
        QueueState queueState = queuesByRadioId.computeIfAbsent(activeRadioId, ignored -> new QueueState());
        sanitizeQueue(queueState);
        saveCache();
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
                .filter(entry -> entry != null && !entry.hiddenFromLibrary)
                .sorted(Comparator.comparing(entry -> entry.title == null || entry.title.isBlank() ? entry.url : entry.title, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public synchronized List<SharedMediaSnapshot.PlaylistEntry> getSortedPlaylists() {
        PlayerIdentity identity = localPlayerIdentity();
        return snapshot.playlists.values().stream()
                .filter(entry -> entry != null && entry.canView(identity.playerId(), identity.playerName()))
                .sorted(Comparator.comparing(entry -> entry.name == null || entry.name.isBlank() ? entry.id : entry.name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public synchronized List<SharedMediaSnapshot.MediaEntry> getPlaylistMedia(String playlistId) {
        PlayerIdentity identity = localPlayerIdentity();
        SharedMediaSnapshot.PlaylistEntry playlistEntry = snapshot.playlists.get(playlistId);
        if (playlistEntry == null || playlistEntry.mediaIds == null || !playlistEntry.canView(identity.playerId(), identity.playerName())) {
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
        SharedMediaSnapshot.MediaEntry mediaEntry = snapshot.upsertMedia(url, title, artist, thumbnail, tags, false);
        persistAndUpload();
        return mediaEntry;
    }

    public synchronized SharedMediaSnapshot.MediaEntry upsertPlaylistOnlyMedia(String url, String title, String artist, String thumbnail, List<String> tags) {
        SharedMediaSnapshot.MediaEntry mediaEntry = snapshot.upsertMedia(url, title, artist, thumbnail, tags, true);
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
            queueState.queueItems.removeIf(item -> mediaId.equals(item.mediaId));
            sanitizeQueue(queueState);
        }
        persistAndUpload();
    }

    public synchronized String createPlaylist(String name) {
        return createPlaylist(name, SharedMediaSnapshot.PlaylistAccess.PRIVATE);
    }

    public synchronized String createPlaylist(String name, SharedMediaSnapshot.PlaylistAccess access) {
        PlayerIdentity identity = localPlayerIdentity();
        SharedMediaSnapshot.PlaylistEntry playlistEntry = snapshot.createPlaylist(name, identity.playerId(), identity.playerName(), access);
        persistAndUpload();
        return playlistEntry.id;
    }

    public synchronized void deletePlaylist(String playlistId) {
        if (!canEditPlaylist(playlistId)) {
            return;
        }
        snapshot.playlists.remove(playlistId);
        persistAndUpload();
    }

    public synchronized boolean renamePlaylist(String playlistId, String newName) {
        SharedMediaSnapshot.PlaylistEntry playlistEntry = snapshot.playlists.get(playlistId);
        if (playlistEntry == null || !canEditPlaylist(playlistId)) {
            return false;
        }
        String resolvedName = newName == null ? "" : newName.trim();
        if (resolvedName.isBlank()) {
            return false;
        }
        if (resolvedName.equals(playlistEntry.name)) {
            return true;
        }
        playlistEntry.name = resolvedName;
        persistAndUpload();
        return true;
    }

    public synchronized void addMediaToPlaylist(String playlistId, String mediaId) {
        SharedMediaSnapshot.PlaylistEntry playlistEntry = snapshot.playlists.get(playlistId);
        if (playlistEntry == null || !snapshot.library.containsKey(mediaId) || !canEditPlaylist(playlistId)) {
            return;
        }
        if (!playlistEntry.mediaIds.contains(mediaId)) {
            playlistEntry.mediaIds.add(mediaId);
            persistAndUpload();
        }
    }

    public synchronized void removeMediaFromPlaylist(String playlistId, int index) {
        SharedMediaSnapshot.PlaylistEntry playlistEntry = snapshot.playlists.get(playlistId);
        if (playlistEntry == null || index < 0 || index >= playlistEntry.mediaIds.size() || !canEditPlaylist(playlistId)) {
            return;
        }
        playlistEntry.mediaIds.remove(index);
        persistAndUpload();
    }

    public synchronized void setQueueFromPlaylist(String playlistId) {
        if (!canViewPlaylist(playlistId)) {
            return;
        }
        QueueState queueState = activeQueueState();
        SharedMediaSnapshot.PlaylistEntry playlistEntry = snapshot.playlists.get(playlistId);
        clearQueueState(queueState);
        if (playlistEntry == null || playlistEntry.mediaIds == null || playlistEntry.mediaIds.isEmpty()) {
            saveCache();
            return;
        }

        for (String mediaId : playlistEntry.mediaIds) {
            if (snapshot.library.containsKey(mediaId)) {
                queueState.queueItems.add(new QueueItem(newQueueItemId(), mediaId));
            }
        }
        if (!queueState.queueItems.isEmpty()) {
            queueState.currentQueueItemId = queueState.queueItems.get(0).queueItemId;
        }
        saveCache();
    }

    public synchronized boolean canViewPlaylist(String playlistId) {
        SharedMediaSnapshot.PlaylistEntry playlistEntry = snapshot.playlists.get(playlistId);
        if (playlistEntry == null) {
            return false;
        }
        PlayerIdentity identity = localPlayerIdentity();
        return playlistEntry.canView(identity.playerId(), identity.playerName());
    }

    public synchronized boolean canEditPlaylist(String playlistId) {
        SharedMediaSnapshot.PlaylistEntry playlistEntry = snapshot.playlists.get(playlistId);
        if (playlistEntry == null) {
            return false;
        }
        PlayerIdentity identity = localPlayerIdentity();
        return playlistEntry.canEdit(identity.playerId());
    }

    public synchronized SharedMediaSnapshot.PlaylistAccess getPlaylistAccess(String playlistId) {
        SharedMediaSnapshot.PlaylistEntry playlistEntry = snapshot.playlists.get(playlistId);
        if (playlistEntry == null || playlistEntry.access == null) {
            return SharedMediaSnapshot.PlaylistAccess.PRIVATE;
        }
        return playlistEntry.access;
    }

    public synchronized void setPlaylistAccess(String playlistId, SharedMediaSnapshot.PlaylistAccess access) {
        SharedMediaSnapshot.PlaylistEntry playlistEntry = snapshot.playlists.get(playlistId);
        if (playlistEntry == null || access == null || !canEditPlaylist(playlistId)) {
            return;
        }
        playlistEntry.access = access;
        persistAndUpload();
    }

    public synchronized List<String> getPlaylistInvites(String playlistId) {
        SharedMediaSnapshot.PlaylistEntry playlistEntry = snapshot.playlists.get(playlistId);
        if (playlistEntry == null) {
            return List.of();
        }
        return playlistEntry.invitesView();
    }

    public synchronized void setPlaylistInvites(String playlistId, List<String> invites) {
        SharedMediaSnapshot.PlaylistEntry playlistEntry = snapshot.playlists.get(playlistId);
        if (playlistEntry == null || !canEditPlaylist(playlistId)) {
            return;
        }
        playlistEntry.setInvites(invites);
        persistAndUpload();
    }

    public synchronized String getPlaylistOwnerName(String playlistId) {
        SharedMediaSnapshot.PlaylistEntry playlistEntry = snapshot.playlists.get(playlistId);
        if (playlistEntry == null || playlistEntry.ownerName == null || playlistEntry.ownerName.isBlank()) {
            return "Unknown";
        }
        return playlistEntry.ownerName;
    }

    public synchronized List<SharedMediaSnapshot.PlaylistEntry> getImportableGlobalPlaylists() {
        PlayerIdentity identity = localPlayerIdentity();
        return snapshot.playlists.values().stream()
                .filter(entry -> entry != null
                        && entry.access == SharedMediaSnapshot.PlaylistAccess.GLOBAL
                        && entry.canView(identity.playerId(), identity.playerName()))
                .sorted(Comparator.comparing(entry -> entry.name == null || entry.name.isBlank() ? entry.id : entry.name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public synchronized List<SharedMediaSnapshot.PlaylistEntry> getImportableInvitedPlaylists() {
        PlayerIdentity identity = localPlayerIdentity();
        return snapshot.playlists.values().stream()
                .filter(entry -> entry != null
                        && entry.access == SharedMediaSnapshot.PlaylistAccess.INVITES
                        && entry.isInvited(identity.playerId(), identity.playerName()))
                .sorted(Comparator.comparing(entry -> entry.name == null || entry.name.isBlank() ? entry.id : entry.name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public synchronized String importPlaylistCopy(String sourcePlaylistId, String playlistName, SharedMediaSnapshot.PlaylistAccess access) {
        SharedMediaSnapshot.PlaylistEntry source = snapshot.playlists.get(sourcePlaylistId);
        if (source == null || !canViewPlaylist(sourcePlaylistId)) {
            return "";
        }

        String resolvedName = playlistName == null || playlistName.isBlank()
                ? "Copy of " + (source.name == null || source.name.isBlank() ? source.id : source.name)
                : playlistName.trim();
        SharedMediaSnapshot.PlaylistEntry copied = snapshot.createPlaylist(resolvedName, localPlayerIdentity().playerId(), localPlayerIdentity().playerName(), access);
        copied.thumbnail = source.thumbnail;
        if (source.mediaIds != null) {
            for (String mediaId : source.mediaIds) {
                if (snapshot.library.containsKey(mediaId) && !copied.mediaIds.contains(mediaId)) {
                    copied.mediaIds.add(mediaId);
                }
            }
        }
        persistAndUpload();
        return copied.id;
    }

    public synchronized void enqueue(String mediaId) {
        QueueState queueState = activeQueueState();
        if (!snapshot.library.containsKey(mediaId)) {
            return;
        }

        QueueItem queueItem = new QueueItem(newQueueItemId(), mediaId);
        queueState.queueItems.add(queueItem);
        if (queueState.currentQueueItemId.isBlank()) {
            queueState.currentQueueItemId = queueItem.queueItemId;
        }
        saveCache();
    }

    public synchronized SharedMediaSnapshot.MediaEntry getCurrentQueueEntry() {
        return getCurrentQueueEntryForRadioId(activeRadioId);
    }

    public synchronized SharedMediaSnapshot.MediaEntry getCurrentQueueEntryForRadioId(String radioId) {
        QueueState queueState = queueStateForRadioId(radioId);
        int currentIndex = findCurrentQueueIndex(queueState);
        if (currentIndex < 0) {
            return null;
        }
        return queueEntryAt(queueState, currentIndex);
    }

    public synchronized List<SharedMediaSnapshot.MediaEntry> getQueueEntries() {
        QueueState queueState = activeQueueState();
        List<SharedMediaSnapshot.MediaEntry> entries = new ArrayList<>();
        for (QueueItem queueItem : queueState.queueItems) {
            SharedMediaSnapshot.MediaEntry entry = snapshot.library.get(queueItem.mediaId);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    public synchronized int getQueueIndex() {
        return findCurrentQueueIndex(activeQueueState());
    }

    public synchronized SharedMediaSnapshot.MediaEntry setQueueIndex(int index) {
        QueueState queueState = activeQueueState();
        if (index < 0 || index >= queueState.queueItems.size()) {
            return null;
        }

        queueState.currentQueueItemId = queueState.queueItems.get(index).queueItemId;
        saveCache();
        return queueEntryAt(queueState, index);
    }

    public synchronized void removeQueueIndex(int index) {
        QueueState queueState = activeQueueState();
        if (index < 0 || index >= queueState.queueItems.size()) {
            return;
        }

        QueueItem removed = queueState.queueItems.remove(index);
        if (removed.queueItemId.equals(queueState.currentQueueItemId)) {
            if (queueState.queueItems.isEmpty()) {
                queueState.currentQueueItemId = "";
            } else {
                int replacementIndex = Math.min(index, queueState.queueItems.size() - 1);
                queueState.currentQueueItemId = queueState.queueItems.get(replacementIndex).queueItemId;
            }
        }
        sanitizeQueue(queueState);
        saveCache();
    }

    public synchronized void moveQueueIndex(int fromIndex, int toIndex) {
        QueueState queueState = activeQueueState();
        if (fromIndex < 0 || fromIndex >= queueState.queueItems.size() || toIndex < 0 || toIndex >= queueState.queueItems.size()) {
            return;
        }
        if (fromIndex == toIndex) {
            return;
        }

        QueueItem queueItem = queueState.queueItems.remove(fromIndex);
        queueState.queueItems.add(toIndex, queueItem);
        sanitizeQueue(queueState);
        saveCache();
    }

    public synchronized SharedMediaSnapshot.MediaEntry nextQueueEntry() {
        QueueState queueState = activeQueueState();
        if (queueState.queueItems.isEmpty()) {
            return null;
        }

        int currentIndex = findCurrentQueueIndex(queueState);
        if (currentIndex < 0) {
            queueState.currentQueueItemId = queueState.queueItems.get(0).queueItemId;
            saveCache();
            return queueEntryAt(queueState, 0);
        }

        if (queueState.loopMode == LoopMode.ONE) {
            return queueEntryAt(queueState, currentIndex);
        }

        int nextIndex = currentIndex + 1;
        if (nextIndex >= queueState.queueItems.size()) {
            if (queueState.loopMode == LoopMode.ALL) {
                queueState.currentQueueItemId = queueState.queueItems.get(0).queueItemId;
                saveCache();
                return queueEntryAt(queueState, 0);
            }
            return null;
        }

        queueState.currentQueueItemId = queueState.queueItems.get(nextIndex).queueItemId;
        saveCache();
        return queueEntryAt(queueState, nextIndex);
    }

    public synchronized SharedMediaSnapshot.MediaEntry previousQueueEntry() {
        QueueState queueState = activeQueueState();
        if (queueState.queueItems.isEmpty()) {
            return null;
        }

        int currentIndex = findCurrentQueueIndex(queueState);
        if (currentIndex < 0) {
            queueState.currentQueueItemId = queueState.queueItems.get(0).queueItemId;
            saveCache();
            return queueEntryAt(queueState, 0);
        }

        if (queueState.loopMode == LoopMode.ONE) {
            return queueEntryAt(queueState, currentIndex);
        }

        int previousIndex = currentIndex - 1;
        if (previousIndex < 0) {
            if (queueState.loopMode == LoopMode.ALL) {
                int lastIndex = queueState.queueItems.size() - 1;
                queueState.currentQueueItemId = queueState.queueItems.get(lastIndex).queueItemId;
                saveCache();
                return queueEntryAt(queueState, lastIndex);
            }
            return null;
        }

        queueState.currentQueueItemId = queueState.queueItems.get(previousIndex).queueItemId;
        saveCache();
        return queueEntryAt(queueState, previousIndex);
    }

    public synchronized void shuffleQueue() {
        QueueState queueState = activeQueueState();
        if (queueState.queueItems.size() <= 1) {
            return;
        }
        Collections.shuffle(queueState.queueItems, ThreadLocalRandom.current());
        sanitizeQueue(queueState);
        saveCache();
    }

    public synchronized LoopMode getLoopMode() {
        return activeQueueState().loopMode;
    }

    public synchronized LoopMode cycleLoopMode() {
        QueueState queueState = activeQueueState();
        queueState.loopMode = switch (queueState.loopMode) {
            case NONE -> LoopMode.ONE;
            case ONE -> LoopMode.ALL;
            case ALL -> LoopMode.NONE;
        };
        saveCache();
        return queueState.loopMode;
    }

    public synchronized String exportActiveQueueStateJson() {
        return exportQueueStateJsonForRadioId(activeRadioId);
    }

    public synchronized String exportQueueStateJsonForRadioId(String radioId) {
        QueueState queueState = queueStateForRadioId(radioId);
        return exportQueueStateJson(queueState);
    }

    private String exportQueueStateJson(QueueState queueState) {
        QueueStatePayload payload = new QueueStatePayload();
        payload.loopMode = queueState.loopMode;
        payload.currentQueueItemId = queueState.currentQueueItemId;
        payload.queueIndex = findCurrentQueueIndex(queueState);

        for (QueueItem queueItem : queueState.queueItems) {
            SharedMediaSnapshot.MediaEntry entry = snapshot.library.get(queueItem.mediaId);
            if (entry == null) {
                continue;
            }

            QueueMediaPayload mediaPayload = new QueueMediaPayload();
            mediaPayload.queueItemId = queueItem.queueItemId;
            mediaPayload.url = entry.url;
            mediaPayload.title = entry.title;
            mediaPayload.artist = entry.artist;
            mediaPayload.thumbnail = entry.thumbnail;
            payload.entries.add(mediaPayload);
        }
        return gson.toJson(payload);
    }

    public synchronized void importActiveQueueStateJson(String json) {
        if (json == null || json.isBlank()) {
            return;
        }

        QueueStatePayload payload;
        try {
            payload = gson.fromJson(json, QueueStatePayload.class);
        } catch (Exception ignored) {
            return;
        }
        if (payload == null) {
            return;
        }

        QueueState queueState = activeQueueState();
        clearQueueState(queueState);
        if (payload.loopMode != null) {
            queueState.loopMode = payload.loopMode;
        }

        if (payload.entries != null) {
            for (QueueMediaPayload queued : payload.entries) {
                if (queued == null || queued.url == null || queued.url.isBlank()) {
                    continue;
                }

                SharedMediaSnapshot.MediaEntry entry = snapshot.upsertMedia(
                        queued.url,
                        queued.title == null ? "" : queued.title,
                        queued.artist == null ? "" : queued.artist,
                        queued.thumbnail == null ? "" : queued.thumbnail,
                        List.of(),
                        true
                );
                queueState.queueItems.add(new QueueItem(
                        safeQueueItemId(queued.queueItemId),
                        entry.id
                ));
            }
        }

        if (!queueState.queueItems.isEmpty()) {
            if (payload.currentQueueItemId != null && !payload.currentQueueItemId.isBlank() && containsQueueItemId(queueState, payload.currentQueueItemId)) {
                queueState.currentQueueItemId = payload.currentQueueItemId;
            } else if (payload.queueIndex >= 0 && payload.queueIndex < queueState.queueItems.size()) {
                queueState.currentQueueItemId = queueState.queueItems.get(payload.queueIndex).queueItemId;
            } else {
                queueState.currentQueueItemId = queueState.queueItems.get(0).queueItemId;
            }
        }

        sanitizeQueue(queueState);
        saveCache();
    }

    public synchronized void uploadSnapshotNow() {
        ModNetworking.uploadSharedSnapshot(snapshot.toJson());
    }

    private synchronized void applySnapshotJson(String json, boolean shouldSaveCache) {
        snapshot = SharedMediaSnapshot.fromJson(json);
        for (QueueState queueState : queuesByRadioId.values()) {
            sanitizeQueue(queueState);
        }
        if (shouldSaveCache) {
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

                activeRadioId = safeRadioId(cacheModel.activeRadioId);
                queuesByRadioId.clear();

                if (cacheModel.queuesByRadioId != null) {
                    for (Map.Entry<String, QueueStateModel> entry : cacheModel.queuesByRadioId.entrySet()) {
                        String radioId = safeRadioId(entry.getKey());
                        QueueState queueState = new QueueState();
                        populateQueueStateFromModel(queueState, entry.getValue());
                        sanitizeQueue(queueState);
                        queuesByRadioId.put(radioId, queueState);
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
                        String radioId = safeRadioId(entry.getKey());
                        RadioStateModel model = entry.getValue();
                        QueueState queueState = new QueueState();
                        if (model != null && model.queueMediaIds != null) {
                            for (String mediaId : model.queueMediaIds) {
                                if (mediaId != null && !mediaId.isBlank()) {
                                    queueState.queueItems.add(new QueueItem(newQueueItemId(), mediaId));
                                }
                            }
                        }
                        if (model != null && model.loopMode != null) {
                            queueState.loopMode = model.loopMode;
                        }
                        if (!queueState.queueItems.isEmpty()) {
                            int index = model == null ? -1 : model.queueIndex;
                            if (index >= 0 && index < queueState.queueItems.size()) {
                                queueState.currentQueueItemId = queueState.queueItems.get(index).queueItemId;
                            }
                        }
                        sanitizeQueue(queueState);
                        queuesByRadioId.put(radioId, queueState);
                    }
                }

                QueueState activeQueue = queuesByRadioId.computeIfAbsent(activeRadioId, ignored -> new QueueState());
                sanitizeQueue(activeQueue);
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
                String radioId = safeRadioId(entry.getKey());
                QueueState queueState = entry.getValue();
                QueueStateModel queueModel = new QueueStateModel();
                queueModel.loopMode = queueState.loopMode;
                queueModel.currentQueueItemId = queueState.currentQueueItemId;
                queueModel.queueItems = new ArrayList<>();
                queueModel.queueMediaIds = new ArrayList<>();
                for (QueueItem queueItem : queueState.queueItems) {
                    QueueItemModel queueItemModel = new QueueItemModel();
                    queueItemModel.queueItemId = queueItem.queueItemId;
                    queueItemModel.mediaId = queueItem.mediaId;
                    queueModel.queueItems.add(queueItemModel);
                    queueModel.queueMediaIds.add(queueItem.mediaId);
                }
                queueModel.queueIndex = findCurrentQueueIndex(queueState);
                model.queuesByRadioId.put(radioId, queueModel);
            }

            Files.writeString(cacheFile, gson.toJson(model));
        } catch (IOException exception) {
            MediaRadio.LOGGER.warn("Failed to save client media cache", exception);
        }
    }

    private synchronized QueueState activeQueueState() {
        return queueStateForRadioId(activeRadioId);
    }

    private QueueState queueStateForRadioId(String radioId) {
        String safeId = safeRadioId(radioId);
        QueueState queueState = queuesByRadioId.computeIfAbsent(safeId, ignored -> new QueueState());
        sanitizeQueue(queueState);
        return queueState;
    }

    private void sanitizeQueue(QueueState queueState) {
        Set<String> seenQueueItemIds = new HashSet<>();
        queueState.queueItems.removeIf(item -> item == null || item.mediaId == null || item.mediaId.isBlank() || !snapshot.library.containsKey(item.mediaId));
        for (QueueItem queueItem : queueState.queueItems) {
            if (queueItem.queueItemId == null || queueItem.queueItemId.isBlank() || seenQueueItemIds.contains(queueItem.queueItemId)) {
                queueItem.queueItemId = newQueueItemId();
            }
            seenQueueItemIds.add(queueItem.queueItemId);
        }

        if (queueState.queueItems.isEmpty()) {
            queueState.currentQueueItemId = "";
        } else if (queueState.currentQueueItemId == null || queueState.currentQueueItemId.isBlank() || !containsQueueItemId(queueState, queueState.currentQueueItemId)) {
            queueState.currentQueueItemId = queueState.queueItems.get(0).queueItemId;
        }
    }

    private boolean containsQueueItemId(QueueState queueState, String queueItemId) {
        for (QueueItem queueItem : queueState.queueItems) {
            if (queueItemId.equals(queueItem.queueItemId)) {
                return true;
            }
        }
        return false;
    }

    private int findCurrentQueueIndex(QueueState queueState) {
        if (queueState.queueItems.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < queueState.queueItems.size(); i++) {
            if (queueState.queueItems.get(i).queueItemId.equals(queueState.currentQueueItemId)) {
                return i;
            }
        }
        return -1;
    }

    private SharedMediaSnapshot.MediaEntry queueEntryAt(QueueState queueState, int index) {
        if (index < 0 || index >= queueState.queueItems.size()) {
            return null;
        }
        return snapshot.library.get(queueState.queueItems.get(index).mediaId);
    }

    private void clearQueueState(QueueState queueState) {
        queueState.queueItems.clear();
        queueState.currentQueueItemId = "";
    }

    private void populateQueueStateFromModel(QueueState queueState, QueueStateModel model) {
        if (model == null) {
            return;
        }

        if (model.queueItems != null && !model.queueItems.isEmpty()) {
            for (QueueItemModel queueItemModel : model.queueItems) {
                if (queueItemModel == null || queueItemModel.mediaId == null || queueItemModel.mediaId.isBlank()) {
                    continue;
                }
                queueState.queueItems.add(new QueueItem(
                        safeQueueItemId(queueItemModel.queueItemId),
                        queueItemModel.mediaId
                ));
            }
        } else if (model.queueMediaIds != null) {
            for (String mediaId : model.queueMediaIds) {
                if (mediaId != null && !mediaId.isBlank()) {
                    queueState.queueItems.add(new QueueItem(newQueueItemId(), mediaId));
                }
            }
        }

        queueState.loopMode = model.loopMode == null ? LoopMode.ALL : model.loopMode;
        if (!queueState.queueItems.isEmpty()) {
            if (model.currentQueueItemId != null && !model.currentQueueItemId.isBlank()) {
                queueState.currentQueueItemId = model.currentQueueItemId;
            } else if (model.queueIndex >= 0 && model.queueIndex < queueState.queueItems.size()) {
                queueState.currentQueueItemId = queueState.queueItems.get(model.queueIndex).queueItemId;
            }
        }
    }

    private String safeQueueItemId(String queueItemId) {
        if (queueItemId == null || queueItemId.isBlank()) {
            return newQueueItemId();
        }
        return queueItemId;
    }

    private String newQueueItemId() {
        return UUID.randomUUID().toString();
    }

    private String safeRadioId(String radioId) {
        if (radioId == null || radioId.isBlank()) {
            return DEFAULT_RADIO_ID;
        }
        return radioId;
    }

    private PlayerIdentity localPlayerIdentity() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.player != null) {
            return new PlayerIdentity(
                    safe(minecraft.player.getStringUUID()),
                    safe(minecraft.player.getGameProfile().getName())
            );
        }
        if (minecraft != null && minecraft.getUser() != null) {
            String profileId = minecraft.getUser().getProfileId() == null ? "" : minecraft.getUser().getProfileId().toString();
            return new PlayerIdentity(safe(profileId), safe(minecraft.getUser().getName()));
        }
        return new PlayerIdentity("", "");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private Path getCacheFile() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("mediaradio-client-cache.json");
    }

    private static class QueueState {
        private final List<QueueItem> queueItems = new ArrayList<>();
        private String currentQueueItemId = "";
        private LoopMode loopMode = LoopMode.ALL;
    }

    private static class QueueItem {
        private String queueItemId;
        private final String mediaId;

        private QueueItem(String queueItemId, String mediaId) {
            this.queueItemId = queueItemId;
            this.mediaId = mediaId;
        }
    }

    private static class CacheModel {
        private String activeRadioId;
        private String snapshotJson;
        private Map<String, QueueStateModel> queuesByRadioId;
        // legacy per-radio cache format from previous build
        private Map<String, RadioStateModel> radios;
    }

    private static class QueueStateModel {
        private List<QueueItemModel> queueItems;
        private String currentQueueItemId;
        // legacy
        private List<String> queueMediaIds;
        private int queueIndex = -1;
        private LoopMode loopMode = LoopMode.ALL;
    }

    private static class QueueItemModel {
        private String queueItemId;
        private String mediaId;
    }

    private static class RadioStateModel {
        private String snapshotJson;
        private List<String> queueMediaIds;
        private int queueIndex;
        private LoopMode loopMode = LoopMode.ALL;
    }

    private static class QueueStatePayload {
        private List<QueueMediaPayload> entries = new ArrayList<>();
        private String currentQueueItemId = "";
        private int queueIndex = -1;
        private LoopMode loopMode = LoopMode.ALL;
    }

    private static class QueueMediaPayload {
        private String queueItemId = "";
        private String url = "";
        private String title = "";
        private String artist = "";
        private String thumbnail = "";
    }

    private record PlayerIdentity(String playerId, String playerName) {
    }

    public enum LoopMode {
        NONE,
        ONE,
        ALL
    }
}
