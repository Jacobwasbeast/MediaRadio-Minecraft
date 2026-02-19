package net.jacobwasbeast.mediaradio.network.message;

import net.minecraft.network.FriendlyByteBuf;

public record ServerboundRequestRadioStateMessage(String radioId, Context context) {
    private static final int MAX_RADIO_ID = 256;

    public ServerboundRequestRadioStateMessage(FriendlyByteBuf friendlyByteBuf) {
        this(
                friendlyByteBuf.readUtf(MAX_RADIO_ID),
                friendlyByteBuf.readEnum(Context.class)
        );
    }

    public ServerboundRequestRadioStateMessage(String radioId) {
        this(radioId, Context.HANDHELD);
    }

    public void encode(FriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeUtf(radioId == null ? "" : radioId, MAX_RADIO_ID);
        friendlyByteBuf.writeEnum(context == null ? Context.HANDHELD : context);
    }

    public enum Context {
        HANDHELD,
        BLOCK
    }
}
