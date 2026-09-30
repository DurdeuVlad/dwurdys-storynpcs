package com.storynpcs.domain.npc;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Deterministically resolves an {@link NpcDisplay} into a {@link DisplayProjection}.
 * Every invalid asset produces a stable fallback plus a diagnostic — never an
 * exception into the render path and never a different result for identical input.
 */
public final class DisplayProjectionResolver {

    /** Fallback skin when the authored source cannot be resolved deterministically. */
    public static final NamespacedId DEFAULT_SKIN =
            NamespacedId.of("minecraft:textures/entity/player/wide/steve.png");

    /** Vanilla player base hitbox in blocks; modelSize 5 maps to scale 1.0. */
    private static final float BASE_WIDTH = 0.6f;
    private static final float BASE_HEIGHT = 1.8f;
    private static final float EYE_RATIO = 0.9f;
    private static final float BASE_MODEL_SIZE = 5.0f;

    private static final Pattern MINECRAFT_USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private DisplayProjectionResolver() {}

    public static DisplayProjection resolve(NpcDisplay display) {
        return resolve(display, ValidationResult.valid()).projection();
    }

    public static Resolution resolve(NpcDisplay display, ValidationResult diagnostics) {
        if (diagnostics == null) diagnostics = ValidationResult.valid();
        if (display == null) {
            diagnostics.addError("DISPLAY_MISSING",
                    "NPC definition has no display block — rendering with the default projection");
            return new Resolution(defaultProjection(), diagnostics);
        }

        NamespacedId skinTexture;
        String skinPlayer = "";
        String skinUrl = "";
        switch (display.getSkinSource()) {
            case PLAYER -> {
                skinPlayer = display.getSkinPlayer().trim();
                if (!MINECRAFT_USERNAME.matcher(skinPlayer).matches()) {
                    diagnostics.addError("SKIN_PLAYER_INVALID",
                            "skinPlayer '" + skinPlayer + "' is not a valid Minecraft username "
                                    + "(3-16 letters, digits, underscores) — falling back to the default skin");
                    skinPlayer = "";
                    skinTexture = DEFAULT_SKIN;
                } else {
                    // Live profile resolution is client-runtime; the projection carries
                    // the validated identity plus a deterministic placeholder.
                    skinTexture = DEFAULT_SKIN;
                }
            }
            case URL -> {
                skinUrl = display.getSkinUrl().trim();
                if (!isValidAssetUrl(skinUrl)) {
                    diagnostics.addError("SKIN_URL_INVALID",
                            "skinUrl '" + skinUrl + "' is not a well-formed http(s) URL "
                                    + "— falling back to the default skin");
                    skinUrl = "";
                    skinTexture = DEFAULT_SKIN;
                } else {
                    skinTexture = DEFAULT_SKIN;
                }
            }
            default -> skinTexture = resolveTextureId(display.getSkinTexture(), DEFAULT_SKIN,
                    "SKIN_TEXTURE_INVALID", diagnostics);
        }

        NamespacedId cloak = optionalTextureId(display.getCloakTexture(), "CLOAK_TEXTURE_INVALID", diagnostics);
        NamespacedId glow = optionalTextureId(display.getGlowTexture(), "GLOW_TEXTURE_INVALID", diagnostics);

        DisplayProjection.NameVisibility nameVisibility;
        if (!display.isShowName() || display.getShowNameMode() == 1) {
            nameVisibility = DisplayProjection.NameVisibility.NEVER;
        } else if (display.getShowNameMode() == 2) {
            nameVisibility = DisplayProjection.NameVisibility.WHILE_ATTACKING;
        } else {
            nameVisibility = DisplayProjection.NameVisibility.ALWAYS;
        }

        float sizeScale = display.getModelSize() / BASE_MODEL_SIZE;
        float width = BASE_WIDTH * display.getScaleX() * sizeScale;
        float height = BASE_HEIGHT * display.getScaleY() * sizeScale;
        boolean solid = display.getHitboxState() != 1;
        DisplayProjection.ProjectedHitbox hitbox = new DisplayProjection.ProjectedHitbox(
                width, height, height * EYE_RATIO, solid);

        return new Resolution(new DisplayProjection(
                display.getSkinSource(), skinTexture, skinPlayer, skinUrl,
                cloak, glow,
                display.getName() == null ? "StoryNPC" : display.getName(),
                display.getTitle() == null ? "" : display.getTitle(),
                nameVisibility, display.getTint(),
                display.getScaleX(), display.getScaleY(), display.getScaleZ(),
                display.getModelSize(),
                display.getModelType() == null ? "humanoid" : display.getModelType(),
                display.getModelId(),
                display.isOverlayGlowing(), display.isShowLayers(), display.hasLivingAnimation(),
                display.getVisibility(), display.getBossBarMode(), display.getBossBarColor(),
                hitbox), diagnostics);
    }

