package com.storynpcs.client.render;

import com.storynpcs.entity.NpcProjectileEntity;
import net.minecraft.client.renderer.entity.ArrowRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.TippableArrowRenderer;
import net.minecraft.resources.ResourceLocation;

/** Arrow-model renderer for the authored NPC projectile contract. */
public class NpcProjectileRenderer extends ArrowRenderer<NpcProjectileEntity> {

    public NpcProjectileRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(NpcProjectileEntity entity) {
        return TippableArrowRenderer.NORMAL_ARROW_LOCATION;
    }
}
