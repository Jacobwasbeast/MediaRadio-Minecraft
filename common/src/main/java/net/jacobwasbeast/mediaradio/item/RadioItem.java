package net.jacobwasbeast.mediaradio.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.jacobwasbeast.mediaradio.block.RadioBlock;
import net.jacobwasbeast.mediaradio.block.entity.RadioBlockEntity;
import net.jacobwasbeast.mediaradio.client.MediaRadioClient;
import net.jacobwasbeast.mediaradio.registry.ModBlocks;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class RadioItem extends Item {

    public static final String TAG_PLACE_MODE = "PlaceMode";
    private static final String TAG_URL = "RadioUrl";
    private static final String TAG_TITLE = "RadioTitle";
    private static final String TAG_ARTIST = "RadioArtist";
    private static final String TAG_THUMBNAIL = "RadioThumbnail";
    private static final String TAG_POSITION = "RadioPosition";
    private static final String TAG_VOLUME = "RadioVolume";

    public RadioItem(Properties properties) {
        super(properties);
    }

    public static boolean isPlaceMode(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(TAG_PLACE_MODE);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (player.isShiftKeyDown()) {
            setPlaceMode(stack, !isPlaceMode(stack));
            if (player instanceof ServerPlayer serverPlayer) {
                boolean placeMode = isPlaceMode(stack);
                serverPlayer.displayClientMessage(
                        Component.literal(placeMode ? "Radio switched to block mode" : "Radio switched to handheld mode")
                                .withStyle(placeMode ? ChatFormatting.AQUA : ChatFormatting.GOLD),
                        true
                );
            }
            level.playSound(null, player.blockPosition(), SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.PLAYERS, 0.45f, 1.2f);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        if (level.isClientSide) {
            MediaRadioClient.openHandRadioScreen(hand);
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        Player player = context.getPlayer();
        if (player == null || !isPlaceMode(stack)) {
            return InteractionResult.PASS;
        }

        Level level = context.getLevel();
        BlockPos placePos = context.getClickedPos().relative(context.getClickedFace());

        BlockState placeState = ModBlocks.RADIO_BLOCK.get().defaultBlockState();
        Direction facing = player.getDirection().getOpposite();
        placeState = placeState.setValue(RadioBlock.FACING, facing);

        if (!level.getBlockState(placePos).canBeReplaced(new BlockPlaceContext(context))) {
            return InteractionResult.FAIL;
        }

        if (!level.setBlock(placePos, placeState, 3)) {
            return InteractionResult.FAIL;
        }

        BlockEntity blockEntity = level.getBlockEntity(placePos);
        if (blockEntity instanceof RadioBlockEntity radioBlockEntity) {
            applyItemDataToBlockEntity(stack, radioBlockEntity);
        }

        level.playSound(null, placePos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.85f, 1.0f);

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }

        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltipComponents, TooltipFlag isAdvanced) {
        super.appendHoverText(stack, level, tooltipComponents, isAdvanced);
        tooltipComponents.add(Component.literal(isPlaceMode(stack) ? "Mode: Block Placement" : "Mode: Handheld")
                .withStyle(ChatFormatting.AQUA));

        String title = getString(stack, TAG_TITLE);
        String artist = getString(stack, TAG_ARTIST);
        if (!title.isBlank()) {
            tooltipComponents.add(Component.literal("Saved: " + title).withStyle(ChatFormatting.GRAY));
        }
        if (!artist.isBlank()) {
            tooltipComponents.add(Component.literal("By: " + artist).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static void setPlaceMode(ItemStack stack, boolean value) {
        stack.getOrCreateTag().putBoolean(TAG_PLACE_MODE, value);
    }

    public static void writeBlockEntityData(ItemStack stack, RadioBlockEntity radioBlockEntity) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(TAG_URL, radioBlockEntity.getMediaUrl());
        tag.putString(TAG_TITLE, radioBlockEntity.getMediaTitle());
        tag.putString(TAG_ARTIST, radioBlockEntity.getMediaArtist());
        tag.putString(TAG_THUMBNAIL, radioBlockEntity.getMediaThumbnail());
        tag.putLong(TAG_POSITION, radioBlockEntity.getPlaybackPositionMs());
        tag.putFloat(TAG_VOLUME, radioBlockEntity.getVolume());
    }

    public static void applyItemDataToBlockEntity(ItemStack stack, RadioBlockEntity radioBlockEntity) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return;
        }
        radioBlockEntity.setMedia(
                getString(stack, TAG_URL),
                getString(stack, TAG_TITLE),
                getString(stack, TAG_ARTIST),
                getString(stack, TAG_THUMBNAIL)
        );
        radioBlockEntity.setVolume(tag.contains(TAG_VOLUME) ? tag.getFloat(TAG_VOLUME) : 1.0f);
        long position = Math.max(0L, tag.getLong(TAG_POSITION));
        radioBlockEntity.setPausedPositionMs(position);
    }

    private static String getString(ItemStack stack, String key) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return "";
        }
        return tag.getString(key);
    }
}
