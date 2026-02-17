package net.jacobwasbeast.mediaradio.network.message;

import net.minecraft.network.FriendlyByteBuf;

public record ServerboundHandheldStateMessage(
        String radioId,
        String url,
        String title,
        String artist,
        String thumbnail,
        String queueStateJson,
        float volume,
        long positionMs,
        int seekSerial,
        boolean playing
) {
    private static final int MAX_PACKET_STRING = 262144;
    private static final int MAX_TITLE_ARTIST = 4096;
    private static final int MAX_RADIO_ID = 256;

    public ServerboundHandheldStateMessage(FriendlyByteBuf friendlyByteBuf) {
        this(
                friendlyByteBuf.readUtf(MAX_RADIO_ID),
                friendlyByteBuf.readUtf(MAX_PACKET_STRING),
                friendlyByteBuf.readUtf(MAX_TITLE_ARTIST),
                friendlyByteBuf.readUtf(MAX_TITLE_ARTIST),
                friendlyByteBuf.readUtf(MAX_PACKET_STRING),
                friendlyByteBuf.readUtf(MAX_PACKET_STRING),
                friendlyByteBuf.readFloat(),
                friendlyByteBuf.readLong(),
                friendlyByteBuf.readVarInt(),
                friendlyByteBuf.readBoolean()
        );
    }

    public void encode(FriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeUtf(radioId, MAX_RADIO_ID);
        friendlyByteBuf.writeUtf(url, MAX_PACKET_STRING);
        friendlyByteBuf.writeUtf(title, MAX_TITLE_ARTIST);
        friendlyByteBuf.writeUtf(artist, MAX_TITLE_ARTIST);
        friendlyByteBuf.writeUtf(thumbnail, MAX_PACKET_STRING);
        friendlyByteBuf.writeUtf(queueStateJson, MAX_PACKET_STRING);
        friendlyByteBuf.writeFloat(volume);
        friendlyByteBuf.writeLong(positionMs);
        friendlyByteBuf.writeVarInt(Math.max(0, seekSerial));
        friendlyByteBuf.writeBoolean(playing);
    }
}
