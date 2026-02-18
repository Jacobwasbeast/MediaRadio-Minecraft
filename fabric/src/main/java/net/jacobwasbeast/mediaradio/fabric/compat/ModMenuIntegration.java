package net.jacobwasbeast.mediaradio.fabric.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.jacobwasbeast.mediaradio.client.screen.MediaRadioAudioSettingsScreen;
import net.minecraft.client.Minecraft;

@Environment(EnvType.CLIENT)
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return screen -> new MediaRadioAudioSettingsScreen(screen, Minecraft.getInstance().options);
    }
}
