# P2-1 — Versioned YAML definition boundary closeout

Status: `DONE-LOCAL` — the versioning boundary is complete and enforced for every loadable family. Per-family version fixtures for domains that do not exist yet (role, job, companion, tool, world) are inherited by their owning issues (P6/P8): those directories fail closed with `SCHEMA_FAMILY_UNSUPPORTED` — a family cannot load without the version boundary, so no silent unmigrated loading is possible. All acceptance criteria met for the schema surface that exists: version envelopes, migration chain, strict-field diagnostics with locations, forward-version refusal, cross-reference diagnostics naming both sides, `requiredCount` ceiling, versioned bundled starters, Java-free quickstart, and malformed-resource refusal.

## Delivered locally

- Added one shared `schemaVersion` envelope boundary for YAML definitions.
- Legacy documents without `schemaVersion` are treated as version `0` and normalized through the version-0-to-version-1 migration boundary before domain binding.
- Current version `1` documents load normally; future versions are rejected before registry mutation with `SCHEMA_VERSION_UNSUPPORTED` and source field line/column diagnostics.
- Invalid version values are rejected with `SCHEMA_VERSION_INVALID` and source field location.
- YAML domain binding is now strict at the authoring loader boundary. Unknown fields produce `SCHEMA_UNKNOWN_FIELD` diagnostics with the offending field name and source location rather than being silently discarded.
- Duplicate mapping keys and multiple YAML documents in one file are rejected before registry registration, preventing last-value-wins and first-document-only corruption.
- Writer output is normalized to `schemaVersion: 1` and remains atomic through the existing `.tmp` then rename write path.
- The dialogue node's derived `terminal` property is explicitly excluded from serialized YAML so strict read/write round trips remain stable.
- All six bundled starter resources now declare `schemaVersion: 1` (captain NPC/dialogue, faction, quest, plus the two new quickstart resources).
- Bundled `quickstart_dialogue.yaml` + `quickstart_demo.yaml` NPC ship in `data/storynpcs/definitions/` and are seeded through `STARTER_DEFINITIONS`; `/storynpcs quickstart` now only resolves loaded definition IDs and spawns/reuses the entity projection — it never constructs or persists definition objects from Java. When no talkable starter/demo definition is loaded, the command fails with a pointer to loader diagnostics.
- `YamlDefinitionLoader.loadDirectory` now emits `SCHEMA_FAMILY_UNSUPPORTED` for recognized-but-not-yet-loadable family directories (`role(s)/`, `job(s)/`, `tool(s)/`, `world(s)/`, `companion(s)/`, `trade(s)/`, `bank(s)/`, `follower(s)/`, `scene(s)/`, `linked_npc(s)/`) at **any depth below the definitions root** instead of silently binding them to the NPC domain; nested `roles/deep/x.yaml` is rejected the same as `roles/x.yaml`. `templates/` and `transports/` are fully loadable families, not reserved.
- Quest objective `requiredCount` is validated against `QuestProgressState.MAX_OBJECTIVE_COUNT` (100,000) at the cross-reference boundary.
- Cross-reference diagnostics name both sides of each broken reference (referring definition + missing target id).

## Evidence

- `YamlDefinitionLoaderTest`: legacy compatibility across the existing NPC/dialogue/faction/quest fixtures, explicit current version, future-version refusal, malformed-version refusal, unknown-field diagnostics, duplicate-key refusal, multiple-document refusal, malformed/empty diagnostics, trader/banker role sections, unsupported-family refusal without registration, and clean load of all bundled versioned starter resources.
- `YamlDefinitionWriterTest`: versioned writer output, atomic temporary-file cleanup, and writer-to-loader dialogue round trip.
- `QuickstartLogicTest`: resolve order unchanged, bundled quickstart resources are versioned/namespaced/loadable and resolve the demo NPC through the real loader, and a source-level guard asserts the quickstart command constructs and persists no definition objects.
- Focused YAML/command tests: `BUILD SUCCESSFUL`.
- Full suite after review remediation: `BUILD SUCCESSFUL`, 999 tests, 0 failures/errors.
- Truth gate: passed. `git diff --check`: passed; only repository line-ending warnings.
- Research basis: Jackson's official YAML backend supports the tree model and general `ObjectMapper` data binding; NeoForge's data-driven resource model reinforces versioned, loader-bound definition data rather than embedding content in code. See [Jackson YAML backend](https://github.com/FasterXML/jackson-dataformats-text/tree/3.x/yaml) and [NeoForge data maps](https://docs.neoforged.net/docs/1.21.3/resources/server/datamaps/).

## Explicit limits

- Top-level job, companion, tool, and world domains do not exist yet — their family directories fail closed with `SCHEMA_FAMILY_UNSUPPORTED` until the owning issues (P6/P8) land the domain models and loaders. Templates and transports already have loadable families.
- Strict inventory diagnostics: `NpcInventory` binding no longer silently drops content — unknown `inventory:` keys, unknown equipment slots, malformed stacks, non-0..100 `chancePercent`, and invalid `lootMode` values fail the document with field-path diagnostics.
- `DefinitionImporter` template probing routes through the shared strict loader path (`YamlDefinitionLoader.loadTemplate`), so imported templates get the version envelope, strict-field binding, missing-id/definition checks, and duplicate detection like every other family.
- Starter seeding is copy-when-empty only: worlds with existing YAML never receive new bundled files; a world lacking the demo definitions gets an honest quickstart failure instead of scaffolded content.
- Version 0 currently preserves the existing field shape; no field-rename migration has been needed yet. Future migrations must add deterministic version steps rather than editing old fixtures in place.
