package com.storynpcs.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.InventoryMenu;

import java.util.HashMap;
import java.util.Map;

/**
 * Renders a {@link com.storynpcs.entity.FakeLivingEntity} as its configured
 * display entity type (#148): a cached, client-only puppet instance of the
 * display type is constructed once per type and drawn at the puppet's
 * position/rotation. Entities without a display type render nothing.
 */
public class FakeLivingRenderer extends EntityRenderer<com.storynpcs.entity.FakeLivingEntity> {

    private final Map<EntityType<?>, Entity> puppetCache = new HashMap<>();
    private net.minecraft.world.level.Level cacheLevel;

    public FakeLivingRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(com.storynpcs.entity.FakeLivingEntity fake, float entityYaw,
                       float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        var displayType = fake.displayEntityType();
        var level = fake.level();
        if (displayType.isEmpty() || level == null) {
            return;
        }
        // Cached puppets hold their level — rebuild after dimension/world
        // changes instead of rendering stale-level entities.
        if (level != cacheLevel) {
            puppetCache.clear();
            cacheLevel = level;
        }
        Entity puppet = puppetCache.computeIfAbsent(displayType.get(), t -> t.create(level));
        if (puppet == null) {
            return;
        }
        puppet.setPos(fake.getX(), fake.getY(), fake.getZ());
        puppet.setYRot(fake.getYRot());
        puppet.setXRot(fake.getXRot());
        puppet.yRotO = fake.yRotO;
        puppet.xRotO = fake.xRotO;
        puppet.tickCount = fake.tickCount;
        if (puppet instanceof net.minecraft.world.entity.LivingEntity living) {
            living.yBodyRot = fake.yBodyRot;
            living.yBodyRotO = fake.yBodyRotO;
            living.yHeadRot = fake.getYHeadRot();
            living.yHeadRotO = fake.yHeadRotO;
        }
        Minecraft.getInstance().getEntityRenderDispatcher()
                .render(puppet, 0, 0, 0, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(com.storynpcs.entity.FakeLivingEntity entity) {
        // The delegate renderer resolves the real texture; this satisfies the
        // abstract contract for tooling that asks us directly.
        return InventoryMenu.BLOCK_ATLAS;
    }
}
