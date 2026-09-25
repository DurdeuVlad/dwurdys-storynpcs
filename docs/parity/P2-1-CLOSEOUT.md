# P2-1 — Versioned YAML definition boundary closeout

Status: `IN-REVIEW`

## Delivered locally

- Added one shared `schemaVersion` envelope boundary for YAML definitions.
- Legacy documents without `schemaVersion` are treated as version `0` and normalized through the version-0-to-version-1 migration boundary before domain binding.
- Current version `1` documents load normally; future versions are rejected before registry mutation with `SCHEMA_VERSION_UNSUPPORTED` and source field line/column diagnostics.
- Invalid version values are rejected with `SCHEMA_VERSION_INVALID` and source field location.
- YAML domain binding is now strict at the authoring loader boundary. Unknown fields produce `SCHEMA_UNKNOWN_FIELD` diagnostics with the offending field name and source location rather than being silently discarded.
- Duplicate mapping keys and multiple YAML documents in one file are rejected before registry registration, preventing last-value-wins and first-document-only corruption.
- Writer output is normalized to `schemaVersion: 1` and remains atomic through the existing `.tmp` then rename write path.
- The dialogue node's derived `terminal` property is explicitly excluded from serialized YAML so strict read/write round trips remain stable.

## Evidence

- `YamlDefinitionLoaderTest`: legacy compatibility across the existing NPC/dialogue/faction/quest fixtures, explicit current version, future-version refusal, malformed-version refusal, unknown-field diagnostics, duplicate-key refusal, multiple-document refusal, and existing malformed/empty diagnostics.
- `YamlDefinitionWriterTest`: versioned writer output, atomic temporary-file cleanup, and writer-to-loader dialogue round trip.
- Focused YAML tests: `BUILD SUCCESSFUL`.
- Full suite: `BUILD SUCCESSFUL`, 274 tests, 0 failures/errors.
- Truth gate: passed. `git diff --check`: passed; only repository line-ending warnings.
- Research basis: Jackson's official YAML backend supports the tree model and general `ObjectMapper` data binding; NeoForge's data-driven resource model reinforces versioned, loader-bound definition data rather than embedding content in code. See [Jackson YAML backend](https://github.com/FasterXML/jackson-dataformats-text/tree/3.x/yaml) and [NeoForge data maps](https://docs.neoforged.net/docs/1.21.3/resources/server/datamaps/).

## Explicit limits

- The current typed loader exposes only NPC, dialogue, faction, and quest top-level families. Role, job, template, tool, and world schemas do not yet have independent loader fixtures or migration chains.
- Version 0 currently preserves the existing field shape; no field-rename migration has been needed yet. Future migrations must add deterministic version steps rather than editing old fixtures in place.
- Existing cross-reference validation remains separate from this envelope boundary and still needs a complete both-sides diagnostic matrix for the full target domain set.
- Independent adversarial review is still required before this high-risk data-integrity issue can move to `DONE-LOCAL`.
