package com.storynpcs.entity;

import com.storynpcs.domain.npc.DisplayProjection;
import net.minecraft.world.entity.EntityDimensions;
import net.neoforged.neoforge.event.entity.EntityEvent;

/**
 * Applies the display hitbox projection to {@link StoryNpcEntity} dimensions via
 * NeoForge's {@code EntityEvent.Size}. Width/height/eye height come from the
 * display contract (model size × authored scales on the vanilla player base);
 * statue mode keeps dimensions and disables pushing in {@link StoryNpcEntity#isPushable()}.
 */
public final class StoryNpcHitboxHandler {

    private StoryNpcHitboxHandler() {}

    public static void onEntitySize(EntityEvent.Size event) {
        if (!(event.getEntity() instanceof StoryNpcEntity npc)) return;
        DisplayProjection.ProjectedHitbox hitbox = npc.projectedHitbox();
        if (hitbox == null) return;
        event.setNewSize(EntityDimensions
                .scalable(hitbox.width(), hitbox.height())
                .withEyeHeight(hitbox.eyeHeight()));
    }
}
