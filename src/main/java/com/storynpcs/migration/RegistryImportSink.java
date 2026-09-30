package com.storynpcs.migration;

import com.storynpcs.creator.template.NpcTemplate;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.dialogue.DialogueGraphSerde;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.faction.FactionSerde;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcDefinitionSerde;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.domain.quest.QuestSerde;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;

/**
 * Production {@link DefinitionImporter.ImportSink}: reads state from the live
 * {@link DefinitionRegistry} and writes through the canonical service save
 * paths (which validate, persist YAML atomically, then register). Rollback is
 * honest — deletes remove the registry binding and the YAML file; restores
 * re-save the snapshot taken before the replace. Snapshots are detached
 * serde copies so rollback restores the pre-import state, not whatever the
 * live object may have become in the meantime.
 */
public final class RegistryImportSink implements DefinitionImporter.ImportSink {

    private final StoryNpcsApplicationService service;
    private final DefinitionRegistry registry;

    public RegistryImportSink(StoryNpcsApplicationService service,
                              DefinitionRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    @Override
    public boolean contains(String family, NamespacedId id) {
        return snapshot(family, id) != null;
    }

    @Override
    public Object snapshot(String family, NamespacedId id) {
        return switch (family) {
            case "npc" -> registry.getNpc(id)
                    .map(n -> NpcDefinitionSerde.fromJson(NpcDefinitionSerde.toJson(n)).orElseThrow(
                            () -> new IllegalStateException("npc snapshot failed to round-trip: " + id)))
                    .orElse(null);
            case "dialogue" -> registry.getDialogue(id)
                    .map(d -> DialogueGraphSerde.fromJson(DialogueGraphSerde.toJson(d)).orElseThrow(
                            () -> new IllegalStateException("dialogue snapshot failed to round-trip: " + id)))
                    .orElse(null);
            case "quest" -> registry.getQuest(id)
                    .map(q -> QuestSerde.fromJson(QuestSerde.toJson(q)).orElseThrow(
                            () -> new IllegalStateException("quest snapshot failed to round-trip: " + id)))
                    .orElse(null);
            case "faction" -> registry.getFaction(id)
                    .map(f -> FactionSerde.fromJson(FactionSerde.toJson(f)).orElseThrow(
                            () -> new IllegalStateException("faction snapshot failed to round-trip: " + id)))
                    .orElse(null);
            default -> null;
        };
    }

    @Override
    public void save(String family, NamespacedId id, Object definition) {
        var result = switch (family) {
            case "npc" -> service.saveNpc((NpcDefinition) definition);
            case "dialogue" -> service.saveDialogue(id, (DialogueGraph) definition);
            case "quest" -> service.saveQuest((Quest) definition);
            case "faction" -> service.saveFaction((Faction) definition);
            default -> throw new IllegalStateException("unknown family: " + family);
        };
        if (result != null && result.hasErrors()) {
            throw new IllegalStateException("canonical save rejected " + family + " " + id
                    + ": " + result.formatReport(3));
        }
    }

    @Override
    public void delete(String family, NamespacedId id) {
        boolean removed = switch (family) {
            case "npc" -> service.deleteNpc(id);
            case "dialogue" -> service.deleteDialogue(id);
            case "quest" -> service.deleteQuest(id);
            case "faction" -> service.deleteFaction(id);
            default -> throw new IllegalStateException("unknown family: " + family);
        };
        if (!removed) {
            throw new IllegalStateException("delete reported no removal for " + family + " " + id);
        }
    }

    @Override
    public void restore(String family, NamespacedId id, Object prior) {
        if (prior == null) {
            delete(family, id);
        } else {
            save(family, id, prior);
        }
    }

    @Override
    public void saveTemplate(NpcTemplate template) {
        // Route through the canonical service like every other family — the
        // import path must not bypass schema validation, coordinated writes,
        // or revision tracking.
        var result = service.saveTemplate(template);
        if (result != null && result.hasErrors()) {
            throw new IllegalStateException("canonical save rejected template "
                    + template.getId() + ": " + result.formatReport(3));
        }
    }

    @Override
    public void deleteTemplate(NamespacedId id) {
        // Canonical deletion: the service removes the durable YAML source first
        // (fail-closed), then the registry binding under the canonical lock.
        var outcome = service.deleteTemplate(id);
        if (!outcome.removed()) {
            throw new IllegalStateException("template delete reported no removal for " + id);
        }
        if (!outcome.dependentSpawners().isEmpty()) {
            System.err.println("[StoryNPCs] template '" + id
                    + "' deleted with dependent spawners now orphaned: " + outcome.dependentSpawners());
        }
    }

    @Override
    public NpcTemplate snapshotTemplate(NamespacedId id) {
        return registry.getTemplate(id).map(RegistryImportSink::copyTemplate).orElse(null);
    }

    private static NpcTemplate copyTemplate(NpcTemplate source) {
        NpcTemplate copy = new NpcTemplate();
        copy.setId(source.getId());
        copy.setSchemaVersion(source.getSchemaVersion());
        copy.setRevision(source.getRevision());
        copy.setDescription(source.getDescription());
        copy.setTags(source.getTags());
        if (source.getDefinition() != null) {
            copy.setDefinition(NpcDefinitionSerde.fromJson(NpcDefinitionSerde.toJson(source.getDefinition()))
                    .orElseThrow(() -> new IllegalStateException(
                            "template snapshot failed to round-trip: " + source.getId())));
        }
        return copy;
    }
}
