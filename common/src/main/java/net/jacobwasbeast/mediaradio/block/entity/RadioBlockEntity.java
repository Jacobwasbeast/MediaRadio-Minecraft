package net.jacobwasbeast.mediaradio.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.jacobwasbeast.mediaradio.registry.ModBlockEntities;
import net.jacobwasbeast.mediaradio.server.SharedMediaManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RadioBlockEntity extends BlockEntity {

    public static final String TAG_MEDIA_URL = "MediaUrl";
    public static final String TAG_RADIO_ID = "RadioId";
    public static final String TAG_OWNER_ID = "OwnerId";
    public static final String TAG_MEDIA_TITLE = "MediaTitle";
    public static final String TAG_MEDIA_ARTIST = "MediaArtist";
    public static final String TAG_MEDIA_THUMBNAIL = "MediaThumbnail";
    public static final String TAG_PLAYING = "Playing";
    public static final String TAG_STARTED_AT = "StartedAtEpochMs";
    public static final String TAG_PAUSED_POSITION = "PausedPositionMs";
    public static final String TAG_SEEK_VERSION = "SeekVersion";
    public static final String TAG_VOLUME = "Volume";
    public static final String TAG_QUEUE_STATE = "QueueStateJson";

    private String mediaUrl = "";
    private String radioId = "";
    private String ownerId = "";
    private String mediaTitle = "";
    private String mediaArtist = "";
    private String mediaThumbnail = "";
    private boolean playing;
    private long startedAtEpochMs;
    private long pausedPositionMs;
    private int seekVersion;
    private float volume = 1.0f;
    private String queueStateJson = "";
    private boolean pendingRuntimeStateApply;
    private boolean runtimeRegistryRegistered;
    private static final Map<String, Set<RadioBlockEntity>> LOADED_BY_RADIO_ID = new HashMap<>();

    public RadioBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(ModBlockEntities.RADIO_BLOCK_ENTITY.get(), blockPos, blockState);
    }

    public String getMediaUrl() {
        return mediaUrl;
    }

    public String getRadioId() {
        return radioId == null ? "" : radioId;
    }

    public void setRadioId(String radioId) {
        String resolved = safe(radioId);
        if (resolved.equals(this.radioId)) {
            return;
        }
        String previous = this.radioId;
        this.radioId = resolved;
        updateRegistryForRadioIdChange(previous, this.radioId);
        sync();
    }

    public String getOwnerId() {
        return ownerId == null ? "" : ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = safe(ownerId);
        sync();
    }

    public String getMediaTitle() {
        return mediaTitle;
    }

    public String getMediaArtist() {
        return mediaArtist;
    }

    public String getMediaThumbnail() {
        return mediaThumbnail;
    }

    public boolean isPlaying() {
        return playing;
    }

    public float getVolume() {
        return volume;
    }

    public long getPausedPositionMs() {
        return pausedPositionMs;
    }

    public int getSeekVersion() {
        return seekVersion;
    }

    public long getPlaybackPositionMs() {
        if (!playing) {
            return pausedPositionMs;
        }
        return Math.max(0L, System.currentTimeMillis() - startedAtEpochMs);
    }

    public void setMedia(String url, String title, String artist, String thumbnail) {
        this.mediaUrl = safe(url);
        this.mediaTitle = safe(title);
        this.mediaArtist = safe(artist);
        this.mediaThumbnail = safe(thumbnail);
        this.pausedPositionMs = 0L;
        sync();
    }

    public void updateMetadata(String title, String artist, String thumbnail) {
        this.mediaTitle = safe(title);
        this.mediaArtist = safe(artist);
        this.mediaThumbnail = safe(thumbnail);
        sync();
    }

    public void setVolume(float volume) {
        this.volume = clamp(volume, 0.0f, 2.0f);
        sync();
    }

    public void setPausedPositionMs(long pausedPositionMs) {
        this.pausedPositionMs = Math.max(0L, pausedPositionMs);
        this.playing = false;
        sync();
    }

    public String getQueueStateJson() {
        return queueStateJson == null ? "" : queueStateJson;
    }

    public void setQueueStateJson(String queueStateJson) {
        this.queueStateJson = safe(queueStateJson);
        sync();
    }

    public void play() {
        this.startedAtEpochMs = System.currentTimeMillis() - Math.max(0L, pausedPositionMs);
        this.playing = true;
        sync();
    }

    public void pause() {
        if (!playing) {
            return;
        }
        this.pausedPositionMs = getPlaybackPositionMs();
        this.playing = false;
        sync();
    }

    public void stop() {
        this.playing = false;
        this.pausedPositionMs = 0L;
        this.startedAtEpochMs = 0L;
        sync();
    }

    public void seekTo(long positionMs) {
        long clamped = Math.max(0L, positionMs);
        if (playing) {
            this.startedAtEpochMs = System.currentTimeMillis() - clamped;
            this.pausedPositionMs = clamped;
        } else {
            this.pausedPositionMs = clamped;
        }
        this.seekVersion++;
        sync();
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(TAG_RADIO_ID, radioId);
        tag.putString(TAG_OWNER_ID, ownerId);
        tag.putString(TAG_MEDIA_URL, mediaUrl);
        tag.putString(TAG_MEDIA_TITLE, mediaTitle);
        tag.putString(TAG_MEDIA_ARTIST, mediaArtist);
        tag.putString(TAG_MEDIA_THUMBNAIL, mediaThumbnail);
        tag.putBoolean(TAG_PLAYING, playing);
        tag.putLong(TAG_STARTED_AT, startedAtEpochMs);
        tag.putLong(TAG_PAUSED_POSITION, pausedPositionMs);
        tag.putInt(TAG_SEEK_VERSION, seekVersion);
        tag.putFloat(TAG_VOLUME, volume);
        tag.putString(TAG_QUEUE_STATE, safe(queueStateJson));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        radioId = safe(tag.getString(TAG_RADIO_ID));
        ownerId = safe(tag.getString(TAG_OWNER_ID));
        mediaUrl = safe(tag.getString(TAG_MEDIA_URL));
        mediaTitle = safe(tag.getString(TAG_MEDIA_TITLE));
        mediaArtist = safe(tag.getString(TAG_MEDIA_ARTIST));
        mediaThumbnail = safe(tag.getString(TAG_MEDIA_THUMBNAIL));
        playing = tag.getBoolean(TAG_PLAYING);
        startedAtEpochMs = tag.getLong(TAG_STARTED_AT);
        pausedPositionMs = tag.getLong(TAG_PAUSED_POSITION);
        seekVersion = Math.max(0, tag.getInt(TAG_SEEK_VERSION));
        volume = clamp(tag.getFloat(TAG_VOLUME), 0.0f, 2.0f);
        queueStateJson = safe(tag.getString(TAG_QUEUE_STATE));
        pendingRuntimeStateApply = true;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    public static void serverTick(Level level, BlockPos blockPos, BlockState blockState, RadioBlockEntity radioBlockEntity) {
        radioBlockEntity.serverTick();
    }

    private void serverTick() {
        ensureRegisteredInRuntimeRegistry();
        if (!pendingRuntimeStateApply || level == null || level.isClientSide) {
            return;
        }
        pendingRuntimeStateApply = false;
        SharedMediaManager.applyRuntimeStateToBlockEntity(this);
    }

    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
    }

    @Override
    public void setRemoved() {
        unregisterLoadedEntity();
        runtimeRegistryRegistered = false;
        super.setRemoved();
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void registerLoadedEntity() {
        if (level == null || level.isClientSide) {
            return;
        }
        String id = safe(radioId);
        if (id.isBlank()) {
            return;
        }
        synchronized (LOADED_BY_RADIO_ID) {
            LOADED_BY_RADIO_ID.computeIfAbsent(id, ignored -> new HashSet<>()).add(this);
        }
    }

    private void unregisterLoadedEntity() {
        synchronized (LOADED_BY_RADIO_ID) {
            LOADED_BY_RADIO_ID.values().forEach(set -> set.remove(this));
            LOADED_BY_RADIO_ID.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isEmpty());
        }
    }

    private void updateRegistryForRadioIdChange(String previousRadioId, String nextRadioId) {
        if (level == null || level.isClientSide) {
            return;
        }
        String previous = safe(previousRadioId);
        String next = safe(nextRadioId);
        if (previous.equals(next)) {
            return;
        }
        synchronized (LOADED_BY_RADIO_ID) {
            if (!previous.isBlank()) {
                Set<RadioBlockEntity> previousSet = LOADED_BY_RADIO_ID.get(previous);
                if (previousSet != null) {
                    previousSet.remove(this);
                    if (previousSet.isEmpty()) {
                        LOADED_BY_RADIO_ID.remove(previous);
                    }
                }
            }
            if (!next.isBlank()) {
                LOADED_BY_RADIO_ID.computeIfAbsent(next, ignored -> new HashSet<>()).add(this);
            }
        }
    }

    public static List<RadioBlockEntity> loadedForRadioId(String radioId) {
        String resolved = safe(radioId);
        if (resolved.isBlank()) {
            return List.of();
        }
        synchronized (LOADED_BY_RADIO_ID) {
            Set<RadioBlockEntity> set = LOADED_BY_RADIO_ID.get(resolved);
            if (set == null || set.isEmpty()) {
                return List.of();
            }
            List<RadioBlockEntity> snapshot = new ArrayList<>();
            for (RadioBlockEntity entity : set) {
                if (entity == null || entity.isRemoved() || entity.level == null || entity.level.isClientSide) {
                    continue;
                }
                snapshot.add(entity);
            }
            return snapshot;
        }
    }

    private void ensureRegisteredInRuntimeRegistry() {
        if (runtimeRegistryRegistered) {
            return;
        }
        registerLoadedEntity();
        runtimeRegistryRegistered = true;
    }
}
