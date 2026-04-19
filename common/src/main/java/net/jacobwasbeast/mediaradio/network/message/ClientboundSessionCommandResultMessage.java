package net.jacobwasbeast.mediaradio.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.jacobwasbeast.mediaradio.MediaRadio;

public record ClientboundSessionCommandResultMessage(
        String radioId,
        String sessionId,
        long serverRevision,
        boolean accepted,
        Reason reason,
        ServerboundRequestRadioStateMessage.Context context
) implements CustomPacketPayload {
    private static final int MAX_RADIO_ID = 256;
    private static final int MAX_SESSION_ID = 256;

    public ClientboundSessionCommandResultMessage(RegistryFriendlyByteBuf friendlyByteBuf) {
        this(
                friendlyByteBuf.readUtf(MAX_RADIO_ID),
                friendlyByteBuf.readUtf(MAX_SESSION_ID),
                friendlyByteBuf.readLong(),
                friendlyByteBuf.readBoolean(),
                friendlyByteBuf.readEnum(Reason.class),
                friendlyByteBuf.readEnum(ServerboundRequestRadioStateMessage.Context.class)
        );
    }

    public void encode(RegistryFriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeUtf(radioId == null ? "" : radioId, MAX_RADIO_ID);
        friendlyByteBuf.writeUtf(sessionId == null ? "" : sessionId, MAX_SESSION_ID);
        friendlyByteBuf.writeLong(Math.max(0L, serverRevision));
        friendlyByteBuf.writeBoolean(accepted);
        friendlyByteBuf.writeEnum(reason == null ? Reason.NONE : reason);
        friendlyByteBuf.writeEnum(context == null ? ServerboundRequestRadioStateMessage.Context.HANDHELD : context);
    }

    public enum Reason {
        NONE,
        STALE_REVISION,
        DUPLICATE_COMMAND,
        UNAUTHORIZED
    }
    public static final Type<ClientboundSessionCommandResultMessage> TYPE = new Type<>(MediaRadio.id("session_command_result"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
