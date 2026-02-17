package net.jacobwasbeast.mediaradio.client.settings;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ClientAudioSettings {
    private static final Gson GSON = new Gson();
    private static final ClientAudioSettings INSTANCE = new ClientAudioSettings();

    private boolean loaded;
    private float selfHandheldVolume = 1.0f;
    private float otherPlayersHandheldVolume = 1.0f;
    private float blockRadioVolume = 1.0f;
    private boolean hearInventoryPlayerRadios = true;

    public static ClientAudioSettings get() {
        return INSTANCE;
    }

    public synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = configPath();
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            String json = Files.readString(path);
            Model model = GSON.fromJson(json, Model.class);
            if (model == null) {
                return;
            }
            selfHandheldVolume = clampVolume(model.selfHandheldVolume);
            otherPlayersHandheldVolume = clampVolume(model.otherPlayersHandheldVolume);
            blockRadioVolume = clampVolume(model.blockRadioVolume);
            hearInventoryPlayerRadios = model.hearInventoryPlayerRadios == null ? true : model.hearInventoryPlayerRadios;
        } catch (IOException | JsonSyntaxException ignored) {
        }
    }

    public synchronized float selfHandheldVolume() {
        return selfHandheldVolume;
    }

    public synchronized void setSelfHandheldVolume(float value) {
        selfHandheldVolume = clampVolume(value);
        save();
    }

    public synchronized float otherPlayersHandheldVolume() {
        return otherPlayersHandheldVolume;
    }

    public synchronized void setOtherPlayersHandheldVolume(float value) {
        otherPlayersHandheldVolume = clampVolume(value);
        save();
    }

    public synchronized float blockRadioVolume() {
        return blockRadioVolume;
    }

    public synchronized void setBlockRadioVolume(float value) {
        blockRadioVolume = clampVolume(value);
        save();
    }

    public synchronized boolean hearInventoryPlayerRadios() {
        return hearInventoryPlayerRadios;
    }

    public synchronized void setHearInventoryPlayerRadios(boolean value) {
        hearInventoryPlayerRadios = value;
        save();
    }

    private synchronized void save() {
        Path path = configPath();
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Model model = new Model();
            model.selfHandheldVolume = selfHandheldVolume;
            model.otherPlayersHandheldVolume = otherPlayersHandheldVolume;
            model.blockRadioVolume = blockRadioVolume;
            model.hearInventoryPlayerRadios = hearInventoryPlayerRadios;
            Files.writeString(path, GSON.toJson(model));
        } catch (IOException ignored) {
        }
    }

    private static float clampVolume(float value) {
        return Mth.clamp(value, 0f, 1.0f);
    }

    private static Path configPath() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return null;
        }
        return minecraft.gameDirectory.toPath().resolve("config").resolve("mediaradio-client-audio-settings.json");
    }

    private static class Model {
        private float selfHandheldVolume = 1.0f;
        private float otherPlayersHandheldVolume = 1.0f;
        private float blockRadioVolume = 1.0f;
        private Boolean hearInventoryPlayerRadios = true;
    }
}
