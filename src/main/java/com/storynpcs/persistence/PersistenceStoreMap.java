package com.storynpcs.persistence;

import java.util.List;

/**
 * Canonical mapping of the 18 target persistence-store categories to StoryNPCs
 * stores. Every category declares its ownership scope and either the concrete
 * store classes that implement it or the owning parity issue it is deferred to.
 */
public final class PersistenceStoreMap {

    /** Lifecycle owner of a store's records. */
    public enum Ownership {
        /** Mutable world-scoped runtime state. */
        WORLD,
        /** Per-player runtime state. */
        PLAYER,
        /** Entity/module-attached state. */
        ENTITY,
        /** Read-only YAML content definitions (world files, definition lifecycle). */
        DEFINITION,
        /** Client-side local state. */
        CLIENT
    }

    /** Mapping maturity: implemented store, intentionally covered by other stores, or deferred. */
    public enum Status { IMPLEMENTED, COVERED, DEFERRED }

    public record Entry(
            String targetSymbol,
            Ownership ownership,
            Status status,
            String store,
            String ownerIssue,
            List<Class<?>> implementedBy) {
        public Entry {
            implementedBy = implementedBy == null ? List.of() : List.copyOf(implementedBy);
        }
    }

    private static Entry e(String symbol, Ownership ownership, Status status,
                           String store, String issue, Class<?>... classes) {
        return new Entry(symbol, ownership, status, store, issue, List.of(classes));
    }

    /** The 18 target persistence stores, keyed by their manifest symbol. */
    private static final List<Entry> ENTRIES = List.of(
            e("global_data", Ownership.WORLD, Status.COVERED,
                    "quest mail via QuestMailStore; global flags still deferred", "P9-4",
                    com.storynpcs.domain.quest.QuestMailStore.class),
            e("factions", Ownership.DEFINITION, Status.IMPLEMENTED,
                    "definitions/factions/*.yaml + ProgressionRepository standings", "P2-1",
                    com.storynpcs.yaml.YamlDefinitionLoader.class,
                    com.storynpcs.yaml.DefinitionRegistry.class,
                    ProgressionRepository.class),
            e("dialogs", Ownership.DEFINITION, Status.IMPLEMENTED,
                    "definitions/dialogues/*.yaml", "P2-1",
                    com.storynpcs.yaml.YamlDefinitionLoader.class,
                    com.storynpcs.yaml.DefinitionRegistry.class),
            e("quests", Ownership.DEFINITION, Status.IMPLEMENTED,
                    "definitions/quests/*.yaml + ProgressionRepository quest state", "P2-1",
                    com.storynpcs.yaml.YamlDefinitionLoader.class,
                    com.storynpcs.yaml.DefinitionRegistry.class,
                    ProgressionRepository.class),
            e("banks", Ownership.PLAYER, Status.IMPLEMENTED,
                    "BankRepository vault records + DurableOperationJournal", "P2-2",
                    BankRepository.class, DurableOperationJournal.class),
            e("recipes", Ownership.DEFINITION, Status.DEFERRED,
                    "recipe/carpentry definitions — pending", "P8-4"),
            e("transport", Ownership.WORLD, Status.COVERED,
                    "definitions/transports/*.yaml loadable; fee charge implemented; per-player unlock persistence deferred", "P6-3",
                    com.storynpcs.yaml.YamlDefinitionLoader.class),
            e("spawns", Ownership.WORLD, Status.IMPLEMENTED,
                    "spawners/*.yaml rules + SpawnerRuntimeStore owned-actor ledger (IndexedRecordStore); natural spawn catalog deferred to P8-5", "P8-1",
                    IndexedRecordStore.class),
            e("player_data", Ownership.PLAYER, Status.IMPLEMENTED,
                    "ProgressionRepository (quests, factions, dialogue, follower)", "P2-2",
                    ProgressionRepository.class),
            e("clones", Ownership.DEFINITION, Status.COVERED,
                    "templates/*.yaml loadable + canonical saves; spawner rules under spawners/*.yaml", "P8-1",
                    com.storynpcs.creator.template.NpcTemplate.class,
                    IndexedRecordStore.class),
            e("scripts", Ownership.DEFINITION, Status.DEFERRED,
                    "script source file storage — pending; bounded ScriptScheduler exists", "P9-2"),
            e("database", Ownership.WORLD, Status.COVERED,
                    "dedicated per-record JSON repositories (relational H2 intentionally not adopted)", "P2-2",
                    DurableJsonStore.class),
            e("linked_npcs", Ownership.DEFINITION, Status.DEFERRED,
                    "linked NPC definitions — pending", "P8-5"),
            e("npc_entity_and_modules", Ownership.ENTITY, Status.IMPLEMENTED,
                    "StoryNpcEntity NBT + ActorStateRepository logical actor state", "P1-2",
                    com.storynpcs.runtime.actor.ActorStateRepository.class),
            e("scripted_item_and_blocks", Ownership.WORLD, Status.IMPLEMENTED,
                    "worldtools/*.yaml defs + WorldToolBindingStore inert hook bindings (IndexedRecordStore); script execution deferred to P9-2", "P8-3",
                    IndexedRecordStore.class),
            e("schematics", Ownership.WORLD, Status.DEFERRED,
                    "schematic/blueprint files — pending world tools", "P8-2"),
            e("config", Ownership.WORLD, Status.DEFERRED,
                    "mod configuration file — pending config parity", "P9-4"),
            e("client_presets", Ownership.CLIENT, Status.DEFERRED,
                    "client-side model/GUI presets — pending", "P8-6")
    );

    private PersistenceStoreMap() {}

    public static List<Entry> all() {
        return ENTRIES;
    }

    public static Entry entry(String targetSymbol) {
        return ENTRIES.stream()
                .filter(e -> e.targetSymbol().equals(targetSymbol))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown persistence store: " + targetSymbol));
    }
}
