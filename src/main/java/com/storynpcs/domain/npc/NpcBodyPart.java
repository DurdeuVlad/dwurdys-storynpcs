package com.storynpcs.domain.npc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Target-compatible cosmetic body parts (MPM-style, issue #58). The target's
 * {@code client.parts} surface exposes nine authored parts; each part carries a
 * type index selecting one of its rendered shapes, a tint color, and an
 * animation behavior. Geometry is clean-room cube-based — no target meshes.
 */
public enum NpcBodyPart {
    BEARD(4),
    EARS(4),
    HORNS(4),
    SNOUT(4),
    TAIL(4),
    WINGS(4),
    FIN(2),
    SKIRT(4),
    EYES(4);

    /** Maximum value for the part's {@code type} selector (0..maxType inclusive). */
    private final int maxType;

    NpcBodyPart(int maxType) {
        this.maxType = maxType;
    }

    public int maxType() { return maxType; }

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static NpcBodyPart fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("part name must not be blank");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_').replace(" ", "_");
        try {
            return NpcBodyPart.valueOf(normalized);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "unknown body part '" + value + "' — expected one of: "
                            + "beard, ears, horns, snout, tail, wings, fin, skirt, eyes");
        }
    }
}
