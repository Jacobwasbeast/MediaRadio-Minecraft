package net.jacobwasbeast.mediaradio.network.message;

import net.minecraft.network.FriendlyByteBuf;

public record ServerboundSharedMediaChunkMessage(
        String transferId,
        int chunkIndex,
        int totalChunks,
        String chunkData
) {
    private static final int MAX_TRANSFER_ID = 96;
    private static final int MAX_CHUNK_DATA = 12000;

    public ServerboundSharedMediaChunkMessage(FriendlyByteBuf friendlyByteBuf) {
        this(
                friendlyByteBuf.readUtf(MAX_TRANSFER_ID),
                friendlyByteBuf.readVarInt(),
                friendlyByteBuf.readVarInt(),
                friendlyByteBuf.readUtf(MAX_CHUNK_DATA)
        );
    }

    public void encode(FriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeUtf(transferId == null ? "" : transferId, MAX_TRANSFER_ID);
        friendlyByteBuf.writeVarInt(chunkIndex);
        friendlyByteBuf.writeVarInt(totalChunks);
        friendlyByteBuf.writeUtf(chunkData == null ? "" : chunkData, MAX_CHUNK_DATA);
    }
}
