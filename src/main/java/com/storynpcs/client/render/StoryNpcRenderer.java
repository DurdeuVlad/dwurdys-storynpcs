package com.storynpcs.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.storynpcs.domain.npc.DisplayProjection;
import com.storynpcs.domain.npc.NpcCosmeticPart;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcDisplay;
import com.storynpcs.domain.npc.NpcEmote;
import com.storynpcs.domain.npc.NpcVariant;
import com.storynpcs.entity.StoryNpcEntity;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.EnumMap;
import java.util.Optional;

/**
 * Per-variant renderer for {@link StoryNpcEntity} (P3-1, issue #58). A single
 * entity type projects nine target-compatible variants, so the model is
 * selected per entity inside {@link #render} from geometry baked out of vanilla
 * layer definitions (clean-room — no target model classes or assets). Humanoid
 * variants use the player model with authored stance/emote poses; generic
 * variants use baked vanilla meshes with no entity-specific animation calls.
 */
public class StoryNpcRenderer extends LivingEntityRenderer<StoryNpcEntity, EntityModel<StoryNpcEntity>> {

    public static final ResourceLocation DEFAULT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");

    private final StoryNpcPlayerModel wideModel;
    private final StoryNpcPlayerModel slimModel;
    private final EnumMap<NpcVariant, EntityModel<StoryNpcEntity>> variantModels;
    private final HumanoidAccessoryLayer accessoryLayer;

