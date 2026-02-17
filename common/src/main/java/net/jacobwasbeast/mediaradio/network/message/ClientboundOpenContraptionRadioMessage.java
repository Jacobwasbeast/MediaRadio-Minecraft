package net.jacobwasbeast.mediaradio.network.message;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

public record ClientboundOpenContraptionRadioMessage(String radioId, int contraptionEntityId, BlockPos localPos) {
    private static final int MAX_RADIO_ID = 32767;

    public ClientboundOpenContraptionRadioMessage(FriendlyByteBuf friendlyByteBuf) {
        this(
                friendlyByteBuf.readUtf(MAX_RADIO_ID),
                friendlyByteBuf.readInt(),
                friendlyByteBuf.readBoolean() ? friendlyByteBuf.readBlockPos() : null
        );
    }

    public void encode(FriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeUtf(radioId == null ? "" : radioId, MAX_RADIO_ID);
        friendlyByteBuf.writeInt(contraptionEntityId);
        boolean hasLocalPos = localPos != null;
        friendlyByteBuf.writeBoolean(hasLocalPos);
        if (hasLocalPos) {
            friendlyByteBuf.writeBlockPos(localPos);
        }
    }
}
