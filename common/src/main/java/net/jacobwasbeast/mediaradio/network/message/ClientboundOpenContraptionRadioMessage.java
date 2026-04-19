package net.jacobwasbeast.mediaradio.network.message;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.jacobwasbeast.mediaradio.MediaRadio;

public record ClientboundOpenContraptionRadioMessage(String radioId, int contraptionEntityId, BlockPos localPos) implements CustomPacketPayload {
    private static final int MAX_RADIO_ID = 32767;

    public ClientboundOpenContraptionRadioMessage(RegistryFriendlyByteBuf friendlyByteBuf) {
        this(
                friendlyByteBuf.readUtf(MAX_RADIO_ID),
                friendlyByteBuf.readInt(),
                friendlyByteBuf.readBoolean() ? friendlyByteBuf.readBlockPos() : null
        );
    }

    public void encode(RegistryFriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeUtf(radioId == null ? "" : radioId, MAX_RADIO_ID);
        friendlyByteBuf.writeInt(contraptionEntityId);
        boolean hasLocalPos = localPos != null;
        friendlyByteBuf.writeBoolean(hasLocalPos);
        if (hasLocalPos) {
            friendlyByteBuf.writeBlockPos(localPos);
        }
    }
    public static final Type<ClientboundOpenContraptionRadioMessage> TYPE = new Type<>(MediaRadio.id("open_contraption_radio"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
