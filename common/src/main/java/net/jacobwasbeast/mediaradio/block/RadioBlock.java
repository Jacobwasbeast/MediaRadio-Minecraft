package net.jacobwasbeast.mediaradio.block;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.jacobwasbeast.mediaradio.block.entity.RadioBlockEntity;
import net.jacobwasbeast.mediaradio.client.MediaRadioClient;
import net.jacobwasbeast.mediaradio.item.RadioItem;
import net.jacobwasbeast.mediaradio.registry.ModBlockEntities;
import net.jacobwasbeast.mediaradio.registry.ModItems;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

public class RadioBlock extends BaseEntityBlock implements EntityBlock {

    public static final com.mojang.serialization.MapCodec<RadioBlock> CODEC = simpleCodec(properties -> new RadioBlock());
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape NORTH_SHAPE = Shapes.or(
            // Main body and front panel depth from the block model.
            box(1.0, 1.0, 2.35, 15.0, 10.0, 13.0),
            // Antenna base.
            box(13.6, 10.0, 10.8, 14.4, 12.0, 11.6),
            // Antenna rod.
            box(13.9, 12.0, 11.1, 14.1, 16.0, 11.3)
    );
    private static final Map<Direction, VoxelShape> SHAPES_BY_FACING = createShapesByFacing();

    public RadioBlock() {
        super(BlockBehaviour.Properties.of().strength(1.6f).sound(SoundType.METAL).noOcclusion());
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(BlockState blockState) {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES_BYFACING(state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES_BYFACING(state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
        return new RadioBlockEntity(blockPos, blockState);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(blockEntityType, ModBlockEntities.RADIO_BLOCK_ENTITY.get(), RadioBlockEntity::serverTick);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide) {
                pickupRadio(level, pos, player);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (level.isClientSide) {
            MediaRadioClient.openBlockRadioScreen(pos);
        }

        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && !player.isCreative()) {
            ItemStack stack = createStackFromBlock(level, pos);
            popResource(level, pos, stack);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    private void pickupRadio(Level level, BlockPos pos, Player player) {
        ItemStack stack = createStackFromBlock(level, pos);

        level.removeBlock(pos, false);

        boolean given = player.addItem(stack);
        if (!given) {
            player.drop(stack, false);
        }

        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.displayClientMessage(Component.literal("Picked up radio").withStyle(ChatFormatting.GOLD), true);
        }
    }

    private ItemStack createStackFromBlock(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        ItemStack stack = new ItemStack(ModItems.RADIO_ITEM);
        if (blockEntity instanceof RadioBlockEntity radioBlockEntity) {
            RadioItem.writeBlockEntityData(stack, radioBlockEntity);
        }
        return stack;
    }

    private static VoxelShape SHAPES_BYFACING(BlockState state) {
        return SHAPES_BY_FACING.getOrDefault(state.getValue(FACING), NORTH_SHAPE);
    }

    private static Map<Direction, VoxelShape> createShapesByFacing() {
        EnumMap<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        shapes.put(Direction.NORTH, NORTH_SHAPE);
        shapes.put(Direction.EAST, rotateShape(NORTH_SHAPE, 1));
        shapes.put(Direction.SOUTH, rotateShape(NORTH_SHAPE, 2));
        shapes.put(Direction.WEST, rotateShape(NORTH_SHAPE, 3));
        return shapes;
    }

    private static VoxelShape rotateShape(VoxelShape shape, int quarterTurnsClockwise) {
        VoxelShape rotated = shape;
        for (int i = 0; i < quarterTurnsClockwise; i++) {
            VoxelShape next = Shapes.empty();
            for (AABB aabb : rotated.toAabbs()) {
                next = Shapes.or(next, Shapes.box(
                        1.0 - aabb.maxZ,
                        aabb.minY,
                        aabb.minX,
                        1.0 - aabb.minZ,
                        aabb.maxY,
                        aabb.maxX
                ));
            }
            rotated = next.optimize();
        }
        return rotated;
    }
}
