package net.jacobwasbeast.mediaradio.forge.client;

import net.jacobwasbeast.mediaradio.client.screen.MediaRadioAudioSettingsScreen;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;

public final class ForgeConfigScreenIntegration {

    private ForgeConfigScreenIntegration() {
    }

    public static void register() {
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) ->
                        new MediaRadioAudioSettingsScreen(parent, minecraft.options))
        );
    }
}