    public StoryNpcRenderer(EntityRendererProvider.Context context) {
        super(context, new StoryNpcPlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.wideModel = (StoryNpcPlayerModel) this.model;
        this.slimModel = new StoryNpcPlayerModel(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        this.variantModels = buildVariantModels(context);

        RenderLayerParent<StoryNpcEntity, HumanoidModel<StoryNpcEntity>> humanoidParent =
                new RenderLayerParent<>() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public HumanoidModel<StoryNpcEntity> getModel() {
                        // Only dereferenced while the active model is humanoid —
                        // HumanoidAccessoryLayer guards every call.
                        return (HumanoidModel<StoryNpcEntity>) StoryNpcRenderer.this.getModel();
                    }

                    @Override
                    public ResourceLocation getTextureLocation(StoryNpcEntity entity) {
                        return StoryNpcRenderer.this.getTextureLocation(entity);
                    }
                };
        this.accessoryLayer = new HumanoidAccessoryLayer(this,
                new ItemInHandLayer<>(humanoidParent, context.getItemInHandRenderer()),
                new HumanoidArmorLayer<>(humanoidParent,
                        new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                        new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                        context.getModelManager()));
        // P3-1: cloak/glow overlay passes + MPM cosmetic parts driven by the
        // display projection.
        this.addLayer(new NpcRenderLayer(this));
        this.addLayer(new NpcPartLayer(this));
        this.addLayer(accessoryLayer);
    }

    private static EnumMap<NpcVariant, EntityModel<StoryNpcEntity>> buildVariantModels(
            EntityRendererProvider.Context context) {
        EnumMap<NpcVariant, EntityModel<StoryNpcEntity>> models = new EnumMap<>(NpcVariant.class);
        models.put(NpcVariant.GOLEM, new GenericNpcModel(context.bakeLayer(ModelLayers.IRON_GOLEM)));
        models.put(NpcVariant.DRAGON, new GenericNpcModel(context.bakeLayer(ModelLayers.ENDER_DRAGON)));
        models.put(NpcVariant.SLIME, new GenericNpcModel(context.bakeLayer(ModelLayers.SLIME)));
        models.put(NpcVariant.CRYSTAL, new GenericNpcModel(context.bakeLayer(ModelLayers.END_CRYSTAL)));
        models.put(NpcVariant.PONY, new GenericNpcModel(context.bakeLayer(ModelLayers.HORSE)));
        return models;
    }

    private EntityModel<StoryNpcEntity> selectModel(StoryNpcEntity entity) {
        DisplayProjection projection = entity.displayProjection();
        NpcVariant variant = projection != null ? projection.variant() : NpcVariant.HUMANOID;
        return switch (variant.modelFamily()) {
            case HUMANOID_WIDE, HUMANOID_FLYING -> wideModel;
            case HUMANOID_SLIM -> slimModel;
            case GENERIC -> variantModels.getOrDefault(variant, wideModel);
        };
    }

    @Override
    public void render(StoryNpcEntity entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        this.model = selectModel(entity);
        this.shadowRadius = this.model == wideModel || this.model == slimModel ? 0.5F : 0.7F;
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(StoryNpcEntity entity) {
        return resolveTexture(entity.getDefinition());
    }

    /**
     * Humanoid model that consumes the authored resting stance and active
     * emote (P3-1) inside {@code setupAnim} — {@code LivingEntityRenderer}
     * rewrites the pose fields every frame, so authored overrides are applied
     * after vanilla setup.
     */
    static final class StoryNpcPlayerModel extends PlayerModel<StoryNpcEntity> {
        StoryNpcPlayerModel(net.minecraft.client.model.geom.ModelPart root, boolean slim) {
            super(root, slim);
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
            NpcEmoteAnimator.apply(this, entity.activeEmote(), entity.emoteProgress(), ageInTicks);
        }
    }

    /**
     * Non-humanoid variant model: vanilla mesh geometry baked through the
     * registered layer definitions, animated by the generic limb-swing path.
     * Deliberately calls no variant-specific animation state on the entity —
     * a slime-shape NPC and a dragon-shape NPC animate identically here, which
     * is the honest baseline for the generic family.
     */
    static final class GenericNpcModel extends HierarchicalModel<StoryNpcEntity> {
        private final ModelPart root;

        GenericNpcModel(ModelPart root) {
            this.root = root;
        }

        @Override
        public ModelPart root() {
            return root;
        }

        @Override
        public void setupAnim(StoryNpcEntity entity, float limbSwing, float limbSwingAmount,
                              float ageInTicks, float netHeadYaw, float headPitch) {
            // Generic variants have no authored pose surface yet — the mesh
            // renders statically; animation is a documented follow-up.
        }
    }

    /**
     * Held items and armor render through the vanilla humanoid layers behind a
     * {@link RenderLayerParent} proxy typed to {@link HumanoidModel}. The proxy
     * is only dereferenced after the instance check, so non-humanoid variants
     * simply skip the pass.
     */
    static final class HumanoidAccessoryLayer
            extends RenderLayer<StoryNpcEntity, EntityModel<StoryNpcEntity>> {
        private final ItemInHandLayer<StoryNpcEntity, HumanoidModel<StoryNpcEntity>> itemLayer;
        private final HumanoidArmorLayer<StoryNpcEntity,
                HumanoidModel<StoryNpcEntity>, HumanoidModel<StoryNpcEntity>> armorLayer;

        HumanoidAccessoryLayer(
                RenderLayerParent<StoryNpcEntity, EntityModel<StoryNpcEntity>> parent,
                ItemInHandLayer<StoryNpcEntity, HumanoidModel<StoryNpcEntity>> itemLayer,
                HumanoidArmorLayer<StoryNpcEntity,
                        HumanoidModel<StoryNpcEntity>, HumanoidModel<StoryNpcEntity>> armorLayer) {
            super(parent);
            this.itemLayer = itemLayer;
            this.armorLayer = armorLayer;
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                           StoryNpcEntity entity, float limbSwing, float limbSwingAmount,
                           float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            if (!(getParentModel() instanceof HumanoidModel)) return;
            itemLayer.render(poseStack, buffer, packedLight, entity, limbSwing, limbSwingAmount,
                    partialTick, ageInTicks, netHeadYaw, headPitch);
            armorLayer.render(poseStack, buffer, packedLight, entity, limbSwing, limbSwingAmount,
                    partialTick, ageInTicks, netHeadYaw, headPitch);
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
