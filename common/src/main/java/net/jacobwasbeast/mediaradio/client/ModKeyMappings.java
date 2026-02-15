package net.jacobwasbeast.mediaradio.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.blay09.mods.kuma.api.InputBinding;
import net.blay09.mods.kuma.api.Kuma;
import net.blay09.mods.kuma.api.ManagedKeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.jacobwasbeast.mediaradio.registry.ModItems;

import static net.jacobwasbeast.mediaradio.MediaRadio.id;

public class ModKeyMappings {

    public static ManagedKeyMapping OPEN_RADIO;

    public static void initialize() {
        OPEN_RADIO = Kuma.createKeyMapping(id("open_radio"))
                .withDefault(InputBinding.key(InputConstants.KEY_R))
                .handleWorldInput(event -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft.player == null) {
                        return false;
                    }

                    if (minecraft.player.getMainHandItem().is(ModItems.RADIO_ITEM)) {
                        MediaRadioClient.openHandRadioScreen(InteractionHand.MAIN_HAND);
                        return true;
                    }

                    if (minecraft.player.getOffhandItem().is(ModItems.RADIO_ITEM)) {
                        MediaRadioClient.openHandRadioScreen(InteractionHand.OFF_HAND);
                        return true;
                    }

                    return false;
                })
                .build();
    }
}
