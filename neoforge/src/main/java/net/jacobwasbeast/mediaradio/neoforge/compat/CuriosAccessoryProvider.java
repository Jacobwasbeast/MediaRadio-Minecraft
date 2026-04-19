package net.jacobwasbeast.mediaradio.neoforge.compat;

import net.jacobwasbeast.mediaradio.compat.RadioAccessoryProvider;
import net.jacobwasbeast.mediaradio.item.RadioItem;
import net.jacobwasbeast.mediaradio.registry.ModItems;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.util.Map;
import java.util.Set;

public final class CuriosAccessoryProvider implements RadioAccessoryProvider {

    @Override
    public void collectRadioIds(Player player, Set<String> collector) {
        if (player == null || collector == null) {
            return;
        }
        forEachRadio(player, (slotType, stack) -> {
            String radioId = RadioItem.getRadioId(stack);
            if (!radioId.isBlank()) {
                collector.add(radioId);
            }
        });
    }

    @Override
    public boolean hasRadioWithId(Player player, String radioId) {
        return !findRadioStack(player, radioId).isEmpty();
    }

    @Override
    public ItemStack findRadioStack(Player player, String radioId) {
        if (player == null || radioId == null || radioId.isBlank()) {
            return ItemStack.EMPTY;
        }
        ItemStack[] match = {ItemStack.EMPTY};
        forEachRadio(player, (slotType, stack) -> {
            if (!match[0].isEmpty()) {
                return;
            }
            if (radioId.equals(RadioItem.getRadioId(stack))) {
                match[0] = stack;
            }
        });
        return match[0];
    }

    private static void forEachRadio(Player player, java.util.function.BiConsumer<String, ItemStack> consumer) {
        CuriosApi.getCuriosInventory(player).ifPresent(inventory -> {
            Map<String, ICurioStacksHandler> map = inventory.getCurios();
            if (map == null) {
                return;
            }
            for (Map.Entry<String, ICurioStacksHandler> entry : map.entrySet()) {
                ICurioStacksHandler handler = entry.getValue();
                if (handler == null) {
                    continue;
                }
                IDynamicStackHandler stacks = handler.getStacks();
                if (stacks == null) {
                    continue;
                }
                for (int i = 0; i < stacks.getSlots(); i++) {
                    ItemStack stack = stacks.getStackInSlot(i);
                    if (stack.is(ModItems.RADIO_ITEM) && !RadioItem.isPlaceMode(stack)) {
                        consumer.accept(entry.getKey(), stack);
                    }
                }
            }
        });
    }
}
