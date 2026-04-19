package net.jacobwasbeast.mediaradio.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.jacobwasbeast.mediaradio.MediaRadio;

public record ClientboundSharedMediaChunkMessage(
        String transferId,
        int chunkIndex,
        int totalChunks,
        String chunkData
) implements CustomPacketPayload {
    private static final int MAX_TRANSFER_ID = 96;
    private static final int MAX_CHUNK_DATA = 12000;

    public ClientboundSharedMediaChunkMessage(RegistryFriendlyByteBuf friendlyByteBuf) {
        this(
                friendlyByteBuf.readUtf(MAX_TRANSFER_ID),
                friendlyByteBuf.readVarInt(),
                friendlyByteBuf.readVarInt(),
                friendlyByteBuf.readUtf(MAX_CHUNK_DATA)
        );
    }

    public void encode(RegistryFriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeUtf(transferId == null ? "" : transferId, MAX_TRANSFER_ID);
        friendlyByteBuf.writeVarInt(chunkIndex);
        friendlyByteBuf.writeVarInt(totalChunks);
        friendlyByteBuf.writeUtf(chunkData == null ? "" : chunkData, MAX_CHUNK_DATA);
    }
    public static final Type<ClientboundSharedMediaChunkMessage> TYPE = new Type<>(MediaRadio.id("shared_media_chunk"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
