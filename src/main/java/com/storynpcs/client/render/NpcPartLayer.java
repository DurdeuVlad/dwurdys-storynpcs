package com.storynpcs.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.storynpcs.domain.npc.DisplayProjection;
import com.storynpcs.domain.npc.NpcBodyPart;
import com.storynpcs.domain.npc.NpcCosmeticPart;
import com.storynpcs.entity.StoryNpcEntity;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;

import java.util.EnumMap;
import java.util.Map;

/**
 * MPM-style cosmetic part layer (issue #58). Renders clean-room cube
 * approximations of the nine authored parts anchored to humanoid bones, tinted
 * per-part and animated per the authored {@link NpcCosmeticPart.PartBehavior}.
 * Non-humanoid variants skip the pass — the projection already diagnosed the
 * combination server-side.
 */
public class NpcPartLayer extends RenderLayer<StoryNpcEntity, EntityModel<StoryNpcEntity>> {

    private static final int WHITE = 0xFFFFFFFF;

    /** Baked piece per part; the type index nudges size/offset so each part
     *  family visibly varies without copying target meshes. */
    private final EnumMap<NpcBodyPart, ModelPart> headPieces = new EnumMap<>(NpcBodyPart.class);
    private final EnumMap<NpcBodyPart, ModelPart> bodyPieces = new EnumMap<>(NpcBodyPart.class);

    public NpcPartLayer(RenderLayerParent<StoryNpcEntity, EntityModel<StoryNpcEntity>> parent) {
        super(parent);
        bakePieces();
    }

    private void bakePieces() {
        // Head-anchored parts. All boxes are authored in head space (8x8x8 at
        // -4,-8,-4): negative Y is up, positive Z points behind the face.
        headPieces.put(NpcBodyPart.BEARD, bake(b -> b.addBox(-4f, 0.5f, -4.6f, 8, 4, 1)));
        headPieces.put(NpcBodyPart.EARS, bake(b -> b
                .addBox(-5.5f, -6f, -0.5f, 2, 3, 1)
                .addBox(3.5f, -6f, -0.5f, 2, 3, 1)));
        headPieces.put(NpcBodyPart.HORNS, bake(b -> b
                .addBox(-4.5f, -11f, -0.5f, 1, 3, 1)
                .addBox(3.5f, -11f, -0.5f, 1, 3, 1)));
        headPieces.put(NpcBodyPart.SNOUT, bake(b -> b.addBox(-2f, -3f, -6.5f, 4, 2, 3)));
        headPieces.put(NpcBodyPart.FIN, bake(b -> b.addBox(-0.5f, -11.5f, -1f, 1, 4, 2)));
        headPieces.put(NpcBodyPart.EYES, bake(b -> b
                .addBox(-3.2f, -5.2f, -4.15f, 2, 1, 0)
                .addBox(1.2f, -5.2f, -4.15f, 2, 1, 0)));
        // Body-anchored parts (body box is 8x12x4 at -4,0,-2).
        bodyPieces.put(NpcBodyPart.TAIL, bake(b -> b.addBox(-1f, 10f, 2f, 2, 2, 6)));
        bodyPieces.put(NpcBodyPart.WINGS, bake(b -> b
                .addBox(-10f, 1f, 2.2f, 8, 8, 0)
                .addBox(2f, 1f, 2.2f, 8, 8, 0)));
        bodyPieces.put(NpcBodyPart.SKIRT, bake(b -> b.addBox(-5f, 10f, -2.5f, 10, 7, 5)));
    }

    private static ModelPart bake(java.util.function.Consumer<CubeListBuilder> shape) {
        MeshDefinition mesh = new MeshDefinition();
        CubeListBuilder builder = CubeListBuilder.create().texOffs(0, 0);
        shape.accept(builder);
        mesh.getRoot().addOrReplaceChild("part", builder, PartPose.ZERO);
        return mesh.getRoot().bake(64, 64);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       StoryNpcEntity entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (!(getParentModel() instanceof HumanoidModel<?> humanoid)) return;
        DisplayProjection projection = entity.displayProjection();
        if (projection == null || projection.parts().isEmpty()) return;

        @SuppressWarnings("unchecked")
        HumanoidModel<StoryNpcEntity> model = (HumanoidModel<StoryNpcEntity>) humanoid;
        var consumer = buffer.getBuffer(RenderType.entityCutoutNoCull(getTextureLocation(entity)));
        int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0.0f);

        for (Map.Entry<NpcBodyPart, NpcCosmeticPart> entry : projection.parts().entrySet()) {
            NpcBodyPart part = entry.getKey();
            NpcCosmeticPart spec = entry.getValue();
            ModelPart piece = headPieces.get(part);
            boolean headAnchored = piece != null;
            if (!headAnchored) {
                piece = bodyPieces.get(part);
            }
            if (piece == null) continue;

            poseStack.pushPose();
            ModelPart anchor = headAnchored || spec.behavior() == NpcCosmeticPart.PartBehavior.FOLLOW_HEAD
                    ? model.head : model.body;
            anchor.translateAndRotate(poseStack);
            if (spec.behavior() == NpcCosmeticPart.PartBehavior.ANIMATED) {
                poseStack.translate(0f, (float) Math.sin(ageInTicks * 0.15f) * 0.05f, 0f);
            }
            // Type index nudges scale so variants of a part family differ.
            float typeScale = 1.0f + 0.15f * spec.type();
            poseStack.scale(typeScale, typeScale, typeScale);
            int argb = WHITE | (spec.color() & 0xFFFFFF);
            piece.render(poseStack, consumer, packedLight, overlay, argb);
            poseStack.popPose();
        }
    }
}
