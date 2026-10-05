package com.storynpcs.domain.npc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Target-compatible entity model variants (issue #58). The target ships 9
 * {@code EntityNpc*} / model variants — classic player, Alex, 64x32, golem,
 * flying, dragon, slime, crystal, pony — as separate entity classes. StoryNPCs
 * keeps one {@code StoryNpcEntity} and carries the variant as validated display
 * data; the renderer dispatches to per-family geometry baked from vanilla layer
 * definitions (clean-room — no target models are copied).
 *
 * <p>Base dimensions replace the player base in hitbox projection before the
 * {@code modelSize}/scale factors apply.
 */
public enum NpcVariant {
    /** Classic wide-armed player model — the default. */
    HUMANOID(0.6f, 1.8f, 0.9f, ModelFamily.HUMANOID_WIDE),
    /** Slim-armed player model (Alex layout). */
    ALEX(0.6f, 1.8f, 0.9f, ModelFamily.HUMANOID_SLIM),
    /** Classic 64x32 texture layout player model. */
    CLASSIC_64X32(0.6f, 1.8f, 0.9f, ModelFamily.HUMANOID_WIDE),
    /** Iron-golem-like large biped. */
    GOLEM(1.4f, 2.7f, 0.85f, ModelFamily.GENERIC),
    /** Humanoid model rendered with a flight/hover pose. */
    FLYING(0.6f, 1.8f, 0.9f, ModelFamily.HUMANOID_FLYING),
    /** Dragon-family quadruped; scaled down by modelSize by default. */
    DRAGON(1.6f, 3.0f, 0.8f, ModelFamily.GENERIC),
    /** Slime cube. */
    SLIME(0.52f, 0.52f, 0.6f, ModelFamily.GENERIC),
    /** Floating crystal form. */
    CRYSTAL(1.0f, 1.0f, 0.8f, ModelFamily.GENERIC),
    /** Horse-family quadruped. */
    PONY(1.4f, 1.6f, 0.8f, ModelFamily.GENERIC);

    /** Renderer-side model selection keys; geometry is baked client-side. */
    public enum ModelFamily {
        HUMANOID_WIDE,
        HUMANOID_SLIM,
        HUMANOID_FLYING,
        GENERIC
    }

    private final float baseWidth;
    private final float baseHeight;
    private final float eyeRatio;
    private final ModelFamily modelFamily;

    NpcVariant(float baseWidth, float baseHeight, float eyeRatio, ModelFamily modelFamily) {
        this.baseWidth = baseWidth;
        this.baseHeight = baseHeight;
        this.eyeRatio = eyeRatio;
        this.modelFamily = modelFamily;
    }

    public float baseWidth() { return baseWidth; }
    public float baseHeight() { return baseHeight; }
    public float eyeRatio() { return eyeRatio; }
    public ModelFamily modelFamily() { return modelFamily; }

    /** Humanoid-capable variants support MPM parts and emote poses. */
    public boolean isHumanoid() {
        return modelFamily != ModelFamily.GENERIC;
    }

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static NpcVariant fromWire(String value) {
        if (value == null || value.isBlank()) return HUMANOID;
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_').replace(" ", "_");
        // Accept common aliases from authored content.
        return switch (normalized) {
            case "PLAYER", "CLASSIC", "CLASSIC_PLAYER", "STEVE" -> HUMANOID;
            case "64X32", "CLASSIC64X32" -> CLASSIC_64X32;
            case "ENDER_DRAGON" -> DRAGON;
            case "END_CRYSTAL", "ENDCRYSTAL" -> CRYSTAL;
            case "HORSE" -> PONY;
            case "IRON_GOLEM" -> GOLEM;
            default -> {
                try {
                    yield NpcVariant.valueOf(normalized);
                } catch (IllegalArgumentException unknown) {
                    throw new IllegalArgumentException(
                            "variant must be one of: humanoid, alex, classic_64x32, golem, "
                                    + "flying, dragon, slime, crystal, pony (got '" + value + "')");
                }
            }
        };
    }
}