    /** Stable content fingerprint used by {@link DisplayProjectionCache} invalidation. */
    public static String fingerprint(NpcDisplay display) {
        if (display == null) return "<null>";
        return String.join("", String.valueOf(display.getName()), String.valueOf(display.getTitle()),
                String.valueOf(display.getSkinTexture()),
                String.valueOf(display.getSkinSource()), String.valueOf(display.getSkinUrl()),
                String.valueOf(display.getSkinPlayer()),
                String.valueOf(display.getCloakTexture()), String.valueOf(display.getGlowTexture()),
                String.valueOf(display.isOverlayGlowing()), String.valueOf(display.isShowLayers()),
                String.valueOf(display.getVisibility()), String.valueOf(display.getModelType()),
                String.valueOf(display.getModelId()),
                String.valueOf(display.getModelSize()),
                Float.toString(display.getScaleX()), Float.toString(display.getScaleY()),
                Float.toString(display.getScaleZ()),
                String.valueOf(display.isShowName()), String.valueOf(display.getShowNameMode()),
                String.valueOf(display.getTint()), String.valueOf(display.hasLivingAnimation()),
                String.valueOf(display.getHitboxState()), String.valueOf(display.getBossBarMode()),
                String.valueOf(display.getBossBarColor()));
    }

    private static NamespacedId resolveTextureId(String raw, NamespacedId fallback,
                                                 String code, ValidationResult diagnostics) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            diagnostics.addError("SKIN_TEXTURE_EMPTY",
                    "skinTexture is blank — falling back to the default skin");
            return fallback;
        }
        try {
            return NamespacedId.of(value);
        } catch (IllegalArgumentException invalid) {
            diagnostics.addError(code,
                    "'" + value + "' is not a valid namespaced asset id — falling back to the default");
            return fallback;
        }
    }

    private static NamespacedId optionalTextureId(String raw, String code, ValidationResult diagnostics) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return null;
        try {
            return NamespacedId.of(value);
        } catch (IllegalArgumentException invalid) {
            diagnostics.addError(code,
                    "'" + value + "' is not a valid namespaced asset id — the layer is disabled");
            return null;
        }
    }

    private static boolean isValidAssetUrl(String url) {
        if (url == null || url.isBlank() || url.length() > 512 || url.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        String scheme = uri.getScheme();
        return uri.getHost() != null
                && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
    }

    private static DisplayProjection defaultProjection() {
        return new DisplayProjection(
                NpcDisplay.SkinSource.TEXTURE, DEFAULT_SKIN, "", "",
                null, null, "StoryNPC", "", DisplayProjection.NameVisibility.ALWAYS,
                0xFFFFFF, 1.0f, 1.0f, 1.0f, 5, "humanoid", "",
                true, true, true, 0, 0, NpcDisplay.BossBarColor.PINK,
                new DisplayProjection.ProjectedHitbox(
                        BASE_WIDTH, BASE_HEIGHT, BASE_HEIGHT * EYE_RATIO, true));
    }

    /** Resolution result carrying the projection plus collected diagnostics. */
    public record Resolution(DisplayProjection projection, ValidationResult diagnostics) {}
}
