package net.jacobwasbeast.mediaradio.mixin;

import net.jacobwasbeast.mediaradio.registry.ModItems;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
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

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void mediaradio$setupAnim(T livingEntity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo callbackInfo) {
        if (!(livingEntity instanceof Player player)) {
            return;
        }

        boolean mainHandRadio = player.getMainHandItem().is(ModItems.RADIO_ITEM);
        boolean offHandRadio = player.getOffhandItem().is(ModItems.RADIO_ITEM);
        HumanoidArm mainArm = player.getMainArm();

        if (offHandRadio) {
            ModelPart arm = mainArm == HumanoidArm.RIGHT ? leftArm : rightArm;
            float side = mainArm == HumanoidArm.RIGHT ? -1f : 1f;
            arm.xRot = -1.85f + head.xRot * 0.08f;
            arm.yRot = side * 0.65f + head.yRot * 0.25f;
            arm.zRot = side * 0.35f;
        }

        if (mainHandRadio) {
            boolean offhandOccupied = !player.getOffhandItem().isEmpty();
            ModelPart arm = mainArm == HumanoidArm.RIGHT ? rightArm : leftArm;
            float side = mainArm == HumanoidArm.RIGHT ? -1f : 1f;

            if (offhandOccupied) {
                arm.xRot = -0.95f;
                arm.yRot = side * 0.45f;
                arm.zRot = side * 0.08f;
            } else {
                arm.xRot = -1.45f;
                arm.yRot = side * 0.25f;
                arm.zRot = side * 0.22f;
            }
        }
    }
}
