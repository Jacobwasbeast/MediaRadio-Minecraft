package net.jacobwasbeast.mediaradio.compat;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

public interface RadioAccessoryProvider {

    RadioAccessoryProvider NO_OP = new RadioAccessoryProvider() {};

    default void collectRadioIds(Player player, Set<String> collector) {
    }

    default boolean hasRadioWithId(Player player, String radioId) {
        return false;
    }

    default ItemStack findRadioStack(Player player, String radioId) {
        return ItemStack.EMPTY;
    }
}
