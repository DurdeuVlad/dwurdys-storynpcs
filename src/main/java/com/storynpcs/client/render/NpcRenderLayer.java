package com.storynpcs.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.storynpcs.domain.npc.DisplayProjection;
import com.storynpcs.entity.StoryNpcEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;

/**
 * Display-projection layer pass for {@link StoryNpcEntity} (P3-1): renders the
 * authored cloak texture as a slightly-inflated tinted overlay and the authored
 * glow texture as a fullbright additive pass. Both passes come straight from
 * the entity's cached {@link DisplayProjection} — invalid assets already fell
 * back or disabled themselves during resolution, so this layer never parses
 * raw strings and never throws into the render path.
 */
public class NpcRenderLayer extends RenderLayer<StoryNpcEntity, PlayerModel<StoryNpcEntity>> {

    /** Vanilla "jacket" inflation factor for overlay passes. */
    private static final float OVERLAY_INFLATION = 1.0625f;

    public NpcRenderLayer(RenderLayerParent<StoryNpcEntity, PlayerModel<StoryNpcEntity>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       StoryNpcEntity entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        DisplayProjection projection = entity.displayProjection();
        if (projection == null || !projection.showLayers()) return;

        // Tinted cloak overlay: the authored cloak texture applied over the
        // whole humanoid model at jacket inflation. Tint multiplies the pass,
        // matching the target's colored-cloak behavior.
        if (projection.cloakTexture() != null) {
            ResourceLocation texture = ResourceLocation.parse(projection.cloakTexture().toString());
            VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));
            int tintArgb = 0xFF000000 | (projection.tint() & 0xFFFFFF);
            poseStack.pushPose();
            poseStack.scale(OVERLAY_INFLATION, OVERLAY_INFLATION, OVERLAY_INFLATION);
            this.getParentModel().renderToBuffer(poseStack, consumer, packedLight,
                    LivingEntityRenderer.getOverlayCoords(entity, 0.0f), tintArgb);
            poseStack.popPose();
        }

        // Fullbright glow pass: the authored glow texture rendered additively —
        // eyes/damageable details that ignore scene lighting.
        if (projection.glowTexture() != null) {
            ResourceLocation texture = ResourceLocation.parse(projection.glowTexture().toString());
            VertexConsumer consumer = buffer.getBuffer(RenderType.eyes(texture));
            this.getParentModel().renderToBuffer(poseStack, consumer, packedLight,
                    LivingEntityRenderer.getOverlayCoords(entity, 0.0f), 0xFFFFFFFF);
        }
    }
}
