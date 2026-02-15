package net.jacobwasbeast.mediaradio.registry;

import net.blay09.mods.balm.api.DeferredObject;
import net.blay09.mods.balm.api.item.BalmItems;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.jacobwasbeast.mediaradio.MediaRadio;
import net.jacobwasbeast.mediaradio.item.RadioItem;

import static net.blay09.mods.balm.api.item.BalmItems.itemProperties;
import static net.jacobwasbeast.mediaradio.MediaRadio.id;

public class ModItems {

    public static Item RADIO_ITEM;
    public static DeferredObject<CreativeModeTab> CREATIVE_MODE_TAB;

    public static void initialize(BalmItems items) {
        items.registerItem((identifier) -> RADIO_ITEM = new RadioItem(itemProperties(identifier).stacksTo(1)), id("radio"));
        CREATIVE_MODE_TAB = items.registerCreativeModeTab(() -> new ItemStack(RADIO_ITEM), id(MediaRadio.MOD_ID));
        items.addToCreativeModeTab(id(MediaRadio.MOD_ID), () -> new ItemLike[]{RADIO_ITEM});
    }
}
