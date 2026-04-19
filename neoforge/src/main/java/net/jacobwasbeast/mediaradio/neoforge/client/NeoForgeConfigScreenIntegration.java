package net.jacobwasbeast.mediaradio.neoforge.client;

import net.jacobwasbeast.mediaradio.client.screen.MediaRadioAudioSettingsScreen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

public final class NeoForgeConfigScreenIntegration {

    private NeoForgeConfigScreenIntegration() {
    }

    public static void register(ModContainer container) {
        container.registerExtensionPoint(
                IConfigScreenFactory.class,
                (IConfigScreenFactory) (c, parent) ->
                        new MediaRadioAudioSettingsScreen(parent, net.minecraft.client.Minecraft.getInstance().options)
        );
    }
}
