package net.jacobwasbeast.mediaradio.mixin;

import net.jacobwasbeast.mediaradio.client.screen.MediaRadioAudioSettingsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.SoundOptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundOptionsScreen.class)
public abstract class SoundOptionsScreenMixin extends Screen {
    protected SoundOptionsScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void mediaradio$addSettingsButton(CallbackInfo callbackInfo) {
        addRenderableWidget(Button.builder(Component.literal("Media Radio"), button ->
                        minecraft.setScreen(new MediaRadioAudioSettingsScreen((Screen) (Object) this, minecraft.options)))
                .bounds(width - 112, 8, 104, 20)
                .build());
    }
}
