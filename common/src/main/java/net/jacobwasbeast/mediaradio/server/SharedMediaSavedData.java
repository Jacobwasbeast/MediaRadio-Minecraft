package net.jacobwasbeast.mediaradio.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public class SharedMediaSavedData extends SavedData {

    private static final String DATA_NAME = "mediaradio_shared_media";
    private static final String TAG_SNAPSHOT_JSON = "SnapshotJson";

    private String snapshotJson = new SharedMediaSnapshot().toJson();

    public static SharedMediaSavedData get(MinecraftServer server) {
        ServerLevel level = server.overworld();
        return level.getDataStorage().computeIfAbsent(SharedMediaSavedData::load, SharedMediaSavedData::new, DATA_NAME);
    }

    public static SharedMediaSavedData load(CompoundTag tag) {
        SharedMediaSavedData data = new SharedMediaSavedData();
        data.snapshotJson = tag.getString(TAG_SNAPSHOT_JSON);
        if (data.snapshotJson.isBlank()) {
            data.snapshotJson = new SharedMediaSnapshot().toJson();
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag compoundTag) {
        compoundTag.putString(TAG_SNAPSHOT_JSON, snapshotJson);
        return compoundTag;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public void setSnapshotJson(String snapshotJson) {
        this.snapshotJson = snapshotJson;
        setDirty();
    }
}
