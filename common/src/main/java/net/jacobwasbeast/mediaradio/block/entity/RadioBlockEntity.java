package net.jacobwasbeast.mediaradio.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.jacobwasbeast.mediaradio.registry.ModBlockEntities;

public class RadioBlockEntity extends BlockEntity {

    public static final String TAG_MEDIA_URL = "MediaUrl";
    public static final String TAG_RADIO_ID = "RadioId";
    public static final String TAG_MEDIA_TITLE = "MediaTitle";
    public static final String TAG_MEDIA_ARTIST = "MediaArtist";
    public static final String TAG_MEDIA_THUMBNAIL = "MediaThumbnail";
    public static final String TAG_PLAYING = "Playing";
    public static final String TAG_STARTED_AT = "StartedAtEpochMs";
    public static final String TAG_PAUSED_POSITION = "PausedPositionMs";
    public static final String TAG_VOLUME = "Volume";

    private String mediaUrl = "";
    private String radioId = "";
    private String mediaTitle = "";
    private String mediaArtist = "";
    private String mediaThumbnail = "";
    private boolean playing;
    private long startedAtEpochMs;
    private long pausedPositionMs;
    private float volume = 1.0f;

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
        this.radioId = safe(radioId);
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
        sync();
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString(TAG_RADIO_ID, radioId);
        tag.putString(TAG_MEDIA_URL, mediaUrl);
        tag.putString(TAG_MEDIA_TITLE, mediaTitle);
        tag.putString(TAG_MEDIA_ARTIST, mediaArtist);
        tag.putString(TAG_MEDIA_THUMBNAIL, mediaThumbnail);
        tag.putBoolean(TAG_PLAYING, playing);
        tag.putLong(TAG_STARTED_AT, startedAtEpochMs);
        tag.putLong(TAG_PAUSED_POSITION, pausedPositionMs);
        tag.putFloat(TAG_VOLUME, volume);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        radioId = safe(tag.getString(TAG_RADIO_ID));
        mediaUrl = safe(tag.getString(TAG_MEDIA_URL));
        mediaTitle = safe(tag.getString(TAG_MEDIA_TITLE));
        mediaArtist = safe(tag.getString(TAG_MEDIA_ARTIST));
        mediaThumbnail = safe(tag.getString(TAG_MEDIA_THUMBNAIL));
        playing = tag.getBoolean(TAG_PLAYING);
        startedAtEpochMs = tag.getLong(TAG_STARTED_AT);
        pausedPositionMs = tag.getLong(TAG_PAUSED_POSITION);
        volume = clamp(tag.getFloat(TAG_VOLUME), 0.0f, 2.0f);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    public void handleUpdateTag(CompoundTag tag) {
        load(tag);
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
}
