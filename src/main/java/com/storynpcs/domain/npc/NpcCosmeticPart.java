package com.storynpcs.domain.npc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * One authored cosmetic part attachment: the part it targets, the selected
 * shape index, an RGB tint, and the animation behavior applied to the rendered
 * piece (issue #58). Validation happens on construction — an invalid part can
 * never exist inside an {@link NpcDisplay}.
 */
public final class NpcCosmeticPart {

    /**
     * Target-compatible animation behavior for a rendered part. NONE keeps the
     * piece rigid on its bone; FOLLOW_HEAD pitches with the head; ANIMATED bobs
     * with walk/idle cycles (matching the target's PartBehaviorType vocabulary).
     */
    public enum PartBehavior {
        NONE,
        FOLLOW_HEAD,
        ANIMATED;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        @JsonCreator
        public static PartBehavior fromWire(String value) {
            if (value == null || value.isBlank()) return NONE;
            try {
                return PartBehavior.valueOf(value.trim().toUpperCase(Locale.ROOT)
                        .replace('-', '_').replace(" ", "_"));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException(
                        "part behavior must be none, follow_head, or animated (got '" + value + "')");
            }
        }
    }

    @JsonProperty
    private final NpcBodyPart part;

    @JsonProperty
    private final int type;

    @JsonProperty
    private final int color;

    @JsonProperty
    private final PartBehavior behavior;

    @JsonCreator
    public NpcCosmeticPart(@JsonProperty("part") NpcBodyPart part,
                           @JsonProperty("type") int type,
                           @JsonProperty("color") Integer color,
                           @JsonProperty("behavior") PartBehavior behavior) {
        if (part == null) {
            throw new IllegalArgumentException("cosmetic part requires a 'part' name");
        }
        if (type < 0 || type > part.maxType()) {
            throw new IllegalArgumentException("part " + part.wire() + " type must be between 0 and "
                    + part.maxType() + " (got " + type + ")");
        }
        int rgb = color == null ? 0xFFFFFF : color;
        if (rgb < 0 || rgb > 0xFFFFFF) {
            throw new IllegalArgumentException("part color must be between 0x000000 and 0xFFFFFF");
        }
        this.part = part;
        this.type = type;
        this.color = rgb;
        this.behavior = behavior == null ? PartBehavior.NONE : behavior;
    }

    public NpcBodyPart part() { return part; }
    public int type() { return type; }
    public int color() { return color; }
    public PartBehavior behavior() { return behavior; }
}
