package net.jacobwasbeast.mediaradio.mixin;

import net.jacobwasbeast.mediaradio.client.render.RadioHeldOverlayRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class GuiMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void mediaradio$renderHeldOverlay(GuiGraphics guiGraphics, float partialTick, CallbackInfo callbackInfo) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null) {
            return;
        }
        RadioHeldOverlayRenderer.render(guiGraphics, minecraft);
    }
}

