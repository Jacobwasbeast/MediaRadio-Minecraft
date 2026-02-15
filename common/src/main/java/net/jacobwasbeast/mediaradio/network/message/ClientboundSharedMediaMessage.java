package net.jacobwasbeast.mediaradio.network.message;

import net.minecraft.network.FriendlyByteBuf;

public record ClientboundSharedMediaMessage(String json) {

    public ClientboundSharedMediaMessage(FriendlyByteBuf friendlyByteBuf) {
        this(friendlyByteBuf.readUtf(262144));
    }

    public void encode(FriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeUtf(json, 262144);
    }
}
