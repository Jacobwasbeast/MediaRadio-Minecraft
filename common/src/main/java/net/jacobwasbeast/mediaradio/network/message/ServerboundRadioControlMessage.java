package net.jacobwasbeast.mediaradio.network.message;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

public record ServerboundRadioControlMessage(
        BlockPos blockPos,
        Action action,
        String url,
        String title,
        String artist,
        String thumbnail,
        float volume,
        long positionMs
) {
    private static final int MAX_PACKET_STRING = 32767;
    private static final int MAX_TITLE_ARTIST = 4096;

    public ServerboundRadioControlMessage(FriendlyByteBuf friendlyByteBuf) {
        this(
                friendlyByteBuf.readBlockPos(),
                friendlyByteBuf.readEnum(Action.class),
                friendlyByteBuf.readUtf(MAX_PACKET_STRING),
                friendlyByteBuf.readUtf(MAX_TITLE_ARTIST),
                friendlyByteBuf.readUtf(MAX_TITLE_ARTIST),
                friendlyByteBuf.readUtf(MAX_PACKET_STRING),
                friendlyByteBuf.readFloat(),
                friendlyByteBuf.readLong()
        );
    }

    public void encode(FriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeBlockPos(blockPos);
        friendlyByteBuf.writeEnum(action);
        friendlyByteBuf.writeUtf(url, MAX_PACKET_STRING);
        friendlyByteBuf.writeUtf(title, MAX_TITLE_ARTIST);
        friendlyByteBuf.writeUtf(artist, MAX_TITLE_ARTIST);
        friendlyByteBuf.writeUtf(thumbnail, MAX_PACKET_STRING);
        friendlyByteBuf.writeFloat(volume);
        friendlyByteBuf.writeLong(positionMs);
    }

    public enum Action {
        PLAY_URL,
        UPDATE_METADATA,
        TOGGLE_PAUSE,
        STOP,
        SET_VOLUME,
        SEEK
    }
}
