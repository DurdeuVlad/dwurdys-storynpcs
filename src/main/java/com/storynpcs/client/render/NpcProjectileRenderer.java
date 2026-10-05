package com.storynpcs.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.storynpcs.entity.NpcProjectileEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ArrowRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.TippableArrowRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Projectile renderer for the authored ranged contract: an authored
 * PROJECTILE equipment item renders as its billboarded item sprite (P3-4),
 * otherwise the default arrow model is used.
 */
public class NpcProjectileRenderer extends ArrowRenderer<NpcProjectileEntity> {

    public NpcProjectileRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(NpcProjectileEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        ItemStack stack = entity.getAuthoredItem();
        if (stack.isEmpty()) {
            super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
            return;
        }
        poseStack.pushPose();
        // Billboard the authored item to the camera, like a thrown item.
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        Minecraft.getInstance().getItemRenderer().renderStatic(
                stack, ItemDisplayContext.GROUND, packedLight, OverlayTexture.NO_OVERLAY,
                poseStack, buffer, entity.level(), entity.getId());
        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(NpcProjectileEntity entity) {
        return TippableArrowRenderer.NORMAL_ARROW_LOCATION;
    }
}
