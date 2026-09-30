package com.storynpcs.api;

import java.util.Optional;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.yaml.DefinitionRegistry;

/**
 * Read-only public extension API facade (P9-1). Mutation operations remain
 * unavailable until the capability-grant contract is implemented.
 */
public final class StoryNpcsApi {

    private final DefinitionRegistry registry;

    public StoryNpcsApi(DefinitionRegistry registry) {
        this.registry = registry;
    }

    /** Negotiate API compatibility before any other call. */
    public ApiVersion.Negotiation negotiate(ApiVersion client) {
        return ApiVersion.negotiate(client);
    }

    /** Immutable view of an NPC definition — detached snapshot. */
    public Optional<NpcView> npc(NamespacedId id) {
        if (registry == null) {
            return Optional.empty();
        }
        Optional<NpcDefinition> def = registry.getNpc(id);
        return def.map(NpcView::of);
    }

    /** Validation surface — the same diagnostics adapters and UIs see. */
    public ValidationResult diagnostics(NamespacedId definitionId) {
        ValidationResult result = new ValidationResult();
        if (registry == null) {
            result.addError("REGISTRY_UNAVAILABLE", "definition registry is not initialized");
            return result;
        }
        if (registry.getNpc(definitionId).isEmpty()) {
            result.addError("DEFINITION_MISSING", "no npc definition: " + definitionId);
        }
        return result;
    }

}
