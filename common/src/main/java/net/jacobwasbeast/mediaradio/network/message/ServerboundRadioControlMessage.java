package net.jacobwasbeast.mediaradio.network.message;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

public record ServerboundRadioControlMessage(
        BlockPos blockPos,
        String radioId,
        Action action,
        String url,
        String title,
        String artist,
        String thumbnail,
        float volume,
        long positionMs,
        long trackDurationMs
) {
    private static final int MAX_PACKET_STRING = 32767;
    private static final int MAX_TITLE_ARTIST = 4096;

    public ServerboundRadioControlMessage(FriendlyByteBuf friendlyByteBuf) {
        this(
                friendlyByteBuf.readBoolean() ? friendlyByteBuf.readBlockPos() : null,
                friendlyByteBuf.readUtf(MAX_PACKET_STRING),
                friendlyByteBuf.readEnum(Action.class),
                friendlyByteBuf.readUtf(MAX_PACKET_STRING),
                friendlyByteBuf.readUtf(MAX_TITLE_ARTIST),
                friendlyByteBuf.readUtf(MAX_TITLE_ARTIST),
                friendlyByteBuf.readUtf(MAX_PACKET_STRING),
                friendlyByteBuf.readFloat(),
                friendlyByteBuf.readLong(),
                friendlyByteBuf.readLong()
        );
    }

    public ServerboundRadioControlMessage(
            BlockPos blockPos,
            Action action,
            String url,
            String title,
            String artist,
            String thumbnail,
            float volume,
            long positionMs
    ) {
        this(blockPos, "", action, url, title, artist, thumbnail, volume, positionMs, -1L);
    }

    public ServerboundRadioControlMessage(
            BlockPos blockPos,
            String radioId,
            Action action,
            String url,
            String title,
            String artist,
            String thumbnail,
            float volume,
            long positionMs
    ) {
        this(blockPos, radioId, action, url, title, artist, thumbnail, volume, positionMs, -1L);
    }

    public void encode(FriendlyByteBuf friendlyByteBuf) {
        boolean hasBlockPos = blockPos != null;
        friendlyByteBuf.writeBoolean(hasBlockPos);
        if (hasBlockPos) {
            friendlyByteBuf.writeBlockPos(blockPos);
        }
        friendlyByteBuf.writeUtf(radioId == null ? "" : radioId, MAX_PACKET_STRING);
        friendlyByteBuf.writeEnum(action);
        friendlyByteBuf.writeUtf(url, MAX_PACKET_STRING);
        friendlyByteBuf.writeUtf(title, MAX_TITLE_ARTIST);
        friendlyByteBuf.writeUtf(artist, MAX_TITLE_ARTIST);
        friendlyByteBuf.writeUtf(thumbnail, MAX_PACKET_STRING);
        friendlyByteBuf.writeFloat(volume);
        friendlyByteBuf.writeLong(positionMs);
        friendlyByteBuf.writeLong(trackDurationMs);
    }

    public enum Action {
        PLAY_URL,
        UPDATE_METADATA,
        UPDATE_QUEUE_STATE,
        TOGGLE_PAUSE,
        STOP,
        SET_VOLUME,
        SEEK,
        SYNC_RUNTIME
    }
}
