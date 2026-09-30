package com.storynpcs.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.storynpcs.domain.npc.DisplayProjection;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcDisplay;
import com.storynpcs.entity.StoryNpcEntity;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public class StoryNpcRenderer extends MobRenderer<StoryNpcEntity, PlayerModel<StoryNpcEntity>> {

    public static final ResourceLocation DEFAULT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");

    public StoryNpcRenderer(EntityRendererProvider.Context context) {
        super(context, new StoryNpcPlayerModel(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
        this.addLayer(new HumanoidArmorLayer<>(
                this,
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()
        ));
        // P3-1: cloak/glow overlay passes driven by the display projection
        this.addLayer(new NpcRenderLayer(this));
    }

    @Override
    public ResourceLocation getTextureLocation(StoryNpcEntity entity) {
        return resolveTexture(entity.getDefinition());
    }

    /**
     * Humanoid model that consumes the authored resting stance (P3-1) inside
     * {@code setupAnim} — {@code LivingEntityRenderer} rewrites the pose
     * fields every frame, so stance flags are OR-ed in after vanilla setup
     * but before pose evaluation. LYING rides the synced entity pose
     * ({@code Pose.SLEEPING}), which the renderer rotates natively.
     */
    static final class StoryNpcPlayerModel extends PlayerModel<StoryNpcEntity> {
        StoryNpcPlayerModel(net.minecraft.client.model.geom.ModelPart root) {
            super(root, false);
        }

        @Override
        public void setupAnim(StoryNpcEntity entity, float limbSwing, float limbSwingAmount,
                              float ageInTicks, float netHeadYaw, float headPitch) {
            var stance = entity.animationStance();
            this.riding = this.riding
                    || stance == com.storynpcs.domain.npc.NpcAi.AnimationStance.SITTING;
            this.crouching = this.crouching
                    || stance == com.storynpcs.domain.npc.NpcAi.AnimationStance.SNEAKING;
            switch (stance) {
                case DANCING -> {
                    this.leftArmPose = this.rightArmPose =
                            net.minecraft.client.model.HumanoidModel.ArmPose.THROW_SPEAR;
                }
                case AIMING -> {
                    this.leftArmPose = this.rightArmPose =
                            net.minecraft.client.model.HumanoidModel.ArmPose.BOW_AND_ARROW;
                }
                default -> { }
            }
            super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        }
    }

    public static ResourceLocation resolveTexture(Optional<NpcDefinition> defOpt) {
        if (defOpt.isPresent()) {
            NpcDisplay display = defOpt.get().getDisplay();
            if (display != null) {
                try {
                    return ResourceLocation.parse(com.storynpcs.domain.npc.DisplayProjectionResolver
                            .resolve(display).skinTexture().toString());
                } catch (Exception ignored) {}
            }
        }
        return DEFAULT_TEXTURE;
    }

    /**
     * Model scale from the projection: authored scaleX/Y/Z times the modelSize
     * factor (modelSize 5 = scale 1.0, matching the hitbox projection).
     */
    @Override
    protected void scale(StoryNpcEntity entity, PoseStack poseStack, float partialTick) {
        DisplayProjection projection = entity.displayProjection();
        if (projection == null) return;
        float sizeScale = projection.modelSize() / 5.0f;
        poseStack.scale(projection.scaleX() * sizeScale,
                projection.scaleY() * sizeScale,
                projection.scaleZ() * sizeScale);
    }

    public static Optional<Component> formatNameTag(Optional<NpcDefinition> defOpt) {
        return formatNameTag(defOpt, false);
    }

    /**
     * Nameplate projection: visibility comes from the projection's resolved
     * name mode, and the authored tint colors the text.
     */
    public static Optional<Component> formatNameTag(Optional<NpcDefinition> defOpt, boolean attacking) {
        if (defOpt.isEmpty()) {
            return Optional.empty();
        }
        NpcDisplay display = defOpt.get().getDisplay();
        if (display == null) return Optional.empty();
        DisplayProjection projection;
        try {
            projection = com.storynpcs.domain.npc.DisplayProjectionResolver.resolve(display);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (!projection.nameVisible(attacking)) {
            return Optional.empty();
        }

        String name = projection.name() != null ? projection.name() : "StoryNPC";
        String title = projection.title();
        String text = title != null && !title.trim().isEmpty()
                ? name + " [" + title.trim() + "]" : name;
        int rgb = projection.tint() & 0xFFFFFF;
        Component component = Component.literal(text);
        return rgb != 0xFFFFFF
                ? Optional.of(component.copy().withStyle(s -> s.withColor(rgb)))
                : Optional.of(component);
    }

    @Override
    protected void renderNameTag(
            StoryNpcEntity entity,
            Component displayName,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            float partialTick
    ) {
        Optional<Component> formattedName = formatNameTag(entity.getDefinition(), entity.isAggressive());
        if (formattedName.isPresent()) {
            super.renderNameTag(entity, formattedName.get(), poseStack, buffer, packedLight, partialTick);
        }
    }
}
