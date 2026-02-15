package net.jacobwasbeast.mediaradio.fabric;

import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.EmptyLoadContext;
import net.fabricmc.api.ModInitializer;
import net.jacobwasbeast.mediaradio.MediaRadio;

public class FabricMediaRadio implements ModInitializer {
    @Override
    public void onInitialize() {
        Balm.initializeMod(MediaRadio.MOD_ID, EmptyLoadContext.INSTANCE, MediaRadio::initialize);
    }
}
