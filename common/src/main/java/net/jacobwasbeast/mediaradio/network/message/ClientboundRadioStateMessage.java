package net.jacobwasbeast.mediaradio.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.jacobwasbeast.mediaradio.MediaRadio;

public record ClientboundRadioStateMessage(
        String radioId,
        String sessionId,
        long revision,
        String url,
        String title,
        String artist,
        String thumbnail,
        String queueStateJson,
        float volume,
        long positionMs,
        long sentAtMs,
        boolean forcePositionSync,
        boolean seekEvent,
        boolean playing
) implements CustomPacketPayload {
    private static final int MAX_PACKET_STRING = 262144;
    private static final int MAX_TITLE_ARTIST = 4096;
    private static final int MAX_RADIO_ID = 256;
    private static final int MAX_SESSION_ID = 256;

    public ClientboundRadioStateMessage(RegistryFriendlyByteBuf friendlyByteBuf) {
        this(
                friendlyByteBuf.readUtf(MAX_RADIO_ID),
                friendlyByteBuf.readUtf(MAX_SESSION_ID),
                friendlyByteBuf.readLong(),
                friendlyByteBuf.readUtf(MAX_PACKET_STRING),
                friendlyByteBuf.readUtf(MAX_TITLE_ARTIST),
                friendlyByteBuf.readUtf(MAX_TITLE_ARTIST),
                friendlyByteBuf.readUtf(MAX_PACKET_STRING),
                friendlyByteBuf.readUtf(MAX_PACKET_STRING),
                friendlyByteBuf.readFloat(),
                friendlyByteBuf.readLong(),
                friendlyByteBuf.readLong(),
                friendlyByteBuf.readBoolean(),
                friendlyByteBuf.readBoolean(),
                friendlyByteBuf.readBoolean()
        );
    }

    public void encode(RegistryFriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeUtf(radioId == null ? "" : radioId, MAX_RADIO_ID);
        friendlyByteBuf.writeUtf(sessionId == null ? "" : sessionId, MAX_SESSION_ID);
        friendlyByteBuf.writeLong(Math.max(0L, revision));
        friendlyByteBuf.writeUtf(url == null ? "" : url, MAX_PACKET_STRING);
        friendlyByteBuf.writeUtf(title == null ? "" : title, MAX_TITLE_ARTIST);
        friendlyByteBuf.writeUtf(artist == null ? "" : artist, MAX_TITLE_ARTIST);
        friendlyByteBuf.writeUtf(thumbnail == null ? "" : thumbnail, MAX_PACKET_STRING);
        friendlyByteBuf.writeUtf(queueStateJson == null ? "" : queueStateJson, MAX_PACKET_STRING);
        friendlyByteBuf.writeFloat(volume);
        friendlyByteBuf.writeLong(positionMs);
        friendlyByteBuf.writeLong(sentAtMs);
        friendlyByteBuf.writeBoolean(forcePositionSync);
        friendlyByteBuf.writeBoolean(seekEvent);
        friendlyByteBuf.writeBoolean(playing);
    }
    public static final Type<ClientboundRadioStateMessage> TYPE = new Type<>(MediaRadio.id("radio_state"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
