package net.jacobwasbeast.mediaradio.server;

import net.blay09.mods.balm.api.Balm;
import net.jacobwasbeast.mediaradio.MediaRadio;
import net.jacobwasbeast.mediaradio.block.entity.RadioBlockEntity;
import net.jacobwasbeast.mediaradio.network.ModNetworking;
import net.jacobwasbeast.mediaradio.network.message.ServerboundRadioControlMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

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

        SharedMediaSnapshot snapshot = SharedMediaSnapshot.fromJson(json);
        String sanitizedJson = snapshot.toJson();

        SharedMediaSavedData data = SharedMediaSavedData.get(player.server);
        data.setSnapshotJson(sanitizedJson);

        ModNetworking.broadcastSharedSnapshot(player.server, sanitizedJson);
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

        switch (message.action()) {
            case PLAY_URL -> {
                radioBlockEntity.setMedia(message.url(), message.title(), message.artist(), message.thumbnail());
                radioBlockEntity.play();
            }
            case UPDATE_METADATA -> radioBlockEntity.updateMetadata(message.title(), message.artist(), message.thumbnail());
            case TOGGLE_PAUSE -> {
                if (radioBlockEntity.isPlaying()) {
                    radioBlockEntity.pause();
                } else {
                    radioBlockEntity.play();
                }
            }
            case STOP -> radioBlockEntity.stop();
            case SET_VOLUME -> radioBlockEntity.setVolume(message.volume());
            case SEEK -> radioBlockEntity.seekTo(message.positionMs());
        }

        MediaRadio.LOGGER.debug("Radio control {} at {} by {}", message.action(), blockPos, player.getGameProfile().getName());
    }
}
