package net.jacobwasbeast.mediaradio.registry;

import net.blay09.mods.balm.api.DeferredObject;
import net.blay09.mods.balm.api.block.BalmBlocks;
import net.minecraft.world.level.block.Block;
import net.jacobwasbeast.mediaradio.block.RadioBlock;

import static net.jacobwasbeast.mediaradio.MediaRadio.id;

public class ModBlocks {

    public static DeferredObject<Block> RADIO_BLOCK;

    public static void initialize(BalmBlocks blocks) {
        RADIO_BLOCK = blocks.registerBlock(identifier -> new RadioBlock(), id("radio_block"));
    }
}
