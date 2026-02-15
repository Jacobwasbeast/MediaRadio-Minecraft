package net.jacobwasbeast.mediaradio.client.audio;

import net.jacobwasbeast.mediaradio.block.entity.RadioBlockEntity;
import net.jacobwasbeast.mediaradio.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClientAudioEngine {

    private static final ClientAudioEngine INSTANCE = new ClientAudioEngine();

    private final Map<BlockPos, RadioAudioChannel> blockChannels = new ConcurrentHashMap<>();

    private RadioAudioChannel handheldChannel;
    private float handheldVolume = 1.0f;
    private InteractionHand handheldHand = InteractionHand.MAIN_HAND;
    private int blockScanTicker;
    private String handheldTitle = "";
    private String handheldArtist = "";
    private String handheldThumbnail = "";

    public static ClientAudioEngine getInstance() {
        return INSTANCE;
    }

    public void tick(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            stopAll();
            return;
        }

        tickHandheld(minecraft);

        blockScanTicker++;
        if (blockScanTicker % 5 == 0) {
            scanBlockRadios(minecraft);
        }

        blockChannels.values().forEach(RadioAudioChannel::tick);
    }

    public void playHandheld(String url, long positionMs, InteractionHand hand, String displayTitle, String artist, String thumbnail) {
        if (url == null || url.isBlank()) {
            return;
        }

        handheldHand = hand;
        if (handheldChannel == null) {
            handheldChannel = new RadioAudioChannel(false, () -> Vec3.ZERO, () -> handheldVolume, 1f);
        }

        handheldChannel.setDisplayTitle(displayTitle);
        handheldChannel.play(url, positionMs);
        handheldTitle = displayTitle == null ? "" : displayTitle;
        handheldArtist = artist == null ? "" : artist;
        handheldThumbnail = thumbnail == null ? "" : thumbnail;
    }

    public void togglePauseHandheld() {
        if (handheldChannel == null) {
            return;
        }
        if (handheldChannel.isPlaying()) {
            handheldChannel.pause();
        } else {
            handheldChannel.resume();
        }
    }

    public void stopHandheld() {
        if (handheldChannel != null) {
            handheldChannel.stop();
            handheldChannel = null;
        }
        handheldTitle = "";
        handheldArtist = "";
        handheldThumbnail = "";
    }

    public void setHandheldVolume(float volume) {
        handheldVolume = Math.max(0f, Math.min(2f, volume));
    }

    public float getHandheldVolume() {
        return handheldVolume;
    }

    public String getHandheldNowPlaying() {
        return handheldChannel == null ? "" : handheldChannel.getDisplayTitle();
    }

    public boolean isHandheldPlaying() {
        return handheldChannel != null && handheldChannel.isPlaying();
    }

    public boolean isHandheldPaused() {
        return handheldChannel != null && handheldChannel.isPaused();
    }

    public long getHandheldPlaybackPositionMs() {
        return handheldChannel == null ? 0L : handheldChannel.getEstimatedPositionMs();
    }

    public long getHandheldTrackDurationMs() {
        return handheldChannel == null ? -1L : handheldChannel.getTrackDurationMs();
    }

    public void seekHandheld(long positionMs) {
        if (handheldChannel == null) {
            return;
        }
        handheldChannel.seekTo(positionMs, handheldChannel.isPaused());
    }

    public String getHandheldArtist() {
        return handheldArtist;
    }

    public String getHandheldThumbnail() {
        return handheldThumbnail;
    }

    public void updateHandheldMetadata(String title, String artist, String thumbnail) {
        handheldTitle = title == null ? "" : title;
        handheldArtist = artist == null ? "" : artist;
        handheldThumbnail = thumbnail == null ? "" : thumbnail;
        if (handheldChannel != null && !handheldTitle.isBlank()) {
            handheldChannel.setDisplayTitle(handheldTitle);
        }
    }

    public void stopAll() {
        stopHandheld();
        blockChannels.values().forEach(RadioAudioChannel::stop);
        blockChannels.clear();
    }

    public long getBlockPlaybackPositionMs(BlockPos blockPos) {
        RadioAudioChannel channel = blockChannels.get(blockPos);
        return channel == null ? -1L : channel.getEstimatedPositionMs();
    }

    public long getBlockTrackDurationMs(BlockPos blockPos) {
        RadioAudioChannel channel = blockChannels.get(blockPos);
        return channel == null ? -1L : channel.getTrackDurationMs();
    }

    private void tickHandheld(Minecraft minecraft) {
        if (handheldChannel == null) {
            return;
        }

        if (!minecraft.player.getMainHandItem().is(ModItems.RADIO_ITEM) && !minecraft.player.getOffhandItem().is(ModItems.RADIO_ITEM)) {
            stopHandheld();
            return;
        }

        // Keep channel alive only while the radio is actually held in the configured hand.
        if (!minecraft.player.getItemInHand(handheldHand).is(ModItems.RADIO_ITEM)
                && !minecraft.player.getMainHandItem().is(ModItems.RADIO_ITEM)
                && !minecraft.player.getOffhandItem().is(ModItems.RADIO_ITEM)) {
            stopHandheld();
            return;
        }

        handheldChannel.tick();
    }

    private void scanBlockRadios(Minecraft minecraft) {
        Set<BlockPos> activePositions = new HashSet<>();
        BlockPos playerPos = minecraft.player.blockPosition();
        BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();
        int horizontalRange = 30;
        int verticalRange = 16;
        int minY = Math.max(minecraft.level.getMinBuildHeight(), playerPos.getY() - verticalRange);
        int maxY = Math.min(minecraft.level.getMaxBuildHeight() - 1, playerPos.getY() + verticalRange);

        for (int x = playerPos.getX() - horizontalRange; x <= playerPos.getX() + horizontalRange; x++) {
            for (int z = playerPos.getZ() - horizontalRange; z <= playerPos.getZ() + horizontalRange; z++) {
                for (int y = minY; y <= maxY; y++) {
                    mutableBlockPos.set(x, y, z);
                    if (!(minecraft.level.getBlockEntity(mutableBlockPos) instanceof RadioBlockEntity radioBlockEntity)) {
                        continue;
                    }

                    BlockPos blockPos = radioBlockEntity.getBlockPos();
                    if (!radioBlockEntity.isPlaying() || radioBlockEntity.getMediaUrl().isBlank()) {
                        RadioAudioChannel existing = blockChannels.remove(blockPos);
                        if (existing != null) {
                            existing.stop();
                        }
                        continue;
                    }

                    activePositions.add(blockPos);

                    RadioAudioChannel channel = blockChannels.computeIfAbsent(blockPos,
                            ignored -> new RadioAudioChannel(
                                    true,
                                    () -> Vec3.atCenterOf(blockPos),
                                    radioBlockEntity::getVolume,
                                    30f));

                    long targetPosition = radioBlockEntity.getPlaybackPositionMs();
                    long delta = Math.abs(channel.getEstimatedPositionMs() - targetPosition);
                    if (!radioBlockEntity.getMediaUrl().equals(channel.getCurrentUrl()) || delta > 2500L) {
                        channel.setDisplayTitle(radioBlockEntity.getMediaTitle());
                        channel.play(radioBlockEntity.getMediaUrl(), targetPosition);
                    }
                }
            }
        }

        Set<BlockPos> stale = new HashSet<>(blockChannels.keySet());
        stale.removeAll(activePositions);
        for (BlockPos stalePos : stale) {
            RadioAudioChannel channel = blockChannels.remove(stalePos);
            if (channel != null) {
                channel.stop();
            }
        }
    }
}
