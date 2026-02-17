package net.jacobwasbeast.mediaradio.network.message;

import net.minecraft.network.FriendlyByteBuf;

public record ClientboundPlayerRadioContextMessage(
        String radioId,
        int entityId,
        boolean active,
        boolean inventoryPlayback
) {
    private static final int MAX_RADIO_ID = 256;

    public ClientboundPlayerRadioContextMessage(FriendlyByteBuf friendlyByteBuf) {
        this(
                friendlyByteBuf.readUtf(MAX_RADIO_ID),
                friendlyByteBuf.readVarInt(),
                friendlyByteBuf.readBoolean(),
                friendlyByteBuf.readBoolean()
        );
    }

    public void encode(FriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeUtf(radioId == null ? "" : radioId, MAX_RADIO_ID);
        friendlyByteBuf.writeVarInt(entityId);
        friendlyByteBuf.writeBoolean(active);
        friendlyByteBuf.writeBoolean(inventoryPlayback);
    }
}
