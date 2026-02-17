package net.jacobwasbeast.mediaradio.fabric;

import net.blay09.mods.balm.api.Balm;
import net.fabricmc.api.ModInitializer;
import net.jacobwasbeast.mediaradio.MediaRadio;

public class FabricMediaRadio implements ModInitializer {
    @Override
    public void onInitialize() {
        Balm.initialize(MediaRadio.MOD_ID, MediaRadio::initialize);
    }
}
