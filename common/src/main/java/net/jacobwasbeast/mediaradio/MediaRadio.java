package net.jacobwasbeast.mediaradio;

import net.blay09.mods.balm.api.Balm;
import net.minecraft.resources.ResourceLocation;
import net.jacobwasbeast.mediaradio.network.ModNetworking;
import net.jacobwasbeast.mediaradio.registry.ModBlockEntities;
import net.jacobwasbeast.mediaradio.registry.ModBlocks;
import net.jacobwasbeast.mediaradio.registry.ModItems;
import net.jacobwasbeast.mediaradio.server.SharedMediaManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MediaRadio {

    public static final String MOD_ID = "mediaradio";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void initialize() {
        ModBlocks.initialize(Balm.getBlocks());
        ModBlockEntities.initialize(Balm.getBlockEntities());
        ModItems.initialize(Balm.getItems());
        ModNetworking.initialize(Balm.getNetworking());
        SharedMediaManager.initialize();
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
