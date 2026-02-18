package net.jacobwasbeast.mediaradio.client.screen;

import net.jacobwasbeast.mediaradio.client.settings.ClientAudioSettings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.OptionsSubScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.client.Options;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class MediaRadioAudioSettingsScreen extends OptionsSubScreen {
    private static final Component TITLE = Component.literal("Media Radio Audio Settings");

    public MediaRadioAudioSettingsScreen(Screen parent, Options options) {
        super(parent, options, TITLE);
    }

    @Override
    protected void init() {
        ClientAudioSettings settings = ClientAudioSettings.get();
        settings.load();

        int left = width / 2 - 155;
        int right = left + 160;
        int full = width / 2 - 155;
        int y = height / 6 - 12;
        int rowGap = 24;

        addRenderableWidget(new VolumeSlider(
                left,
                y,
                150,
                "Volume Self Handheld",
                settings::selfHandheldVolume,
                settings::setSelfHandheldVolume
        ));
        addRenderableWidget(new VolumeSlider(
                right,
                y,
                150,
                "Volume Block Radio",
                settings::blockRadioVolume,
                settings::setBlockRadioVolume
        ));
        y += rowGap;
        addRenderableWidget(new VolumeSlider(
                full,
                y,
                310,
                "Volume Others Players Handheld",
                settings::otherPlayersHandheldVolume,
                settings::setOtherPlayersHandheldVolume
        ));
        y += rowGap;
        addRenderableWidget(CycleButton.onOffBuilder(settings.hearInventoryPlayerRadios())
                .create(
                        full,
                        y,
                        310,
                        20,
                        Component.literal("Hear In Inventory Player Radios"),
                        (button, value) -> settings.setHearInventoryPlayerRadios(value)
                ));

        y += rowGap;
        addRenderableWidget(CycleButton.<ClientAudioSettings.SoundOptionsButtonPosition>builder(position -> Component.literal(position.label()))
                .withValues(
                        ClientAudioSettings.SoundOptionsButtonPosition.TOP_LEFT,
                        ClientAudioSettings.SoundOptionsButtonPosition.TOP_RIGHT,
                        ClientAudioSettings.SoundOptionsButtonPosition.BOTTOM_LEFT,
                        ClientAudioSettings.SoundOptionsButtonPosition.BOTTOM_RIGHT
                )
                .withInitialValue(settings.soundOptionsButtonPosition())
                .create(
                        full,
                        y,
                        310,
                        20,
                        Component.literal("Sound Options Button Position"),
                        (button, value) -> settings.setSoundOptionsButtonPosition(value)
                ));

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(width / 2 - 100, height - 27, 200, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);
        guiGraphics.drawCenteredString(font, title, width / 2, 18, 0xFFFFFF);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private static class VolumeSlider extends AbstractSliderButton {
        private final String label;
        private final Consumer<Float> onChange;

        private VolumeSlider(int x, int y, int width, String label, Supplier<Float> getter, Consumer<Float> onChange) {
            super(x, y, width, 20, Component.empty(), Mth.clamp(getter.get(), 0f, 1f));
            this.label = label;
            this.onChange = onChange;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            float volume = (float) value;
            int percent = Math.round(volume * 100f);
            setMessage(Component.literal(label + ": " + String.format(Locale.ROOT, "%d%%", percent)));
        }

        @Override
        protected void applyValue() {
            float volume = (float) value;
            onChange.accept(volume);
            updateMessage();
        }
    }
}
