package net.jacobwasbeast.mediaradio.server;

import net.blay09.mods.balm.api.Balm;
import net.jacobwasbeast.mediaradio.MediaRadio;
import net.jacobwasbeast.mediaradio.block.entity.RadioBlockEntity;
import net.jacobwasbeast.mediaradio.network.ModNetworking;
import net.jacobwasbeast.mediaradio.network.message.ClientboundRadioStateMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundHandheldStateMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundRadioControlMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundRequestRadioStateMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class SharedMediaManager {

    public static void initialize() {
        Balm.getEvents().onEvent(net.blay09.mods.balm.api.event.PlayerLoginEvent.class,
                event -> ModNetworking.sendSharedSnapshot(event.getPlayer(), getSnapshot(event.getPlayer().server)));
    }

    public static String getSnapshot(MinecraftServer server) {
        return SharedMediaSavedData.get(server).getSnapshotJson();
    }

    public static void handleClientSnapshotUpload(ServerPlayer player, String json) {
        if (json == null || json.isBlank() || json.length() > SharedMediaSnapshot.MAX_JSON_LENGTH) {
            return;
        }

        SharedMediaSavedData data = SharedMediaSavedData.get(player.server);
        SharedMediaSnapshot currentSnapshot = SharedMediaSnapshot.fromJson(data.getSnapshotJson());
        SharedMediaSnapshot incomingSnapshot = SharedMediaSnapshot.fromJson(json);
        SharedMediaSnapshot mergedSnapshot = mergeSnapshotForPlayer(
                currentSnapshot,
                incomingSnapshot,
                player.getStringUUID(),
                player.getGameProfile().getName()
        );
        String mergedJson = mergedSnapshot.toJson();

        data.setSnapshotJson(mergedJson);

        ModNetworking.broadcastSharedSnapshot(player.server, mergedJson);
    }

    public static void handleRadioControl(ServerPlayer player, ServerboundRadioControlMessage message) {
        BlockPos blockPos = message.blockPos();
        if (blockPos == null) {
            return;
        }

        if (player.distanceToSqr(blockPos.getX() + 0.5d, blockPos.getY() + 0.5d, blockPos.getZ() + 0.5d) > 144d) {
            return;
        }

        BlockEntity blockEntity = player.level().getBlockEntity(blockPos);
        if (!(blockEntity instanceof RadioBlockEntity radioBlockEntity)) {
            return;
        }

        String radioId = radioBlockEntity.getRadioId();
        if (radioId == null || radioId.isBlank()) {
            radioId = UUID.randomUUID().toString();
            radioBlockEntity.setRadioId(radioId);
        }

        RadioRuntimeStateSavedData runtimeData = RadioRuntimeStateSavedData.get(player.server);
        RadioRuntimeStateSavedData.RadioRuntimeState runtimeState = runtimeData.getOrCreate(radioId);
        long now = System.currentTimeMillis();

        switch (message.action()) {
            case PLAY_URL -> {
                runtimeState.url = safe(message.url());
                runtimeState.title = safe(message.title());
                runtimeState.artist = safe(message.artist());
                runtimeState.thumbnail = safe(message.thumbnail());
                runtimeState.positionMs = 0L;
                runtimeState.playing = true;
                runtimeState.updatedAtMs = now;
                runtimeState.volume = Mth.clamp(message.volume(), 0f, 2f);
                radioBlockEntity.setMedia(message.url(), message.title(), message.artist(), message.thumbnail());
                radioBlockEntity.play();
            }
            case UPDATE_METADATA -> {
                runtimeState.title = safe(message.title());
                runtimeState.artist = safe(message.artist());
                runtimeState.thumbnail = safe(message.thumbnail());
                runtimeState.updatedAtMs = now;
                radioBlockEntity.updateMetadata(message.title(), message.artist(), message.thumbnail());
            }
            case UPDATE_QUEUE_STATE -> {
                runtimeState.queueStateJson = safe(message.url());
                runtimeState.updatedAtMs = now;
                radioBlockEntity.setQueueStateJson(message.url());
            }
            case TOGGLE_PAUSE -> {
                if (runtimeState.playing) {
                    runtimeState.positionMs = runtimeData.currentPositionMs(runtimeState);
                    runtimeState.playing = false;
                    runtimeState.updatedAtMs = now;
                    radioBlockEntity.pause();
                } else {
                    runtimeState.playing = true;
                    runtimeState.updatedAtMs = now;
                    radioBlockEntity.play();
                }
            }
            case STOP -> {
                runtimeState.playing = false;
                runtimeState.positionMs = 0L;
                runtimeState.updatedAtMs = now;
                radioBlockEntity.stop();
            }
            case SET_VOLUME -> {
                runtimeState.volume = Mth.clamp(message.volume(), 0f, 2f);
                runtimeState.updatedAtMs = now;
                radioBlockEntity.setVolume(message.volume());
            }
            case SEEK -> {
                runtimeState.positionMs = Math.max(0L, message.positionMs());
                runtimeState.updatedAtMs = now;
                radioBlockEntity.seekTo(message.positionMs());
            }
        }

        runtimeState.volume = Mth.clamp(radioBlockEntity.getVolume(), 0f, 2f);
        runtimeState.queueStateJson = safe(radioBlockEntity.getQueueStateJson());
        runtimeData.setDirty();
        MediaRadio.LOGGER.debug("Radio control {} at {} by {}", message.action(), blockPos, player.getGameProfile().getName());
    }

    public static void handleHandheldState(ServerPlayer player, ServerboundHandheldStateMessage message) {
        String radioId = message.radioId();
        if (radioId == null || radioId.isBlank()) {
            return;
        }

        RadioRuntimeStateSavedData runtimeData = RadioRuntimeStateSavedData.get(player.server);
        runtimeData.setFromClient(
                radioId,
                message.url(),
                message.title(),
                message.artist(),
                message.thumbnail(),
                message.queueStateJson(),
                message.volume(),
                message.positionMs(),
                message.playing()
        );
    }

    public static void handleRadioStateRequest(ServerPlayer player, ServerboundRequestRadioStateMessage message) {
        String radioId = safe(message.radioId());
        if (radioId.isBlank()) {
            return;
        }
        RadioRuntimeStateSavedData runtimeData = RadioRuntimeStateSavedData.get(player.server);
        RadioRuntimeStateSavedData.RadioRuntimeState state = runtimeData.getOrCreate(radioId);
        long position = runtimeData.currentPositionMs(state);
        ModNetworking.sendRadioState(player, new ClientboundRadioStateMessage(
                radioId,
                safe(state.url),
                safe(state.title),
                safe(state.artist),
                safe(state.thumbnail),
                safe(state.queueStateJson),
                Mth.clamp(state.volume, 0f, 2f),
                position,
                state.playing
        ));
    }

    public static void applyRuntimeStateToBlockEntity(RadioBlockEntity radioBlockEntity) {
        if (radioBlockEntity == null || radioBlockEntity.getLevel() == null || radioBlockEntity.getLevel().isClientSide) {
            return;
        }
        String radioId = safe(radioBlockEntity.getRadioId());
        if (radioId.isBlank()) {
            return;
        }
        MinecraftServer server = radioBlockEntity.getLevel().getServer();
        if (server == null) {
            return;
        }
        RadioRuntimeStateSavedData runtimeData = RadioRuntimeStateSavedData.get(server);
        RadioRuntimeStateSavedData.RadioRuntimeState state = runtimeData.getOrCreate(radioId);
        long position = runtimeData.currentPositionMs(state);
        radioBlockEntity.setMedia(state.url, state.title, state.artist, state.thumbnail);
        radioBlockEntity.setQueueStateJson(state.queueStateJson);
        radioBlockEntity.setVolume(state.volume);
        radioBlockEntity.setPausedPositionMs(position);
        if (state.playing && state.url != null && !state.url.isBlank()) {
            radioBlockEntity.play();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static SharedMediaSnapshot mergeSnapshotForPlayer(
            SharedMediaSnapshot current,
            SharedMediaSnapshot incoming,
            String playerId,
            String playerName
    ) {
        SharedMediaSnapshot merged = new SharedMediaSnapshot();

        // Library remains collaborative/global and follows incoming client state.
        merged.library.putAll(incoming.library);

        // Start from current playlists so unauthorized deletions/edits are ignored.
        for (Map.Entry<String, SharedMediaSnapshot.PlaylistEntry> entry : current.playlists.entrySet()) {
            merged.playlists.put(entry.getKey(), clonePlaylist(entry.getValue()));
        }

        // Apply owner-authorized deletions.
        for (Map.Entry<String, SharedMediaSnapshot.PlaylistEntry> entry : current.playlists.entrySet()) {
            String playlistId = entry.getKey();
            SharedMediaSnapshot.PlaylistEntry existing = entry.getValue();
            if (!incoming.playlists.containsKey(playlistId) && existing != null && existing.canEdit(playerId)) {
                merged.playlists.remove(playlistId);
            }
        }

        // Apply incoming updates and creations, bounded by ownership rules.
        for (Map.Entry<String, SharedMediaSnapshot.PlaylistEntry> entry : incoming.playlists.entrySet()) {
            String playlistId = entry.getKey();
            SharedMediaSnapshot.PlaylistEntry incomingEntry = clonePlaylist(entry.getValue());
            if (incomingEntry == null) {
                continue;
            }

            SharedMediaSnapshot.PlaylistEntry currentEntry = current.playlists.get(playlistId);
            if (currentEntry == null) {
                // New playlist creation must be owned by this player (or unspecified ownership).
                if (!incomingEntry.ownerId.isBlank() && !incomingEntry.ownerId.equals(playerId)) {
                    continue;
                }
                incomingEntry.ownerId = playerId;
                incomingEntry.ownerName = safe(playerName);
                merged.playlists.put(playlistId, incomingEntry);
                continue;
            }

            if (!currentEntry.canEdit(playerId)) {
                continue;
            }

            // Preserve canonical owner identity.
            if (!currentEntry.ownerId.isBlank()) {
                incomingEntry.ownerId = currentEntry.ownerId;
            } else {
                incomingEntry.ownerId = playerId;
            }
            if (!currentEntry.ownerName.isBlank()) {
                incomingEntry.ownerName = currentEntry.ownerName;
            } else {
                incomingEntry.ownerName = safe(playerName);
            }
            merged.playlists.put(playlistId, incomingEntry);
        }

        // Drop playlist media references that no longer exist in library.
        merged.playlists.values().forEach(playlist -> {
            if (playlist != null && playlist.mediaIds != null) {
                playlist.mediaIds.removeIf(mediaId -> mediaId == null || mediaId.isBlank() || !merged.library.containsKey(mediaId));
            }
        });

        return merged.sanitize();
    }

    private static SharedMediaSnapshot.PlaylistEntry clonePlaylist(SharedMediaSnapshot.PlaylistEntry source) {
        if (source == null) {
            return null;
        }
        SharedMediaSnapshot.PlaylistEntry copy = new SharedMediaSnapshot.PlaylistEntry();
        copy.id = source.id;
        copy.name = source.name;
        copy.thumbnail = source.thumbnail;
        copy.mediaIds = source.mediaIds == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(source.mediaIds);
        copy.ownerId = source.ownerId;
        copy.ownerName = source.ownerName;
        copy.access = source.access;
        copy.invitedPlayerIds = source.invitedPlayerIds == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(source.invitedPlayerIds);
        copy.invitedPlayerNames = source.invitedPlayerNames == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(source.invitedPlayerNames);
        copy.sanitize();
        return copy;
    }
}
