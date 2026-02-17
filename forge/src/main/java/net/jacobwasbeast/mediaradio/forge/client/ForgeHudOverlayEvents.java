package net.jacobwasbeast.mediaradio.forge.client;

import net.jacobwasbeast.mediaradio.MediaRadio;
import net.jacobwasbeast.mediaradio.client.render.RadioHeldOverlayRenderer;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MediaRadio.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ForgeHudOverlayEvents {

    private ForgeHudOverlayEvents() {
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
