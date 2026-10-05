package com.storynpcs.domain.npc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Target-compatible emote animations (issue #58). The target drives ten
 * {@code Ani*} emote classes through {@code EntityAIAnimation}; StoryNPCs
 * carries the emote as short-lived, server-synced runtime state on the entity
 * — never authored in YAML and never persisted as a stance. Humanoid-family
 * variants apply pose overrides; generic variants ignore the pose channel but
 * still honor the emote lifecycle (duration, interruption, completion events).
 */
public enum NpcEmote {
    /** Target's AniBlank — no emote in progress. */
    NONE,
    AIM,
    BOW,
    CRAWL,
    DANCE,
    HUG,
    NO,
    POINT,
    WAVE,
    YES;

    public static final int EMOTE_COUNT = values().length;

    /** Default emote duration in ticks when the caller does not specify one. */
    public static final int DEFAULT_DURATION_TICKS = 40;

    /** Hard cap on an emote duration — one minute. */
    public static final int MAX_DURATION_TICKS = 1200;

    public static NpcEmote byOrdinal(int ordinal) {
        var values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NONE;
    }

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static NpcEmote fromWire(String value) {
        if (value == null || value.isBlank()) return NONE;
        try {
            return NpcEmote.valueOf(value.trim().toUpperCase(Locale.ROOT)
                    .replace('-', '_').replace(" ", "_"));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "emote must be one of: none, aim, bow, crawl, dance, hug, no, "
                            + "point, wave, yes (got '" + value + "')");
        }
    }
}
