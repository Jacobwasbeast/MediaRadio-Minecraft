package net.jacobwasbeast.mediaradio.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.jacobwasbeast.mediaradio.MediaRadio;

public record ClientboundPlayerRadioContextMessage(
        String radioId,
        int entityId,
        boolean active,
        boolean inventoryPlayback
) implements CustomPacketPayload {
    private static final int MAX_RADIO_ID = 256;

    public ClientboundPlayerRadioContextMessage(RegistryFriendlyByteBuf friendlyByteBuf) {
        this(
                friendlyByteBuf.readUtf(MAX_RADIO_ID),
                friendlyByteBuf.readVarInt(),
                friendlyByteBuf.readBoolean(),
                friendlyByteBuf.readBoolean()
        );
    }

    public void encode(RegistryFriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeUtf(radioId == null ? "" : radioId, MAX_RADIO_ID);
        friendlyByteBuf.writeVarInt(entityId);
        friendlyByteBuf.writeBoolean(active);
        friendlyByteBuf.writeBoolean(inventoryPlayback);
    }
    public static final Type<ClientboundPlayerRadioContextMessage> TYPE = new Type<>(MediaRadio.id("player_radio_context"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
