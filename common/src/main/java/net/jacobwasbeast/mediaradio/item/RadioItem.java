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
import net.jacobwasbeast.mediaradio.server.SharedMediaManager;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public class RadioItem extends Item {

    public static final String TAG_PLACE_MODE = "PlaceMode";
    public static final String TAG_RADIO_ID = "RadioId";
    public static final String TAG_OWNER_ID = "RadioOwnerId";
    public static final String TAG_ALLOW_INVENTORY_BROADCAST = "AllowInventoryBroadcast";
    private static final String TAG_URL = "RadioUrl";
    private static final String TAG_TITLE = "RadioTitle";
    private static final String TAG_ARTIST = "RadioArtist";
    private static final String TAG_THUMBNAIL = "RadioThumbnail";
    private static final String TAG_POSITION = "RadioPosition";
    private static final String TAG_VOLUME = "RadioVolume";
    public static final String TAG_QUEUE_STATE = "RadioQueueState";

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
        ensureRadioId(stack);
        setOwnerId(stack, player.getStringUUID());

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

        // In block placement mode, this item should only be used for placing.
        if (isPlaceMode(stack)) {
            return InteractionResultHolder.pass(stack);
        }

        if (level.isClientSide) {
            MediaRadioClient.openHandRadioScreen(hand);
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        ensureRadioId(stack);
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
            applyItemDataToBlockEntity(stack, radioBlockEntity, player);
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
        String url = getString(stack, TAG_URL);
        if (!title.isBlank()) {
            tooltipComponents.add(Component.literal("Saved: " + title).withStyle(ChatFormatting.GRAY));
        } else if (!url.isBlank()) {
            tooltipComponents.add(Component.literal("Saved: " + url).withStyle(ChatFormatting.GRAY));
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
        String radioId = radioBlockEntity.getRadioId();
        if (radioId == null || radioId.isBlank()) {
            radioId = UUID.randomUUID().toString();
            radioBlockEntity.setRadioId(radioId);
        }
        tag.putString(TAG_RADIO_ID, radioId);
        tag.putString(TAG_OWNER_ID, safe(radioBlockEntity.getOwnerId()));
        tag.remove(TAG_URL);
        tag.remove(TAG_TITLE);
        tag.remove(TAG_ARTIST);
        tag.remove(TAG_THUMBNAIL);
        tag.remove(TAG_POSITION);
        tag.remove(TAG_VOLUME);
        tag.remove(TAG_QUEUE_STATE);
    }

    public static void applyItemDataToBlockEntity(ItemStack stack, RadioBlockEntity radioBlockEntity, @Nullable Player placer) {
        String radioId = getOrCreateRadioId(stack);
        String ownerId = getOwnerId(stack);
        String placerId = placer == null ? "" : safe(placer.getStringUUID());
        boolean ownershipChanged = !placerId.isBlank() && !ownerId.isBlank() && !ownerId.equals(placerId);
        boolean legacyOwnerlessPlacement = !placerId.isBlank() && ownerId.isBlank();
        if (ownershipChanged || legacyOwnerlessPlacement) {
            // Ownership transfer (or legacy ownerless handoff): prevent inheriting stale runtime identity.
            radioId = UUID.randomUUID().toString();
            stack.getOrCreateTag().putString(TAG_RADIO_ID, radioId);
        }
        if (!placerId.isBlank()) {
            setOwnerId(stack, placerId);
            radioBlockEntity.setOwnerId(placerId);
        } else {
            radioBlockEntity.setOwnerId(ownerId);
        }
        radioBlockEntity.setRadioId(radioId);
        SharedMediaManager.applyRuntimeStateToBlockEntity(radioBlockEntity);
    }

    public static void applyItemDataToBlockEntity(ItemStack stack, RadioBlockEntity radioBlockEntity) {
        applyItemDataToBlockEntity(stack, radioBlockEntity, null);
    }

    private static String getString(ItemStack stack, String key) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return "";
        }
        return tag.getString(key);
    }

    public static String getSavedUrl(ItemStack stack) {
        return getString(stack, TAG_URL);
    }

    public static String getSavedTitle(ItemStack stack) {
        return getString(stack, TAG_TITLE);
    }

    public static String getSavedArtist(ItemStack stack) {
        return getString(stack, TAG_ARTIST);
    }

    public static String getSavedThumbnail(ItemStack stack) {
        return getString(stack, TAG_THUMBNAIL);
    }

    public static long getSavedPositionMs(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return 0L;
        }
        return Math.max(0L, tag.getLong(TAG_POSITION));
    }

    public static float getSavedVolume(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_VOLUME)) {
            return 1.0f;
        }
        return Math.max(0.0f, Math.min(2.0f, tag.getFloat(TAG_VOLUME)));
    }

    public static String getSavedQueueState(ItemStack stack) {
        return getString(stack, TAG_QUEUE_STATE);
    }

    public static void saveRuntimeState(ItemStack stack, String url, String title, String artist, String thumbnail, long positionMs, float volume, String queueStateJson) {
        CompoundTag tag = stack.getOrCreateTag();
        String safeUrl = url == null ? "" : url;
        if (!safeUrl.isBlank() || !tag.contains(TAG_URL)) {
            tag.putString(TAG_URL, safeUrl);
        }

        String safeTitle = title == null ? "" : title;
        if (!safeTitle.isBlank() || !tag.contains(TAG_TITLE)) {
            tag.putString(TAG_TITLE, safeTitle);
        }

        String safeArtist = artist == null ? "" : artist;
        if (!safeArtist.isBlank() || !tag.contains(TAG_ARTIST)) {
            tag.putString(TAG_ARTIST, safeArtist);
        }

        String safeThumbnail = thumbnail == null ? "" : thumbnail;
        if (!safeThumbnail.isBlank() || !tag.contains(TAG_THUMBNAIL)) {
            tag.putString(TAG_THUMBNAIL, safeThumbnail);
        }
        tag.putLong(TAG_POSITION, Math.max(0L, positionMs));
        tag.putFloat(TAG_VOLUME, Math.max(0.0f, Math.min(2.0f, volume)));
        tag.putString(TAG_QUEUE_STATE, queueStateJson == null ? "" : queueStateJson);
        getOrCreateRadioId(stack);
    }

    public static String getOrCreateRadioId(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        String existing = tag.getString(TAG_RADIO_ID);
        if (existing == null || existing.isBlank()) {
            existing = UUID.randomUUID().toString();
            tag.putString(TAG_RADIO_ID, existing);
        }
        return existing;
    }

    public static String getRadioId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return "";
        }
        return tag.getString(TAG_RADIO_ID);
    }

    public static String getOwnerId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return "";
        }
        return safe(tag.getString(TAG_OWNER_ID));
    }

    public static void setOwnerId(ItemStack stack, String ownerId) {
        stack.getOrCreateTag().putString(TAG_OWNER_ID, safe(ownerId));
    }

    public static boolean isInventoryBroadcastAllowed(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(TAG_ALLOW_INVENTORY_BROADCAST);
    }

    public static void setInventoryBroadcastAllowed(ItemStack stack, boolean allowed) {
        CompoundTag tag = stack.getOrCreateTag();
        if (allowed) {
            tag.putBoolean(TAG_ALLOW_INVENTORY_BROADCAST, true);
            return;
        }
        tag.remove(TAG_ALLOW_INVENTORY_BROADCAST);
    }

    private static void ensureRadioId(ItemStack stack) {
        getOrCreateRadioId(stack);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
