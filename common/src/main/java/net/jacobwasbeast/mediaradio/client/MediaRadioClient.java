package net.jacobwasbeast.mediaradio.client;

import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.client.BalmClient;
import net.blay09.mods.balm.api.event.TickPhase;
import net.blay09.mods.balm.api.event.TickType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.jacobwasbeast.mediaradio.MediaRadio;
import net.jacobwasbeast.mediaradio.client.audio.ClientAudioEngine;
import net.jacobwasbeast.mediaradio.client.data.ClientMediaRepository;
import net.jacobwasbeast.mediaradio.client.render.RadioBlockEntityRenderer;
import net.jacobwasbeast.mediaradio.client.screen.RadioScreen;
import net.jacobwasbeast.mediaradio.item.RadioItem;
import net.jacobwasbeast.mediaradio.mixin.ItemPropertiesAccessor;
import net.jacobwasbeast.mediaradio.registry.ModBlockEntities;
import net.jacobwasbeast.mediaradio.registry.ModItems;

public class MediaRadioClient {

    public static void initialize() {
        ModKeyMappings.initialize();
        ClientMediaRepository.initialize();

        BalmClient.getRenderers().registerBlockEntityRenderer(
                MediaRadio.id("radio_block_entity"),
                () -> ModBlockEntities.RADIO_BLOCK_ENTITY.get(),
                RadioBlockEntityRenderer::new
        );

        registerItemPredicates();

        Balm.getEvents().onTickEvent(TickType.Client, TickPhase.End,
                (net.blay09.mods.balm.api.event.client.ClientTickHandler) minecraft -> ClientAudioEngine.getInstance().tick(minecraft));

        Balm.getEvents().onEvent(net.blay09.mods.balm.api.event.client.DisconnectedFromServerEvent.class,
                event -> {
                    ClientAudioEngine.getInstance().stopAll();
                    ClientMediaRepository.getInstance().reset();
                });
    }

    public static void openHandRadioScreen(InteractionHand hand) {
        Minecraft.getInstance().setScreen(RadioScreen.forHand(hand));
    }

    public static void openBlockRadioScreen(BlockPos blockPos) {
        Minecraft.getInstance().setScreen(RadioScreen.forBlock(blockPos));
    }

    private static void registerItemPredicates() {
        ItemPropertiesAccessor.mediaradio$register(ModItems.RADIO_ITEM, MediaRadio.id("place_mode"),
                (stack, level, entity, seed) -> RadioItem.isPlaceMode(stack) ? 1f : 0f);

        ItemPropertiesAccessor.mediaradio$register(ModItems.RADIO_ITEM, MediaRadio.id("offhand_occupied"),
                MediaRadioClient::offhandOccupiedPredicate);
    }

    private static float offhandOccupiedPredicate(ItemStack stack, net.minecraft.client.multiplayer.ClientLevel level, LivingEntity entity, int seed) {
        if (entity == null) {
            return 0f;
        }

        boolean mainHandRadio = entity.getMainHandItem().is(ModItems.RADIO_ITEM);
        boolean offHandOccupied = !entity.getOffhandItem().isEmpty();

        return mainHandRadio && offHandOccupied ? 1f : 0f;
    }
}
