package com.storynpcs.domain.persistence;

import java.util.List;

/**
 * The full target persistence-store category inventory (issue #56 — P2-2
 * durable world/player/economy stores), evidence-sourced from
 * {@code docs/parity/target-surface-manifest.json}'s {@code persistence_stores}
 * surface (exactly 18 categories, confirmed via a direct query against that
 * manifest — the same evidence-sourcing method used for #73's JobType, #85's
 * CommandParityCatalog, and #87's optional-integrations closeout). This directly
 * satisfies P2-2's own named gap in docs/parity/P2-2-CLOSEOUT.md: "the complete
 * target inventory of 18 persistence categories is not yet mapped to concrete
 * StoryNPCs stores."
 *
 * <p><b>Scope boundary:</b> this is a read-only, evidence-backed snapshot of
 * current mapping status. It does NOT implement any of the missing stores
 * (recipes, spawns, scripts, linked NPCs, scripted items/blocks,
 * config, client presets, global data) — those remain open, each with its own
 * tracking issue where one exists, cited per entry below. The schematics
 * store landed with #149's schematic pipeline.
 */
public final class PersistenceStoreCatalog {

    public static final int TARGET_STORE_COUNT = 18;

    private static final List<PersistenceStoreEntry> ENTRIES = List.of(
            new PersistenceStoreEntry("target.persistence_stores.0001", "global_data", "GlobalDataController",
                    PersistenceStoreStatus.UNMAPPED, null, "No server-wide global data blob or equivalent GlobalDataController analog exists in this codebase yet."),
            new PersistenceStoreEntry("target.persistence_stores.0002", "factions", "FactionController",
                    PersistenceStoreStatus.PARTIALLY_MAPPED, "Faction reputation: PlayerProgression (ProgressionRepository, DurableJsonStore-backed)", "Player-scoped faction reputation is durably persisted via ProgressionRepository (versioned envelope, atomic write, backup rotation, corruption quarantine). Faction DEFINITIONS themselves are authored content, persisted as YAML files (AGENTS.md: 'YAML-first definitions'), not through the crash-safe DurableJsonStore policy this issue is about -- that split is an intentional architectural choice, not a gap, but it means this store is only partially covered by P2-2's own crash/recovery guarantees."),
            new PersistenceStoreEntry("target.persistence_stores.0003", "dialogs", "DialogController",
                    PersistenceStoreStatus.UNMAPPED, null, "Dialog DEFINITIONS are authored YAML content (not P2-2's crash-safe store concern). No per-player dialog-read/unread state is persisted at all yet -- confirmed by issue #85's command-parity audit, which found no dialog-read-marker command or storage exists (target.commands.0029/0030 are UNVERIFIED there for the same reason)."),
            new PersistenceStoreEntry("target.persistence_stores.0004", "quests", "QuestController",
                    PersistenceStoreStatus.PARTIALLY_MAPPED, "Quest progression: PlayerProgression (ProgressionRepository, DurableJsonStore-backed)", "Player-scoped quest progression (active/completed state, objective counts, repeat timestamps) is durably persisted via ProgressionRepository. Quest DEFINITIONS are authored YAML content, same split and same rationale as faction reputation above."),
            new PersistenceStoreEntry("target.persistence_stores.0005", "banks", "BankController",
                    PersistenceStoreStatus.MAPPED, "BankRepository (DurableJsonStore-backed)", null),
            new PersistenceStoreEntry("target.persistence_stores.0006", "recipes", "RecipeController",
                    PersistenceStoreStatus.MAPPED, "recipes/*.yaml definition files (YamlDefinitionWriter via DefinitionWriteCoordinator) + DefinitionRegistry",
                    null),
            new PersistenceStoreEntry("target.persistence_stores.0007", "transport", "TransportController",
                    PersistenceStoreStatus.PARTIALLY_MAPPED, "Per-player unlock state: PlayerProgression.unlockedTransportLocations (ProgressionRepository, DurableJsonStore-backed)", "Per-player transport-location unlock state is durably persisted via ProgressionRepository. Transport location DEFINITIONS (destinations, coordinates, fees) are authored YAML content per issue #72, not run through the crash-safe DurableJsonStore policy -- same definitions-vs-progression split as factions and quests above."),
            new PersistenceStoreEntry("target.persistence_stores.0008", "spawns", "SpawnController",
                    PersistenceStoreStatus.PARTIALLY_MAPPED, "SpawnerRuntimeStore (IndexedRecordStore-backed owned-actor ledger under world/storynpcs/spawner_state/)", "Template-spawner rules are authored YAML (definitions/spawners/*.yaml) and the runtime ledger (owned entity UUIDs, last spawn tick, death accounting, instantiated definition id) is durably persisted per change. Natural-spawn rules persist as authored YAML (definitions/naturalspawns/*.yaml, P8-5); their per-dimension quota counts live tagged actors — no separate ledger needed."),
            new PersistenceStoreEntry("target.persistence_stores.0009", "player_data", "PlayerData / PlayerDataController",
                    PersistenceStoreStatus.MAPPED, "ProgressionRepository (DurableJsonStore-backed; mailbox, faction/quest progression, transport unlocks, and all other per-player state)", null),
            new PersistenceStoreEntry("target.persistence_stores.0010", "clones", "ServerCloneController",
                    PersistenceStoreStatus.MAPPED, "templates/*.yaml definition files (YamlDefinitionWriter via DefinitionWriteCoordinator) + DefinitionRegistry TemplateLibrary", "Named templates persist as schemaVersioned YAML definitions and mutate through canonical template.replace/delete operations; spawner rules persist under definitions/spawners/*.yaml the same way."),
            new PersistenceStoreEntry("target.persistence_stores.0011", "scripts", "ScriptController",
                    PersistenceStoreStatus.UNMAPPED, null, "No scripting engine or script storage exists in this codebase yet (tracked by the still-open issue #84, P9-2)."),
            new PersistenceStoreEntry("target.persistence_stores.0012", "database", "DatabaseController",
                    PersistenceStoreStatus.INTENTIONAL_DEVIATION, null, "The target uses an embedded H2 SQL database (DatabaseController) for arbitrary structured queries. AGENTS.md mandates 'Clean Persistence Separation' via versioned file-backed stores (DurableJsonStore) rather than an embedded relational database; introducing a SQL dependency would contradict that directive and this codebase's whole persistence architecture. No StoryNPCs feature currently needs ad hoc relational queries over save data, so no equivalent is planned."),
            new PersistenceStoreEntry("target.persistence_stores.0013", "linked_npcs", "LinkedNpcController",
                    PersistenceStoreStatus.MAPPED, "LinkedNpcStore (IndexedRecordStore-backed actor→target links under world/storynpcs/linked_npcs/)", null),
            new PersistenceStoreEntry("target.persistence_stores.0014", "npc_entity_and_modules", "EntityNPCInterface and attached data modules",
                    PersistenceStoreStatus.MAPPED, "StoryNpcEntity.addAdditionalSaveData/readAdditionalSaveData (standard Minecraft entity NBT persistence)", null),
            new PersistenceStoreEntry("target.persistence_stores.0015", "scripted_item_and_blocks", "ItemScriptedWrapper / TileScripted / TileScriptedDoor",
                    PersistenceStoreStatus.PARTIALLY_MAPPED, "WorldToolBindingStore (IndexedRecordStore-backed activation bindings under world/storynpcs/world_tool_bindings/)", "Activated world-tool positions and their inert typed hook bindings (ScriptedHookBinding payloads) persist durably via IndexedRecordStore — atomic writes, per-position records. Script EXECUTION of those bindings remains deferred to P9-2 (#84): the world-tool layer stores validated inert data and never runs user code."),
            new PersistenceStoreEntry("target.persistence_stores.0016", "schematics", "SchematicController and schematic readers",
                    PersistenceStoreStatus.PARTIALLY_MAPPED, "SchematicStore + SchematicReader", "Issue #149: Sponge .schem v1-v3 + legacy .schematic reader, bundled data/storynpcs/schematics/ assets (read-only) + config/storynpcs/schematics/ creator files, bounded SchematicBuildService. All 28 bundled target assets ship in the mod jar (ported under the target's CC BY-NC license with ATTRIBUTION.txt)."),
            new PersistenceStoreEntry("target.persistence_stores.0017", "config", "ConfigLoader / CustomNpcs",
                    PersistenceStoreStatus.UNMAPPED, null, "No persistent server-configuration store exists yet. Issue #85's command-parity audit found the equivalent /noppes/config command family (chunkloaders, debug, freezenpcs, icemelts, leavesdecay, scripting, vineinflateth) entirely UNVERIFIED -- there is no config value to persist because none of these settings are implemented yet."),
            new PersistenceStoreEntry("target.persistence_stores.0018", "client_presets", "PresetController",
                    PersistenceStoreStatus.PARTIALLY_MAPPED, "ModelPreset YAML definitions under definitions/presets/ (P8-6)", "Server-authoritative preset schema is mapped; client-side PresetController rendering/caching is client-only state in the target and stays deferred to the P10-1 screen wave.")
    );

    private PersistenceStoreCatalog() {}

    public static List<PersistenceStoreEntry> all() {
        return ENTRIES;
    }
}
