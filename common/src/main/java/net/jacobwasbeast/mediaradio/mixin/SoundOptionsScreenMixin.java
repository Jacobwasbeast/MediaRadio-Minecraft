package net.jacobwasbeast.mediaradio.mixin;

import net.jacobwasbeast.mediaradio.client.settings.ClientAudioSettings;
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
        ClientAudioSettings settings = ClientAudioSettings.get();
        settings.load();
        int buttonWidth = 104;
        int buttonHeight = 20;
        int margin = 8;
        int x = margin;
        int y = height - buttonHeight - margin;
        switch (settings.soundOptionsButtonPosition()) {
            case TOP_LEFT -> {
                x = margin;
                y = margin;
            }
            case TOP_RIGHT -> {
                x = width - buttonWidth - margin;
                y = margin;
            }
            case BOTTOM_RIGHT -> {
                x = width - buttonWidth - margin;
                y = height - buttonHeight - margin;
            }
            case BOTTOM_LEFT -> {
                x = margin;
                y = height - buttonHeight - margin;
            }
        }
        addRenderableWidget(Button.builder(Component.literal("Media Radio"), button ->
                        minecraft.setScreen(new MediaRadioAudioSettingsScreen((Screen) (Object) this, minecraft.options)))
                .bounds(x, y, buttonWidth, buttonHeight)
                .build());
    }
}
