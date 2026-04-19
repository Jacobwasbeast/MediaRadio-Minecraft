package net.jacobwasbeast.mediaradio.mixin;

import net.jacobwasbeast.mediaradio.item.RadioItem;
import net.jacobwasbeast.mediaradio.registry.ModItems;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidModel.class)
public class HumanoidModelMixin<T extends LivingEntity> {

    @Shadow
    public ModelPart rightArm;

    @Shadow
    public ModelPart leftArm;

    @Shadow
    public ModelPart head;

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", remap = false, at = @At("TAIL"))
    private void mediaradio$setupAnim(T livingEntity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo callbackInfo) {
        if (!(livingEntity instanceof Player player)) {
            return;
        }

        ItemStack mainStack = player.getMainHandItem();
        ItemStack offStack = player.getOffhandItem();
        boolean mainHandRadio = mainStack.is(ModItems.RADIO_ITEM);
        boolean offHandRadio = offStack.is(ModItems.RADIO_ITEM);
        boolean mainHandPlaceMode = mainHandRadio && RadioItem.isPlaceMode(mainStack);
        boolean offHandPlaceMode = offHandRadio && RadioItem.isPlaceMode(offStack);
        HumanoidArm mainArm = player.getMainArm();

        if (offHandRadio) {
            ModelPart arm = mainArm == HumanoidArm.RIGHT ? leftArm : rightArm;
            // The offhand arm is opposite the main arm, so its outward side flips.
            float side = mainArm == HumanoidArm.RIGHT ? 1f : -1f;
            if (offHandPlaceMode) {
                // Lower carry pose in place mode.
                arm.xRot = -0.95f + head.xRot * 0.10f;
                arm.yRot = side * 0.35f + head.yRot * 0.10f;
                arm.zRot = side * 0.06f;
            } else {
                // Offhand hold near ear.
                arm.xRot = -1.55f + head.xRot * 0.10f;
                arm.yRot = side * 0.55f + head.yRot * 0.25f;
                arm.zRot = side * 0.16f;
            }
        }

        if (mainHandRadio) {
            boolean offhandOccupied = !player.getOffhandItem().isEmpty();
            ModelPart arm = mainArm == HumanoidArm.RIGHT ? rightArm : leftArm;
            float side = mainArm == HumanoidArm.RIGHT ? -1f : 1f;

            if (mainHandPlaceMode) {
                // Lower carry pose in place mode.
                arm.xRot = -0.95f + head.xRot * 0.10f;
                arm.yRot = side * 0.15f + head.yRot * 0.10f;
                arm.zRot = side * 0.05f;
            } else if (offhandOccupied) {
                // Main-hand radio when offhand is busy: closer to chest.
                arm.xRot = -1.10f + head.xRot * 0.08f;
                arm.yRot = side * 0.26f + head.yRot * 0.12f;
                arm.zRot = side * 0.03f;
            } else {
                // Main-hand free pose near face.
                arm.xRot = -1.45f + head.xRot * 0.12f;
                arm.yRot = side * 0.38f + head.yRot * 0.18f;
                arm.zRot = side * 0.10f;
            }
        }
    }
}
