package com.storynpcs.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
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
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
        this.addLayer(new HumanoidArmorLayer<>(
                this,
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()
        ));
    }

    @Override
    public ResourceLocation getTextureLocation(StoryNpcEntity entity) {
        return resolveTexture(entity.getDefinition());
    }

    public static ResourceLocation resolveTexture(Optional<NpcDefinition> defOpt) {
        if (defOpt.isPresent()) {
            NpcDisplay display = defOpt.get().getDisplay();
            if (display != null && display.getSkinTexture() != null && !display.getSkinTexture().trim().isEmpty()) {
                try {
                    return ResourceLocation.parse(display.getSkinTexture().trim());
                } catch (Exception ignored) {}
            }
        }
        return DEFAULT_TEXTURE;
    }

    public static Optional<Component> formatNameTag(Optional<NpcDefinition> defOpt) {
        return formatNameTag(defOpt, false);
    }

    public static Optional<Component> formatNameTag(Optional<NpcDefinition> defOpt, boolean attacking) {
        if (defOpt.isEmpty()) {
            return Optional.empty();
        }
        NpcDisplay display = defOpt.get().getDisplay();
        if (display == null || !display.isNameVisible(attacking)) {
            return Optional.empty();
        }

        String name = display.getName() != null ? display.getName() : "StoryNPC";
        String title = display.getTitle();
        if (title != null && !title.trim().isEmpty()) {
            return Optional.of(Component.literal(name + " [" + title.trim() + "]"));
        }
        return Optional.of(Component.literal(name));
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
