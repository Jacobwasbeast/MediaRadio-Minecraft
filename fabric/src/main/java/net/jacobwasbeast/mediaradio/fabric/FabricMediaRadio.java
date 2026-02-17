package net.jacobwasbeast.mediaradio.fabric;

import net.blay09.mods.balm.api.Balm;
import net.fabricmc.api.ModInitializer;
import net.jacobwasbeast.mediaradio.MediaRadio;
import net.fabricmc.loader.api.FabricLoader;

public class FabricMediaRadio implements ModInitializer {
    @Override
    public void onInitialize() {
        Balm.initialize(MediaRadio.MOD_ID, MediaRadio::initialize);
        if (FabricLoader.getInstance().isModLoaded("create")) {
            tryInitializeCreateCompat();
        }
    }

    private static void tryInitializeCreateCompat() {
        try {
            MediaRadio.LOGGER.info("==========================");
            MediaRadio.LOGGER.info("MEDIA RADIO CREATE API LOADED");
            MediaRadio.LOGGER.info("==========================");
            Class<?> compatClass = Class.forName("net.jacobwasbeast.mediaradio.fabric.compat.CreateCompat");
            compatClass.getMethod("initialize").invoke(null);
        } catch (Exception exception) {
            MediaRadio.LOGGER.error("Failed to initialize Fabric Create compatibility", exception);
        }
    }
}
