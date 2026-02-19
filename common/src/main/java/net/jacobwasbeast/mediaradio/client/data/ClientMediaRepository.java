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
    private static final long CHUNK_TTL_MS = 30_000L;
    private static final int WIRE_SAFE_QUEUE_STATE_MAX = 30_000;
    private static final Map<String, SnapshotChunkAccumulator> SNAPSHOT_CHUNKS = new HashMap<>();

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

    public static void applyServerSnapshotChunk(String transferId, int chunkIndex, int totalChunks, String chunkData) {
        if (transferId == null || transferId.isBlank() || chunkData == null) {
            return;
        }
        if (totalChunks <= 0 || totalChunks > 512 || chunkIndex < 0 || chunkIndex >= totalChunks) {
            return;
        }

        long now = System.currentTimeMillis();
        String json;
        synchronized (SNAPSHOT_CHUNKS) {
            SNAPSHOT_CHUNKS.entrySet().removeIf(entry -> now - entry.getValue().createdAtMs > CHUNK_TTL_MS);
            SnapshotChunkAccumulator accumulator = SNAPSHOT_CHUNKS.computeIfAbsent(
                    transferId,
                    ignored -> new SnapshotChunkAccumulator(totalChunks, now)
            );
            if (accumulator.totalChunks != totalChunks) {
                SNAPSHOT_CHUNKS.remove(transferId);
                return;
            }
            if (!accumulator.accept(chunkIndex, chunkData, SharedMediaSnapshot.MAX_JSON_LENGTH)) {
                SNAPSHOT_CHUNKS.remove(transferId);
                return;
            }
            if (!accumulator.complete()) {
                return;
            }
            json = accumulator.join();
            SNAPSHOT_CHUNKS.remove(transferId);
        }

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

    public synchronized void resetAndReloadCache() {
        reset();
        loadCache();
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
        String safePlaylistId = safe(playlistId);
        if (!safePlaylistId.isBlank() && !snapshot.deletedPlaylistIds.contains(safePlaylistId)) {
            snapshot.deletedPlaylistIds.add(safePlaylistId);
        }
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

    public synchronized int reconcileCurrentQueueEntryForRadioId(String radioId, String currentUrl) {
        String normalizedUrl = safe(currentUrl);
        if (normalizedUrl.isBlank()) {
            return -1;
        }
        QueueState queueState = queueStateForRadioId(radioId);
        int currentIndex = findCurrentQueueIndex(queueState);
        SharedMediaSnapshot.MediaEntry currentEntry = queueEntryAt(queueState, currentIndex);
        if (currentEntry != null && sameTrackUrl(currentEntry.url, normalizedUrl)) {
            return currentIndex;
        }

        int matchedIndex = -1;
        int matchCount = 0;
        for (int i = 0; i < queueState.queueItems.size(); i++) {
            SharedMediaSnapshot.MediaEntry entry = queueEntryAt(queueState, i);
            if (entry == null || entry.url == null || entry.url.isBlank()) {
                continue;
            }
            if (!sameTrackUrl(entry.url, normalizedUrl)) {
                continue;
            }
            matchCount++;
            if (matchedIndex < 0) {
                matchedIndex = i;
            } else if (currentIndex >= 0) {
                int currentDistance = Math.abs(currentIndex - matchedIndex);
                int candidateDistance = Math.abs(currentIndex - i);
                if (candidateDistance < currentDistance) {
                    matchedIndex = i;
                }
            }
        }
        if (matchCount > 0 && matchedIndex >= 0) {
            queueState.currentQueueItemId = queueState.queueItems.get(matchedIndex).queueItemId;
            saveCache();
            return matchedIndex;
        }

        SharedMediaSnapshot.MediaEntry mediaEntry = findByUrl(normalizedUrl);
        if (mediaEntry == null) {
            mediaEntry = snapshot.upsertMedia(normalizedUrl, "", "", "", List.of(), true);
        }
        QueueItem queueItem = new QueueItem(newQueueItemId(), mediaEntry.id);
        int insertIndex = currentIndex < 0 ? queueState.queueItems.size() : Math.max(0, currentIndex);
        if (insertIndex > queueState.queueItems.size()) {
            insertIndex = queueState.queueItems.size();
        }
        queueState.queueItems.add(insertIndex, queueItem);
        queueState.currentQueueItemId = queueItem.queueItemId;
        sanitizeQueue(queueState);
        saveCache();
        return findCurrentQueueIndex(queueState);
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
        QueueStatePayload fullPayload = buildQueueStatePayload(queueState, false);
        String fullJson = gson.toJson(fullPayload);
        if (fullJson.length() <= WIRE_SAFE_QUEUE_STATE_MAX) {
            return fullJson;
        }

        QueueStatePayload compactPayload = buildQueueStatePayload(queueState, true);
        String compactJson = gson.toJson(compactPayload);
        if (compactJson.length() <= WIRE_SAFE_QUEUE_STATE_MAX) {
            return compactJson;
        }

        QueueStatePayload trimmedFullPayload = trimPayloadToWireLimit(fullPayload);
        QueueStatePayload trimmedCompactPayload = trimPayloadToWireLimit(compactPayload);
        QueueStatePayload preferred = preferredPayload(trimmedFullPayload, trimmedCompactPayload);
        String preferredJson = gson.toJson(preferred);
        if (preferredJson.length() <= WIRE_SAFE_QUEUE_STATE_MAX) {
            return preferredJson;
        }

        QueueStatePayload trimmedPayload = trimPayloadToWireLimit(compactPayload);
        return gson.toJson(trimmedPayload);
    }

    private QueueStatePayload preferredPayload(QueueStatePayload first, QueueStatePayload second) {
        int firstCount = first == null || first.entries == null ? 0 : first.entries.size();
        int secondCount = second == null || second.entries == null ? 0 : second.entries.size();
        if (secondCount > firstCount) {
            return second == null ? new QueueStatePayload() : second;
        }
        return first == null ? new QueueStatePayload() : first;
    }

    private QueueStatePayload buildQueueStatePayload(QueueState queueState, boolean compact) {
        QueueStatePayload payload = new QueueStatePayload();
        payload.loopMode = queueState.loopMode;
        payload.currentQueueItemId = queueState.currentQueueItemId;
        payload.queueIndex = findCurrentQueueIndex(queueState);

        for (QueueItem queueItem : queueState.queueItems) {
            SharedMediaSnapshot.MediaEntry entry = snapshot.library.get(queueItem.mediaId);
            if (entry == null || entry.url == null || entry.url.isBlank()) {
                continue;
            }

            QueueMediaPayload mediaPayload = new QueueMediaPayload();
            mediaPayload.queueItemId = queueItem.queueItemId;
            mediaPayload.url = entry.url;
            if (!compact) {
                mediaPayload.title = entry.title;
                mediaPayload.artist = entry.artist;
                mediaPayload.thumbnail = entry.thumbnail;
            }
            payload.entries.add(mediaPayload);
        }

        return payload;
    }

    private QueueStatePayload trimPayloadToWireLimit(QueueStatePayload payload) {
        if (payload == null) {
            return new QueueStatePayload();
        }

        QueueStatePayload trimmed = new QueueStatePayload();
        trimmed.loopMode = payload.loopMode;
        trimmed.currentQueueItemId = payload.currentQueueItemId;
        trimmed.queueIndex = payload.queueIndex;
        trimmed.partial = true;

        List<QueueMediaPayload> sourceEntries = payload.entries == null ? List.of() : payload.entries;
        if (sourceEntries.isEmpty()) {
            return trimmed;
        }

        QueueMediaPayload current = null;
        if (trimmed.currentQueueItemId != null && !trimmed.currentQueueItemId.isBlank()) {
            for (QueueMediaPayload sourceEntry : sourceEntries) {
                if (sourceEntry != null && trimmed.currentQueueItemId.equals(sourceEntry.queueItemId)) {
                    current = sourceEntry;
                    break;
                }
            }
        }
        if (current == null) {
            int index = Math.max(0, Math.min(trimmed.queueIndex, sourceEntries.size() - 1));
            current = sourceEntries.get(index);
        }
        if (current != null) {
            trimmed.entries.add(copyQueuePayloadEntry(current));
        }

        for (QueueMediaPayload sourceEntry : sourceEntries) {
            if (sourceEntry == null || sourceEntry == current) {
                continue;
            }
            trimmed.entries.add(copyQueuePayloadEntry(sourceEntry));
            if (gson.toJson(trimmed).length() > WIRE_SAFE_QUEUE_STATE_MAX) {
                trimmed.entries.remove(trimmed.entries.size() - 1);
                break;
            }
        }

        if (trimmed.entries.isEmpty()) {
            QueueMediaPayload first = sourceEntries.get(0);
            if (first != null) {
                trimmed.entries.add(copyQueuePayloadEntry(first));
            }
        }

        if (!trimmed.entries.isEmpty()) {
            String currentId = trimmed.currentQueueItemId;
            int currentIndex = -1;
            for (int i = 0; i < trimmed.entries.size(); i++) {
                QueueMediaPayload entry = trimmed.entries.get(i);
                if (entry != null && entry.queueItemId != null && entry.queueItemId.equals(currentId)) {
                    currentIndex = i;
                    break;
                }
            }
            if (currentIndex < 0) {
                currentIndex = 0;
                QueueMediaPayload first = trimmed.entries.get(0);
                trimmed.currentQueueItemId = first == null ? "" : safe(first.queueItemId);
            }
            trimmed.queueIndex = currentIndex;
        } else {
            trimmed.currentQueueItemId = "";
            trimmed.queueIndex = -1;
        }

        String json = gson.toJson(trimmed);
        while (json.length() > WIRE_SAFE_QUEUE_STATE_MAX && !trimmed.entries.isEmpty()) {
            trimmed.entries.remove(trimmed.entries.size() - 1);
            if (trimmed.entries.isEmpty()) {
                trimmed.currentQueueItemId = "";
                trimmed.queueIndex = -1;
                break;
            }
            QueueMediaPayload first = trimmed.entries.get(0);
            trimmed.currentQueueItemId = first == null ? "" : safe(first.queueItemId);
            trimmed.queueIndex = 0;
            json = gson.toJson(trimmed);
        }

        return trimmed;
    }

    private QueueMediaPayload copyQueuePayloadEntry(QueueMediaPayload source) {
        QueueMediaPayload copy = new QueueMediaPayload();
        copy.queueItemId = safe(source == null ? "" : source.queueItemId);
        copy.url = safe(source == null ? "" : source.url);
        copy.title = truncateForWire(safe(source == null ? "" : source.title), 256);
        copy.artist = truncateForWire(safe(source == null ? "" : source.artist), 256);
        copy.thumbnail = truncateForWire(safe(source == null ? "" : source.thumbnail), 1024);
        return copy;
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
        boolean mergePartialPayload = payload.partial && !queueState.queueItems.isEmpty();
        if (mergePartialPayload && countQueueIdOverlap(queueState, payload) == 0) {
            clearQueueState(queueState);
            mergePartialPayload = false;
        }
        if (!mergePartialPayload) {
            clearQueueState(queueState);
        }
        if (payload.loopMode != null) {
            queueState.loopMode = payload.loopMode;
        }

        if (payload.entries != null) {
            for (QueueMediaPayload queued : payload.entries) {
                if (queued == null || queued.url == null || queued.url.isBlank()) {
                    continue;
                }

                SharedMediaSnapshot.MediaEntry existingEntry = snapshot.library.get(SharedMediaSnapshot.idForUrl(queued.url));
                String resolvedTitle = safe(queued.title);
                String resolvedArtist = safe(queued.artist);
                String resolvedThumbnail = safe(queued.thumbnail);
                if (resolvedTitle.isBlank() && existingEntry != null) {
                    resolvedTitle = safe(existingEntry.title);
                }
                if (resolvedArtist.isBlank() && existingEntry != null) {
                    resolvedArtist = safe(existingEntry.artist);
                }
                if (resolvedThumbnail.isBlank() && existingEntry != null) {
                    resolvedThumbnail = safe(existingEntry.thumbnail);
                }
                SharedMediaSnapshot.MediaEntry entry = snapshot.upsertMedia(
                        queued.url,
                        resolvedTitle,
                        resolvedArtist,
                        resolvedThumbnail,
                        List.of(),
                        true
                );
                String queueItemId = safeQueueItemId(queued.queueItemId);
                if (mergePartialPayload && containsQueueItemId(queueState, queueItemId)) {
                    continue;
                }
                queueState.queueItems.add(new QueueItem(queueItemId, entry.id));
            }
        }

        if (!queueState.queueItems.isEmpty()) {
            if (payload.currentQueueItemId != null && !payload.currentQueueItemId.isBlank() && containsQueueItemId(queueState, payload.currentQueueItemId)) {
                queueState.currentQueueItemId = payload.currentQueueItemId;
            } else if (!mergePartialPayload && payload.queueIndex >= 0 && payload.queueIndex < queueState.queueItems.size()) {
                queueState.currentQueueItemId = queueState.queueItems.get(payload.queueIndex).queueItemId;
            } else if (queueState.currentQueueItemId == null
                    || queueState.currentQueueItemId.isBlank()
                    || !containsQueueItemId(queueState, queueState.currentQueueItemId)) {
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

    private int countQueueIdOverlap(QueueState queueState, QueueStatePayload payload) {
        if (queueState == null || payload == null || payload.entries == null || payload.entries.isEmpty()) {
            return 0;
        }
        Set<String> ids = new HashSet<>();
        for (QueueItem queueItem : queueState.queueItems) {
            String queueItemId = safe(queueItem == null ? null : queueItem.queueItemId);
            if (!queueItemId.isBlank()) {
                ids.add(queueItemId);
            }
        }
        if (ids.isEmpty()) {
            return 0;
        }
        int overlap = 0;
        for (QueueMediaPayload entry : payload.entries) {
            String queueItemId = safe(entry == null ? null : entry.queueItemId);
            if (!queueItemId.isBlank() && ids.contains(queueItemId)) {
                overlap++;
            }
        }
        return overlap;
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

    private static boolean sameTrackUrl(String left, String right) {
        return trackSyncKey(left).equals(trackSyncKey(right));
    }

    private static String trackSyncKey(String url) {
        String safeUrl = safe(url);
        if (safeUrl.isBlank()) {
            return "";
        }
        try {
            java.net.URI uri = new java.net.URI(safeUrl);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (host.contains("youtube.com")) {
                String videoId = queryParam(uri.getRawQuery(), "v");
                if (!videoId.isBlank()) {
                    return "yt:" + videoId;
                }
            } else if (host.equals("youtu.be")) {
                String id = path.startsWith("/") ? path.substring(1) : path;
                int slash = id.indexOf('/');
                if (slash >= 0) {
                    id = id.substring(0, slash);
                }
                if (!id.isBlank()) {
                    return "yt:" + id;
                }
            }
            String normalizedPath = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
            return host + normalizedPath;
        } catch (java.net.URISyntaxException ignored) {
            return safeUrl;
        }
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank() || key == null || key.isBlank()) {
            return "";
        }
        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            int separator = pair.indexOf('=');
            String k = separator >= 0 ? pair.substring(0, separator) : pair;
            if (!key.equals(k)) {
                continue;
            }
            String value = separator >= 0 && separator + 1 < pair.length() ? pair.substring(separator + 1) : "";
            return value == null ? "" : value;
        }
        return "";
    }

    private static String truncateForWire(String value, int maxLength) {
        String safeValue = safe(value);
        if (maxLength <= 0 || safeValue.length() <= maxLength) {
            return safeValue;
        }
        return safeValue.substring(0, maxLength);
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
        private boolean partial = false;
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

    private static class SnapshotChunkAccumulator {
        private final int totalChunks;
        private final String[] chunks;
        private final long createdAtMs;
        private int received;
        private int totalLength;

        private SnapshotChunkAccumulator(int totalChunks, long createdAtMs) {
            this.totalChunks = totalChunks;
            this.chunks = new String[totalChunks];
            this.createdAtMs = createdAtMs;
        }

        private boolean accept(int index, String chunk, int maxLength) {
            if (index < 0 || index >= totalChunks) {
                return false;
            }
            if (chunks[index] != null) {
                return true;
            }
            chunks[index] = chunk;
            received++;
            totalLength += chunk.length();
            return totalLength <= maxLength;
        }

        private boolean complete() {
            return received == totalChunks;
        }

        private String join() {
            StringBuilder builder = new StringBuilder(totalLength);
            for (String chunk : chunks) {
                if (chunk != null) {
                    builder.append(chunk);
                }
            }
            return builder.toString();
        }
    }

    public enum LoopMode {
        NONE,
        ONE,
        ALL
    }
}
