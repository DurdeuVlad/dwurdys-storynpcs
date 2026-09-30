package com.storynpcs.domain.npc;

import com.storynpcs.domain.common.NamespacedId;

/**
 * Resolved, immutable render projection of an {@link NpcDisplay}. The renderer and
 * entity consume only these typed parameters; resolution rules (fallback assets,
 * remote-source classification, hitbox math) live in {@link DisplayProjectionResolver}.
 */
public record DisplayProjection(
        NpcDisplay.SkinSource skinSource,
        NamespacedId skinTexture,
        String skinPlayer,
        String skinUrl,
        NamespacedId cloakTexture,
        NamespacedId glowTexture,
        String name,
        String title,
        NameVisibility nameVisibility,
        int tint,
        float scaleX,
        float scaleY,
        float scaleZ,
        int modelSize,
        String modelType,
        String modelId,
        boolean overlayGlowing,
        boolean showLayers,
        boolean livingAnimation,
        int visibility,
        int bossBarMode,
        NpcDisplay.BossBarColor bossBarColor,
        ProjectedHitbox hitbox) {

    public enum NameVisibility {
        ALWAYS,
        NEVER,
        WHILE_ATTACKING
    }

    /**
     * Projected entity hitbox. {@code solid} is false for statue mode: the entity
     * still projects dimensions (it occupies space) but is never pushable.
     */
    public record ProjectedHitbox(float width, float height, float eyeHeight, boolean solid) {}

    public boolean nameVisible(boolean attacking) {
        return switch (nameVisibility) {
            case ALWAYS -> true;
            case NEVER -> false;
            case WHILE_ATTACKING -> attacking;
        };
    }

    public boolean isRemoteSkin() {
        return skinSource == NpcDisplay.SkinSource.URL && skinUrl != null && !skinUrl.isBlank();
    }

    public boolean isPlayerSkin() {
        return skinSource == NpcDisplay.SkinSource.PLAYER && skinPlayer != null && !skinPlayer.isBlank();
    }
}
