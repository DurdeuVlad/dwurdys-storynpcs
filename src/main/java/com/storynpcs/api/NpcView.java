package com.storynpcs.api;

import java.util.Map;
import java.util.Optional;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;

/**
 * Immutable public view of an NPC definition (P9-1). API consumers never hold
 * mutable domain objects — views are detached snapshots.
 */
public record NpcView(
        NamespacedId id,
        NamespacedId factionId,
        NamespacedId dialogueId,
        String displayName,
        String modelType,
        int hitboxState,
        Map<String, String> metadata) {

    public NpcView {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Detach a view from a definition — always a snapshot, never a live reference. */
    public static NpcView of(NpcDefinition def) {
        String name = def.getDisplay() == null ? "" : def.getDisplay().getName();
        String model = def.getDisplay() == null ? "" : def.getDisplay().getModelType();
        int hitbox = def.getDisplay() == null ? 0 : def.getDisplay().getHitboxState();
        return new NpcView(def.getId(), def.getFactionId(), def.getDialogueId(),
                name == null ? "" : name, model == null ? "" : model, hitbox, Map.of());
    }
}
