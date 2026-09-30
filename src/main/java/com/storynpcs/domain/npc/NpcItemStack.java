package com.storynpcs.domain.npc;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

/**
 * Server-authoritative item reference inside an NPC inventory. The optional
 * {@code components} payload holds serialized NeoForge data components authored
 * server-side; it is never trusted from client NBT and is bounded in length.
 */
public record NpcItemStack(
        @JsonProperty(required = true) NamespacedId itemId,
        @JsonProperty int count,
        @JsonProperty String components) {

    public static final int MAX_COUNT = 99;
    public static final int MAX_COMPONENTS_LENGTH = 4096;

    public NpcItemStack {
        if (itemId == null) {
            throw new IllegalArgumentException("itemId cannot be null");
        }
        if (count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("count must be between 1 and " + MAX_COUNT);
        }
        components = components == null ? "" : components;
        if (components.length() > MAX_COMPONENTS_LENGTH) {
            throw new IllegalArgumentException(
                    "components exceeds " + MAX_COMPONENTS_LENGTH + " characters");
        }
    }

    public static NpcItemStack of(NamespacedId itemId, int count, String components) {
        return new NpcItemStack(itemId, count, components);
    }

    public static NpcItemStack single(NamespacedId itemId) {
        return new NpcItemStack(itemId, 1, "");
    }
}
