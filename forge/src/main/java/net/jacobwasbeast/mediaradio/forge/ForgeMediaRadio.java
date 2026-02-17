package net.jacobwasbeast.mediaradio.forge;

import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.client.BalmClient;
import net.jacobwasbeast.mediaradio.MediaRadio;
import net.jacobwasbeast.mediaradio.client.MediaRadioClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod(MediaRadio.MOD_ID)
public class ForgeMediaRadio {

    public ForgeMediaRadio(FMLJavaModLoadingContext context) {
        Balm.initialize(MediaRadio.MOD_ID, MediaRadio::initialize);
        context.getModEventBus().addListener(this::onCommonSetup);
        DistExecutor.runWhenOn(Dist.CLIENT,
                () -> () -> BalmClient.initialize(MediaRadio.MOD_ID, MediaRadioClient::initialize));
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
            Class<?> compatClass = Class.forName("net.jacobwasbeast.mediaradio.forge.compat.CreateCompat");
            compatClass.getMethod("initialize").invoke(null);
        } catch (Exception exception) {
            MediaRadio.LOGGER.error("Failed to initialize Forge Create compatibility", exception);
        }
    }
}
