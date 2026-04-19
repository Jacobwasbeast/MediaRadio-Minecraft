package net.jacobwasbeast.mediaradio.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.jacobwasbeast.mediaradio.MediaRadio;

public record ServerboundRequestRadioStateMessage(String radioId, Context context) implements CustomPacketPayload {
    private static final int MAX_RADIO_ID = 256;

    public ServerboundRequestRadioStateMessage(RegistryFriendlyByteBuf friendlyByteBuf) {
        this(
                friendlyByteBuf.readUtf(MAX_RADIO_ID),
                friendlyByteBuf.readEnum(Context.class)
        );
    }

    public ServerboundRequestRadioStateMessage(String radioId) {
        this(radioId, Context.HANDHELD);
    }

    public void encode(RegistryFriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeUtf(radioId == null ? "" : radioId, MAX_RADIO_ID);
        friendlyByteBuf.writeEnum(context == null ? Context.HANDHELD : context);
    }

    public enum Context {
        HANDHELD,
        BLOCK
    }
    public static final Type<ServerboundRequestRadioStateMessage> TYPE = new Type<>(MediaRadio.id("radio_state_request"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
