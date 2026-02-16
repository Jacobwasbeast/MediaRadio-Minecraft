package net.jacobwasbeast.mediaradio.server;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

public class RadioRuntimeStateSavedData extends SavedData {

    private static final String DATA_NAME = "mediaradio_radio_runtime";
    private static final String TAG_RUNTIME_JSON = "RuntimeJson";
    private static final Gson GSON = new Gson();

    private final Map<String, RadioRuntimeState> radioStates = new HashMap<>();

    public static RadioRuntimeStateSavedData get(MinecraftServer server) {
        ServerLevel level = server.overworld();
        return level.getDataStorage().computeIfAbsent(RadioRuntimeStateSavedData::load, RadioRuntimeStateSavedData::new, DATA_NAME);
    }

    public static RadioRuntimeStateSavedData load(CompoundTag tag) {
        RadioRuntimeStateSavedData data = new RadioRuntimeStateSavedData();
        String runtimeJson = tag.getString(TAG_RUNTIME_JSON);
        if (runtimeJson == null || runtimeJson.isBlank()) {
            return data;
        }
        try {
            RuntimeModel model = GSON.fromJson(runtimeJson, RuntimeModel.class);
            if (model != null && model.states != null) {
                for (Map.Entry<String, RadioRuntimeState> entry : model.states.entrySet()) {
                    String radioId = safe(entry.getKey());
                    if (radioId.isBlank() || entry.getValue() == null) {
                        continue;
                    }
                    RadioRuntimeState state = entry.getValue();
                    state.sanitize();
                    data.radioStates.put(radioId, state);
                }
            }
        } catch (JsonSyntaxException ignored) {
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        RuntimeModel model = new RuntimeModel();
        model.states = radioStates;
        tag.putString(TAG_RUNTIME_JSON, GSON.toJson(model));
        return tag;
    }

    public RadioRuntimeState getOrCreate(String radioId) {
        String safeId = safe(radioId);
        return radioStates.computeIfAbsent(safeId, ignored -> new RadioRuntimeState());
    }

    public RadioRuntimeState get(String radioId) {
        return radioStates.get(safe(radioId));
    }

    public long currentPositionMs(RadioRuntimeState state) {
        if (state == null) {
            return 0L;
        }
        long base = Math.max(0L, state.positionMs);
        if (!state.playing) {
            return base;
        }
        long now = System.currentTimeMillis();
        long delta = Math.max(0L, now - Math.max(0L, state.updatedAtMs));
        return base + delta;
    }

    public void setFromClient(
            String radioId,
            String url,
            String title,
            String artist,
            String thumbnail,
            String queueStateJson,
            float volume,
            long positionMs,
            boolean playing
    ) {
        RadioRuntimeState state = getOrCreate(radioId);
        state.url = safe(url);
        state.title = safe(title);
        state.artist = safe(artist);
        state.thumbnail = safe(thumbnail);
        state.queueStateJson = safe(queueStateJson);
        state.volume = Mth.clamp(volume, 0f, 2f);
        state.positionMs = Math.max(0L, positionMs);
        state.playing = playing;
        state.updatedAtMs = System.currentTimeMillis();
        setDirty();
    }

    public static class RadioRuntimeState {
        public String url = "";
        public String title = "";
        public String artist = "";
        public String thumbnail = "";
        public String queueStateJson = "";
        public float volume = 1.0f;
        public long positionMs = 0L;
        public boolean playing = false;
        public long updatedAtMs = 0L;

        public void sanitize() {
            url = safe(url);
            title = safe(title);
            artist = safe(artist);
            thumbnail = safe(thumbnail);
            queueStateJson = safe(queueStateJson);
            volume = Mth.clamp(volume, 0f, 2f);
            positionMs = Math.max(0L, positionMs);
            updatedAtMs = Math.max(0L, updatedAtMs);
        }
    }

    private static class RuntimeModel {
        private Map<String, RadioRuntimeState> states = new HashMap<>();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
