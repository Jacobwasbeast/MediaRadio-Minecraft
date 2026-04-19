package net.jacobwasbeast.mediaradio.mixin;

import net.jacobwasbeast.mediaradio.client.audio.ClientAudioEngine;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundEngine.class)
public class SoundEngineMixin {

    @Inject(method = "reload", remap = false, at = @At("HEAD"))
    private void mediaradio$onReload(CallbackInfo callbackInfo) {
        ClientAudioEngine.getInstance().stopAll();
    }

    @Inject(method = "stopAll", remap = false, at = @At("HEAD"))
    private void mediaradio$onStopAll(CallbackInfo callbackInfo) {
        ClientAudioEngine.getInstance().stopAll();
    }
}
