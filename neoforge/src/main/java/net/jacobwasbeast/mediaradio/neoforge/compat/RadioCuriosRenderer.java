package net.jacobwasbeast.mediaradio.neoforge.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.ICurioRenderer;

import java.util.Locale;

public class RadioCuriosRenderer implements ICurioRenderer {

    @Override
    public <T extends LivingEntity, M extends EntityModel<T>> void render(
            ItemStack stack,
            SlotContext slotContext,
            PoseStack poseStack,
            RenderLayerParent<T, M> renderLayerParent,
            MultiBufferSource bufferSource,
            int packedLight,
            float limbSwing,
            float limbSwingAmount,
            float partialTick,
            float ageInTicks,
            float netHeadYaw,
            float headPitch
    ) {
        if (!slotContext.visible() || slotContext.cosmetic() || stack.isEmpty()) {
            return;
        }
        LivingEntity entity = slotContext.entity();
        if (entity == null || !(renderLayerParent.getModel() instanceof HumanoidModel<?> humanoidModel)) {
            return;
        }

        String slotIdentifier = slotContext.identifier() == null ? "" : slotContext.identifier().toLowerCase(Locale.ROOT);
        boolean isBack = slotIdentifier.contains("back");
        boolean isBelt = slotIdentifier.contains("belt") || slotIdentifier.contains("hip") || slotIdentifier.contains("waist");
        if (!isBack && !isBelt) {
            return;
        }

        poseStack.pushPose();
        ICurioRenderer.followBodyRotations(entity, (HumanoidModel<LivingEntity>) (HumanoidModel<?>) humanoidModel);

        Minecraft minecraft = Minecraft.getInstance();
        ItemRenderer itemRenderer = minecraft.getItemRenderer();

        if (isBack) {
            // Anchor to the torso so the radio tracks body yaw/pitch.
            humanoidModel.body.translateAndRotate(poseStack);
            // Flip Y-down model space into Y-up world-oriented space.
            poseStack.mulPose(Axis.XP.rotationDegrees(180f));
            // Upper back: mid-torso height, sitting just behind the body shell.
            poseStack.translate(0f, -0.2f, -0.18f);
            poseStack.scale(0.55f, 0.55f, 0.55f);
        } else {
            // Anchor to the right leg so the belt-mounted radio swings with the stride.
            humanoidModel.rightLeg.translateAndRotate(poseStack);
            // Flip Y-down leg-model space into Y-up world-oriented space.
            poseStack.mulPose(Axis.XP.rotationDegrees(180f));
            // Outer right hip (entity-right = -X in this frame), just below the leg pivot.
            poseStack.translate(-0.2f, -0.15f, 0f);
            poseStack.mulPose(Axis.YP.rotationDegrees(90f));
            poseStack.scale(0.45f, 0.45f, 0.45f);
        }

        itemRenderer.renderStatic(
                stack,
                ItemDisplayContext.FIXED,
                packedLight,
                OverlayTexture.NO_OVERLAY,
                poseStack,
                bufferSource,
                entity.level(),
                entity.getId()
        );

        poseStack.popPose();
    }
}
