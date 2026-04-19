package net.jacobwasbeast.mediaradio.neoforge;

import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.client.BalmClient;
import net.jacobwasbeast.mediaradio.MediaRadio;
import net.jacobwasbeast.mediaradio.client.MediaRadioClient;
import net.jacobwasbeast.mediaradio.neoforge.client.NeoForgeConfigScreenIntegration;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(MediaRadio.MOD_ID)
public class NeoForgeMediaRadio {

    public NeoForgeMediaRadio(IEventBus modEventBus) {
        Balm.initialize(MediaRadio.MOD_ID, modEventBus, MediaRadio::initialize);
        modEventBus.addListener(this::onCommonSetup);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            initializeClient(modEventBus);
        }
    }

    private static void initializeClient(IEventBus modEventBus) {
        BalmClient.initialize(MediaRadio.MOD_ID, modEventBus, MediaRadioClient::initialize);
        NeoForgeConfigScreenIntegration.register();
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        if (ModList.get().isLoaded("create")) {
            tryInitializeCreateCompat();
        }
    }

    private static void tryInitializeCreateCompat() {
        try {
            MediaRadio.LOGGER.info("==========================");
            MediaRadio.LOGGER.info("MEDIA RADIO CREATE API LOADED");
            MediaRadio.LOGGER.info("==========================");
            Class<?> compatClass = Class.forName("net.jacobwasbeast.mediaradio.neoforge.compat.CreateCompat");
            compatClass.getMethod("initialize").invoke(null);
        } catch (Exception exception) {
            MediaRadio.LOGGER.error("Failed to initialize NeoForge Create compatibility", exception);
        }
    }
}
