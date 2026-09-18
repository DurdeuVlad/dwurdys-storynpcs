package com.storynpcs.entity;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcStats;
import com.storynpcs.service.DialogueView;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;

import java.util.Optional;
import java.util.UUID;

public class StoryNpcState {

    private String definitionId;

    public StoryNpcState() {
        this.definitionId = "";
    }

    public StoryNpcState(String definitionId) {
        this.definitionId = definitionId != null ? definitionId : "";
    }

    public String getDefinitionId() {
        return definitionId;
    }

    public void setDefinitionId(String definitionId) {
        this.definitionId = definitionId != null ? definitionId : "";
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