package net.jacobwasbeast.mediaradio.neoforge.compat;

import net.jacobwasbeast.mediaradio.MediaRadio;
import net.jacobwasbeast.mediaradio.compat.RadioAccessoryHooks;
import net.jacobwasbeast.mediaradio.registry.ModItems;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;

public final class CuriosCompat {

    private CuriosCompat() {
    }

    public static void initialize() {
        try {
            RadioAccessoryHooks.set(new CuriosAccessoryProvider());
            MediaRadio.LOGGER.info("Curios radio accessory provider registered");
        } catch (Throwable throwable) {
            MediaRadio.LOGGER.error("Failed to register Curios accessory provider", throwable);
        }
    }

    public static void initializeClient() {
        try {
            CuriosRendererRegistry.register(ModItems.RADIO_ITEM, RadioCuriosRenderer::new);
            MediaRadio.LOGGER.info("Curios radio renderer registered");
        } catch (Throwable throwable) {
            MediaRadio.LOGGER.error("Failed to register Curios radio renderer", throwable);
        }
    }
}
