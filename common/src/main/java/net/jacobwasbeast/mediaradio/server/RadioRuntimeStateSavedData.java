package net.jacobwasbeast.mediaradio.server;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.saveddata.SavedData;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class RadioRuntimeStateSavedData extends SavedData {

    private static final String DATA_NAME = "mediaradio_radio_runtime";
    private static final String TAG_RUNTIME_JSON = "RuntimeJson";
    private static final String TAG_RUNTIME_BYTES = "RuntimeBytes";
    private static final Gson GSON = new Gson();

    private final Map<String, RadioRuntimeState> radioStates = new HashMap<>();

    public static RadioRuntimeStateSavedData get(MinecraftServer server) {
        ServerLevel level = server.overworld();
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<RadioRuntimeStateSavedData>(RadioRuntimeStateSavedData::new, (tag, registries) -> load(tag), null),
                DATA_NAME
        );
    }

    public static RadioRuntimeStateSavedData load(CompoundTag tag) {
        RadioRuntimeStateSavedData data = new RadioRuntimeStateSavedData();
        String runtimeJson;
        if (tag.contains(TAG_RUNTIME_BYTES)) {
            byte[] bytes = tag.getByteArray(TAG_RUNTIME_BYTES);
            runtimeJson = bytes.length == 0 ? "" : new String(bytes, StandardCharsets.UTF_8);
        } else {
            runtimeJson = tag.getString(TAG_RUNTIME_JSON);
        }
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
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        RuntimeModel model = new RuntimeModel();
        model.states = radioStates;
        String runtimeJson = GSON.toJson(model);
        tag.putByteArray(TAG_RUNTIME_BYTES, runtimeJson.getBytes(StandardCharsets.UTF_8));
        tag.remove(TAG_RUNTIME_JSON);
        return tag;
    }

    public RadioRuntimeState getOrCreate(String radioId) {
        String safeId = safe(radioId);
        return radioStates.computeIfAbsent(safeId, ignored -> new RadioRuntimeState());
    }

    public RadioRuntimeState get(String radioId) {
        return radioStates.get(safe(radioId));
    }

    public Map<String, RadioRuntimeState> states() {
        return radioStates;
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
        if (Long.MAX_VALUE - base < delta) {
            return Long.MAX_VALUE;
        }
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
            long trackDurationMs,
            int seekSerial,
            boolean playing,
            boolean allowInventoryBroadcast
    ) {
        RadioRuntimeState state = getOrCreate(radioId);
        state.url = safe(url);
        state.title = safe(title);
        state.artist = safe(artist);
        state.thumbnail = safe(thumbnail);
        state.queueStateJson = safe(queueStateJson);
        state.volume = Mth.clamp(volume, 0f, 2f);
        state.positionMs = Math.max(0L, positionMs);
        state.trackDurationMs = sanitizeDuration(trackDurationMs);
        if (state.trackDurationMs > 0L && !state.url.isBlank()) {
            state.knownTrackDurationsMs.put(trackKey(state.url), state.trackDurationMs);
        } else if (state.trackDurationMs <= 0L && !state.url.isBlank()) {
            state.trackDurationMs = state.knownTrackDurationsMs.getOrDefault(trackKey(state.url), -1L);
        }
        if (!playing && state.trackDurationMs > 0L) {
            state.positionMs = Math.min(state.positionMs, state.trackDurationMs);
        }
        state.seekSerial = Math.max(0, seekSerial);
        state.playing = playing;
        state.allowInventoryBroadcast = allowInventoryBroadcast;
        state.updatedAtMs = System.currentTimeMillis();
        setDirty();
    }

    public static class RadioRuntimeState {
        public String sessionId = "";
        public long revision = 0L;
        public String ownerId = "";
        public String url = "";
        public String title = "";
        public String artist = "";
        public String thumbnail = "";
        public String queueStateJson = "";
        public float volume = 1.0f;
        public long positionMs = 0L;
        public long trackDurationMs = -1L;
        public int seekSerial = 0;
        public boolean playing = false;
        public boolean allowInventoryBroadcast = false;
        public long updatedAtMs = 0L;
        public Map<String, Long> knownTrackDurationsMs = new HashMap<>();

        public void sanitize() {
            sessionId = safe(sessionId);
            revision = Math.max(0L, revision);
            ownerId = safe(ownerId);
            url = safe(url);
            title = safe(title);
            artist = safe(artist);
            thumbnail = safe(thumbnail);
            queueStateJson = safe(queueStateJson);
            volume = Mth.clamp(volume, 0f, 2f);
            positionMs = Math.max(0L, positionMs);
            trackDurationMs = sanitizeDuration(trackDurationMs);
            seekSerial = Math.max(0, seekSerial);
            updatedAtMs = Math.max(0L, updatedAtMs);
            if (knownTrackDurationsMs == null) {
                knownTrackDurationsMs = new HashMap<>();
            } else {
                knownTrackDurationsMs.entrySet().removeIf(entry ->
                        entry == null
                                || entry.getKey() == null
                                || entry.getKey().isBlank()
                                || entry.getValue() == null
                                || entry.getValue() <= 0L
                                || entry.getValue() == Long.MAX_VALUE
                );
            }
            if (trackDurationMs > 0L && !url.isBlank()) {
                knownTrackDurationsMs.put(trackKey(url), trackDurationMs);
            } else if (trackDurationMs <= 0L && !url.isBlank()) {
                trackDurationMs = knownTrackDurationsMs.getOrDefault(trackKey(url), -1L);
            }
            if (!playing && trackDurationMs > 0L) {
                positionMs = Math.min(positionMs, trackDurationMs);
            }
        }
    }

    private static class RuntimeModel {
        private Map<String, RadioRuntimeState> states = new HashMap<>();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static long sanitizeDuration(long value) {
        if (value <= 0L || value == Long.MAX_VALUE) {
            return -1L;
        }
        return value;
    }

    private static String trackKey(String url) {
        String safeUrl = safe(url).trim();
        if (safeUrl.isBlank()) {
            return "";
        }
        try {
            URI uri = new URI(safeUrl);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (host.contains("youtube.com")) {
                String videoId = queryParam(uri.getRawQuery(), "v");
                if (!videoId.isBlank()) {
                    return "yt:" + videoId;
                }
            } else if (host.equals("youtu.be")) {
                String id = path.startsWith("/") ? path.substring(1) : path;
                int slash = id.indexOf('/');
                if (slash >= 0) {
                    id = id.substring(0, slash);
                }
                if (!id.isBlank()) {
                    return "yt:" + id;
                }
            }
            String normalizedPath = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
            return host + normalizedPath;
        } catch (URISyntaxException ignored) {
            return safeUrl;
        }
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank() || key == null || key.isBlank()) {
            return "";
        }
        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            int separator = pair.indexOf('=');
            String k = separator >= 0 ? pair.substring(0, separator) : pair;
            if (!key.equals(k)) {
                continue;
            }
            String value = separator >= 0 && separator + 1 < pair.length() ? pair.substring(separator + 1) : "";
            return value == null ? "" : value;
        }
        return "";
    }
}
