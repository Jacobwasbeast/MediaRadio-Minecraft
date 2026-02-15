package net.jacobwasbeast.mediaradio.registry;

import net.blay09.mods.balm.api.DeferredObject;
import net.blay09.mods.balm.api.block.BalmBlockEntities;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.jacobwasbeast.mediaradio.block.entity.RadioBlockEntity;

import static net.jacobwasbeast.mediaradio.MediaRadio.id;

public class ModBlockEntities {

    public static DeferredObject<BlockEntityType<RadioBlockEntity>> RADIO_BLOCK_ENTITY;

    public static void initialize(BalmBlockEntities blockEntities) {
        RADIO_BLOCK_ENTITY = blockEntities.registerBlockEntity(
                id("radio_block_entity"),
                RadioBlockEntity::new,
                () -> new Block[]{ModBlocks.RADIO_BLOCK.get()}
        );
    }
}
