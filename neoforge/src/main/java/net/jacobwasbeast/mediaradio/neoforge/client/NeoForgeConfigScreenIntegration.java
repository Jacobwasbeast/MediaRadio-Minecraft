package net.jacobwasbeast.mediaradio.neoforge.client;

import net.jacobwasbeast.mediaradio.client.screen.MediaRadioAudioSettingsScreen;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

public final class NeoForgeConfigScreenIntegration {

    private NeoForgeConfigScreenIntegration() {
    }

    public static void register() {
        ModLoadingContext.get().registerExtensionPoint(
                IConfigScreenFactory.class,
                (IConfigScreenFactory) (container, parent) ->
                        new MediaRadioAudioSettingsScreen(parent, net.minecraft.client.Minecraft.getInstance().options)
        );
    }
}
