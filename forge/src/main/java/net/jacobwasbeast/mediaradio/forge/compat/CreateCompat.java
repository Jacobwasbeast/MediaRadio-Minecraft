package net.jacobwasbeast.mediaradio.forge.compat;

import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import net.jacobwasbeast.mediaradio.MediaRadio;
import net.jacobwasbeast.mediaradio.block.entity.RadioBlockEntity;
import net.jacobwasbeast.mediaradio.client.audio.ClientAudioEngine;
import net.jacobwasbeast.mediaradio.client.render.RadioBlockEntityRenderer;
import net.jacobwasbeast.mediaradio.network.ModNetworking;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Map;
import java.util.UUID;

public final class CreateCompat {

    private CreateCompat() {
    }

    public static void initialize() {
        try {
            Block radioBlock = BuiltInRegistries.BLOCK.get(MediaRadio.id("radio_block"));
            if (radioBlock == Blocks.AIR) {
                MediaRadio.LOGGER.warn("Create compatibility skipped: radio block not yet registered");
                return;
            }
            MovingInteractionBehaviour.REGISTRY.register(radioBlock, new RadioMovingInteraction());
            MovementBehaviour.REGISTRY.register(radioBlock, new RadioMovingDisplayBehaviour());
            MediaRadio.LOGGER.info("Create compatibility enabled");
        } catch (Exception exception) {
            MediaRadio.LOGGER.error("Failed to initialize Create compatibility", exception);
        }
    }

    private static class RadioMovingInteraction extends MovingInteractionBehaviour {
        @Override
        public boolean handlePlayerInteraction(Player player, InteractionHand interactionHand, BlockPos localPos, AbstractContraptionEntity contraptionEntity) {
            if (player.level().isClientSide) {
                return true;
            }
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return false;
            }

            Map<?, ?> blocks;
            try {
                Object contraption = contraptionEntity.getClass().getMethod("getContraption").invoke(contraptionEntity);
                blocks = (Map<?, ?>) contraption.getClass().getMethod("getBlocks").invoke(contraption);
            } catch (ReflectiveOperationException exception) {
                MediaRadio.LOGGER.error("Failed to read contraption blocks for Create interaction", exception);
                return false;
            }

            StructureTemplate.StructureBlockInfo blockInfo = (StructureTemplate.StructureBlockInfo) blocks.get(localPos);
            if (blockInfo == null) {
                return false;
            }

            CompoundTag nbt = blockInfo.nbt() == null ? new CompoundTag() : blockInfo.nbt().copy();
            String radioId = nbt.getString(RadioBlockEntity.TAG_RADIO_ID);
            if (radioId == null || radioId.isBlank()) {
                radioId = UUID.randomUUID().toString();
                nbt.putString(RadioBlockEntity.TAG_RADIO_ID, radioId);
                try {
                    contraptionEntity.getClass()
                            .getMethod("setBlock", BlockPos.class, StructureTemplate.StructureBlockInfo.class)
                            .invoke(contraptionEntity, localPos, new StructureTemplate.StructureBlockInfo(blockInfo.pos(), blockInfo.state(), nbt));
                } catch (ReflectiveOperationException exception) {
                    MediaRadio.LOGGER.error("Failed to persist contraption radio id", exception);
                    return false;
                }
            }

