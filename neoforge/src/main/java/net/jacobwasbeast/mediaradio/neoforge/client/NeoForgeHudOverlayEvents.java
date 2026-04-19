package net.jacobwasbeast.mediaradio.neoforge.client;

import net.jacobwasbeast.mediaradio.MediaRadio;
import net.jacobwasbeast.mediaradio.client.render.RadioHeldOverlayRenderer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

@EventBusSubscriber(modid = MediaRadio.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class NeoForgeHudOverlayEvents {

    private NeoForgeHudOverlayEvents() {
    }

    @SubscribeEvent
    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null) {
            return;
        }
        RadioHeldOverlayRenderer.render(event.getGuiGraphics(), minecraft);
    }
}
