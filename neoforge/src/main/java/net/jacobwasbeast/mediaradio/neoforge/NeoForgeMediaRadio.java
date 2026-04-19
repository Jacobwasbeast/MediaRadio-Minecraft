package net.jacobwasbeast.mediaradio.neoforge;

import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.client.BalmClient;
import net.blay09.mods.balm.neoforge.NeoForgeLoadContext;
import net.jacobwasbeast.mediaradio.MediaRadio;
import net.jacobwasbeast.mediaradio.client.MediaRadioClient;
import net.jacobwasbeast.mediaradio.neoforge.client.NeoForgeConfigScreenIntegration;
import net.jacobwasbeast.mediaradio.server.SharedMediaManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(MediaRadio.MOD_ID)
public class NeoForgeMediaRadio {

    public NeoForgeMediaRadio(IEventBus modEventBus, ModContainer modContainer) {
        NeoForgeLoadContext context = new NeoForgeLoadContext(modEventBus);
        Balm.initialize(MediaRadio.MOD_ID, context, MediaRadio::initialize);
        modEventBus.addListener(this::onCommonSetup);
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> SharedMediaManager.tickServer(event.getServer()));
        if (FMLEnvironment.dist == Dist.CLIENT) {
            initializeClient(context, modContainer, modEventBus);
        }
    }

    private static void initializeClient(NeoForgeLoadContext context, ModContainer modContainer, IEventBus modEventBus) {
        BalmClient.initialize(MediaRadio.MOD_ID, context, MediaRadioClient::initialize);
        NeoForgeConfigScreenIntegration.register(modContainer);
        modEventBus.addListener((FMLClientSetupEvent event) -> {
            if (ModList.get().isLoaded("curios")) {
                tryInitializeCuriosClient();
            }
        });
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        if (ModList.get().isLoaded("create")) {
            tryInitializeCreateCompat();
        }
        if (ModList.get().isLoaded("curios")) {
            tryInitializeCuriosCommon();
        }
    }

    private static void tryInitializeCuriosCommon() {
        try {
            Class<?> compatClass = Class.forName("net.jacobwasbeast.mediaradio.neoforge.compat.CuriosCompat");
            compatClass.getMethod("initialize").invoke(null);
        } catch (Exception exception) {
            MediaRadio.LOGGER.error("Failed to initialize Curios accessory provider", exception);
        }
    }

    private static void tryInitializeCuriosClient() {
        try {
            Class<?> compatClass = Class.forName("net.jacobwasbeast.mediaradio.neoforge.compat.CuriosCompat");
            compatClass.getMethod("initializeClient").invoke(null);
        } catch (Exception exception) {
            MediaRadio.LOGGER.error("Failed to initialize Curios client renderer", exception);
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
