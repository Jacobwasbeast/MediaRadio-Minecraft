package net.jacobwasbeast.mediaradio.network.message;

import net.minecraft.network.FriendlyByteBuf;

public record ServerboundRequestRadioStateMessage(String radioId) {
    private static final int MAX_RADIO_ID = 256;

    public ServerboundRequestRadioStateMessage(FriendlyByteBuf friendlyByteBuf) {
        this(friendlyByteBuf.readUtf(MAX_RADIO_ID));
    }

    public void encode(FriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeUtf(radioId == null ? "" : radioId, MAX_RADIO_ID);
    }
}
