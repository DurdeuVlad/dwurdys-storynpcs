package com.storynpcs.entity;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcStats;
import com.storynpcs.domain.npc.TacticalStance;
import com.storynpcs.service.DialogueView;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;

import java.util.Optional;
import java.util.UUID;

public class StoryNpcState {

    private String definitionId;
    /** Durable logical actor identity; entity UUIDs are only runtime projections. */
    private String actorId;

    /** VULN-55: Per-entity tactical stance override — takes precedence over the shared NpcDefinition.ai.tacticalStance. */
    private TacticalStance tacticalStanceOverride = null;

    public StoryNpcState() {
        this.definitionId = "";
        this.actorId = "";
    }

    public StoryNpcState(String definitionId) {
        this.definitionId = definitionId != null ? definitionId : "";
        this.actorId = this.definitionId;
    }

    public String getDefinitionId() {
        return definitionId;
    }

    public void setDefinitionId(String definitionId) {
        this.definitionId = definitionId != null ? definitionId : "";
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId != null ? actorId : "";
    }

    /** Returns the per-entity stance override, or null if no override has been set. */
    public TacticalStance getTacticalStanceOverride() {
        return tacticalStanceOverride;
    }

    /** Sets a per-entity tactical stance override. Pass null to clear and fall back to the definition's stance. */
    public void setTacticalStanceOverride(TacticalStance tacticalStanceOverride) {
        this.tacticalStanceOverride = tacticalStanceOverride;
    }

    /**
     * Returns the effective tactical stance for this entity.
     * Prefers the per-entity override over the shared definition stance (VULN-55 fix).
     */
    public TacticalStance getEffectiveTacticalStance(DefinitionRegistry registry) {
        if (tacticalStanceOverride != null) {
            return tacticalStanceOverride;
        }
        return resolveDefinition(registry)
                .map(com.storynpcs.domain.npc.NpcDefinition::getAi)
                .filter(ai -> ai != null)
                .map(ai -> ai.getTacticalStance())
                .orElse(null);
    }

    public Optional<NpcDefinition> resolveDefinition(DefinitionRegistry registry) {
        if (definitionId == null || definitionId.trim().isEmpty() || registry == null) {
            return Optional.empty();
        }
        try {
            NamespacedId id = NamespacedId.of(definitionId);
            return registry.getNpc(id);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<String> getDisplayName(DefinitionRegistry registry) {
        return resolveDefinition(registry)
                .map(NpcDefinition::getDisplay)
                .map(d -> d.getName());
    }

    public Optional<NpcStats> getStats(DefinitionRegistry registry) {
        return resolveDefinition(registry)
                .map(NpcDefinition::getStats);
    }

    public Optional<NamespacedId> getDialogueId(DefinitionRegistry registry) {
        return resolveDefinition(registry)
                .map(NpcDefinition::getDialogueId);
    }

    public Optional<NamespacedId> getFactionId(DefinitionRegistry registry) {
        return resolveDefinition(registry)
                .map(NpcDefinition::getFactionId);
    }

    public boolean canInteract(DefinitionRegistry registry) {
        return getDialogueId(registry).isPresent();
    }

    public Optional<DialogueView> interact(UUID playerUuid, StoryNpcsApplicationService service, DefinitionRegistry registry) {
        return interact(playerUuid, service, registry, null, null, 0.0, 0.0, 0.0);
    }

    public Optional<DialogueView> interact(UUID playerUuid, StoryNpcsApplicationService service, DefinitionRegistry registry,
                                           UUID npcUuid, String dimensionId, double x, double y, double z) {
        if (playerUuid == null || service == null || registry == null) {
            return Optional.empty();
        }
        Optional<NamespacedId> dialogueIdOpt = getDialogueId(registry);
        if (dialogueIdOpt.isEmpty()) {
            return Optional.empty();
        }
        try {
            DialogueView view = service.startDialogue(playerUuid, dialogueIdOpt.get(), npcUuid, dimensionId, x, y, z);
            return Optional.ofNullable(view);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
