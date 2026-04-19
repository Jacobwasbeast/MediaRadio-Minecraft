package net.jacobwasbeast.mediaradio.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.jacobwasbeast.mediaradio.MediaRadio;

public record ServerboundSharedMediaMessage(String json) implements CustomPacketPayload {

    public ServerboundSharedMediaMessage(RegistryFriendlyByteBuf friendlyByteBuf) {
        this(friendlyByteBuf.readUtf(262144));
    }

    public void encode(RegistryFriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeUtf(json, 262144);
    }
    public static final Type<ServerboundSharedMediaMessage> TYPE = new Type<>(MediaRadio.id("shared_media_upload"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