            ModNetworking.openContraptionRadioScreen(serverPlayer, radioId, contraptionEntity.getId(), localPos);
            return true;
        }
    }

    private static class RadioMovingDisplayBehaviour implements MovementBehaviour {
        @Override
        public void tick(MovementContext context) {
            if (context == null || context.world == null || !context.world.isClientSide || context.localPos == null) {
                return;
            }
            if (context.contraption == null || context.contraption.entity == null) {
                return;
            }
            CompoundTag nbt = resolveMovementNbt(context);
            String radioId = nbt == null ? "" : nbt.getString(RadioBlockEntity.TAG_RADIO_ID);
            if (radioId.isBlank()) {
                return;
            }

            ClientAudioEngine audioEngine = ClientAudioEngine.getInstance();
            ClientAudioEngine.HandheldRenderState runtime = audioEngine.getRenderStateForRadioId(radioId);

            String mediaUrl = pick(runtime == null ? "" : runtime.url(), safe(nbt, RadioBlockEntity.TAG_MEDIA_URL));
            String mediaTitle = pick(runtime == null ? "" : runtime.title(), safe(nbt, RadioBlockEntity.TAG_MEDIA_TITLE));
            String mediaArtist = pick(runtime == null ? "" : runtime.artist(), safe(nbt, RadioBlockEntity.TAG_MEDIA_ARTIST));
            String mediaThumbnail = pick(runtime == null ? "" : runtime.thumbnail(), safe(nbt, RadioBlockEntity.TAG_MEDIA_THUMBNAIL));
            boolean playing = runtime != null ? runtime.playing() : (nbt != null && nbt.getBoolean(RadioBlockEntity.TAG_PLAYING));
            long positionMs = runtime != null ? runtime.positionMs() : playbackFromNbt(nbt);
            float volume = runtime != null ? runtime.volume() : (nbt == null ? 1.0f : nbt.getFloat(RadioBlockEntity.TAG_VOLUME));

            audioEngine.syncExternalContraptionState(
                    radioId,
                    context.contraption.entity.getId(),
                    context.localPos,
                    mediaUrl,
                    mediaTitle,
                    mediaArtist,
                    mediaThumbnail,
                    volume,
                    positionMs,
                    playing
            );
        }

        @Override
        public boolean disableBlockEntityRendering() {
            return true;
        }

        @Override
        public void renderInContraption(MovementContext context, VirtualRenderWorld renderWorld, ContraptionMatrices matrices, MultiBufferSource bufferSource) {
            if (context == null || context.state == null || context.localPos == null) {
                return;
            }

            CompoundTag nbt = resolveMovementNbt(context);
            String radioId = nbt == null ? "" : nbt.getString(RadioBlockEntity.TAG_RADIO_ID);
            if (radioId.isBlank()) {
                return;
            }

            ClientAudioEngine.HandheldRenderState runtime = ClientAudioEngine.getInstance().getRenderStateForRadioId(radioId);
            String mediaUrl = pick(runtime == null ? "" : runtime.url(), safe(nbt, RadioBlockEntity.TAG_MEDIA_URL));
            String mediaTitle = pick(runtime == null ? "" : runtime.title(), safe(nbt, RadioBlockEntity.TAG_MEDIA_TITLE));
            String mediaArtist = pick(runtime == null ? "" : runtime.artist(), safe(nbt, RadioBlockEntity.TAG_MEDIA_ARTIST));
            String mediaThumbnail = pick(runtime == null ? "" : runtime.thumbnail(), safe(nbt, RadioBlockEntity.TAG_MEDIA_THUMBNAIL));
            boolean playing = runtime != null ? runtime.playing() : (nbt != null && nbt.getBoolean(RadioBlockEntity.TAG_PLAYING));
            long positionMs = runtime != null ? runtime.positionMs() : playbackFromNbt(nbt);
            float volume = runtime != null ? runtime.volume() : (nbt == null ? 1.0f : nbt.getFloat(RadioBlockEntity.TAG_VOLUME));

            PoseStack poseStack = matrices.getModelViewProjection();
            poseStack.pushPose();
            poseStack.translate(context.localPos.getX(), context.localPos.getY(), context.localPos.getZ());
            RadioBlockEntityRenderer.renderRadioDisplay(
                    context.state,
                    radioId,
                    mediaUrl,
                    mediaTitle,
                    mediaArtist,
                    mediaThumbnail,
                    playing,
                    positionMs,
                    volume,
                    poseStack,
                    bufferSource,
                    0x00F000F0
            );
            poseStack.popPose();
        }

        private static CompoundTag resolveMovementNbt(MovementContext context) {
            CompoundTag direct = context == null ? null : context.blockEntityData;
            if (direct != null && !safe(direct, RadioBlockEntity.TAG_RADIO_ID).isBlank()) {
                return direct;
            }
            if (context == null || context.localPos == null || context.contraption == null) {
                return direct;
            }
            try {
                Map<?, ?> blocks = (Map<?, ?>) context.contraption.getClass().getMethod("getBlocks").invoke(context.contraption);
                Object blockInfoObj = blocks.get(context.localPos);
                if (!(blockInfoObj instanceof StructureTemplate.StructureBlockInfo blockInfo)) {
                    return direct;
                }
                CompoundTag fallback = blockInfo.nbt();
                if (fallback == null) {
                    return direct;
                }
                if (direct == null || safe(direct, RadioBlockEntity.TAG_RADIO_ID).isBlank()) {
                    return fallback;
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall back to direct movement data when reflection access is unavailable.
            }
            return direct;
        }

        private static long playbackFromNbt(CompoundTag nbt) {
            if (nbt == null) {
                return 0L;
            }
            if (!nbt.getBoolean(RadioBlockEntity.TAG_PLAYING)) {
                return Math.max(0L, nbt.getLong(RadioBlockEntity.TAG_PAUSED_POSITION));
            }
            long startedAt = nbt.getLong(RadioBlockEntity.TAG_STARTED_AT);
            if (startedAt <= 0L) {
                return Math.max(0L, nbt.getLong(RadioBlockEntity.TAG_PAUSED_POSITION));
            }
            return Math.max(0L, System.currentTimeMillis() - startedAt);
        }

        private static String safe(CompoundTag tag, String key) {
            if (tag == null) {
                return "";
            }
            String value = tag.getString(key);
            return value == null ? "" : value;
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }

        private static String pick(String preferred, String fallback) {
            String resolvedPreferred = safe(preferred);
            if (!resolvedPreferred.isBlank()) {
                return resolvedPreferred;
            }
            return safe(fallback);
        }
    }
}
