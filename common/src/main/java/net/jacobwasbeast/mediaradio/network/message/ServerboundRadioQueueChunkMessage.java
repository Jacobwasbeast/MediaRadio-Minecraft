package net.jacobwasbeast.mediaradio.network.message;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

public record ServerboundRadioQueueChunkMessage(
        BlockPos blockPos,
        String radioId,
        ServerboundRadioControlMessage.Context context,
        String transferId,
        int chunkIndex,
        int totalChunks,
        String chunkData,
        long knownRevision,
        long commandId
) {
    private static final int MAX_RADIO_ID = 256;
    private static final int MAX_TRANSFER_ID = 96;
    private static final int MAX_CHUNK_DATA = 12000;

    public ServerboundRadioQueueChunkMessage(FriendlyByteBuf friendlyByteBuf) {
        this(
                friendlyByteBuf.readBoolean() ? friendlyByteBuf.readBlockPos() : null,
                friendlyByteBuf.readUtf(MAX_RADIO_ID),
                friendlyByteBuf.readEnum(ServerboundRadioControlMessage.Context.class),
                friendlyByteBuf.readUtf(MAX_TRANSFER_ID),
                friendlyByteBuf.readVarInt(),
                friendlyByteBuf.readVarInt(),
                friendlyByteBuf.readUtf(MAX_CHUNK_DATA),
                friendlyByteBuf.readLong(),
                friendlyByteBuf.readLong()
        );
    }

    public void encode(FriendlyByteBuf friendlyByteBuf) {
        boolean hasBlockPos = blockPos != null;
        friendlyByteBuf.writeBoolean(hasBlockPos);
        if (hasBlockPos) {
            friendlyByteBuf.writeBlockPos(blockPos);
        }
        friendlyByteBuf.writeUtf(radioId == null ? "" : radioId, MAX_RADIO_ID);
        friendlyByteBuf.writeEnum(context == null ? ServerboundRadioControlMessage.Context.BLOCK : context);
        friendlyByteBuf.writeUtf(transferId == null ? "" : transferId, MAX_TRANSFER_ID);
        friendlyByteBuf.writeVarInt(chunkIndex);
        friendlyByteBuf.writeVarInt(totalChunks);
        friendlyByteBuf.writeUtf(chunkData == null ? "" : chunkData, MAX_CHUNK_DATA);
        friendlyByteBuf.writeLong(knownRevision);
        friendlyByteBuf.writeLong(commandId);
    }
}
