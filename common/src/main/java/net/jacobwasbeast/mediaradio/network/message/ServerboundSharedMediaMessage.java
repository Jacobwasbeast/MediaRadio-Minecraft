package net.jacobwasbeast.mediaradio.network.message;

import net.minecraft.network.FriendlyByteBuf;

public record ServerboundSharedMediaMessage(String json) {

    public ServerboundSharedMediaMessage(FriendlyByteBuf friendlyByteBuf) {
        this(friendlyByteBuf.readUtf(262144));
    }

    public void encode(FriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeUtf(json, 262144);
    }
}
