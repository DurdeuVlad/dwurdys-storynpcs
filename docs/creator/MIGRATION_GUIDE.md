# Migration guide — CustomNPCs → StoryNPCs

Status: `CURRENT` — states only what is verified by the test suite.

## Accepted inputs

| Input | Accepted? | Notes |
|---|---|---|
| StoryNPCs YAML definitions (schemaVersion 1) | yes | primary authoring format |
| AI patch plans (`storynpcs/patches/*.yaml`) | yes | `/storynpcs author validate|apply <name>` — canonical apply with rollback; see `examples/guard_patchplan.yaml` |
| `scripts:` hook lists on NPC definitions | yes | bounded Rhino scripts (P9-2); capability allowlist enforced |
| Legacy string-array `inventory:` | yes | migrates onto visible drop slots at 100% chance |
| `repeatType: ONCE` | yes | loads as `NORMAL` |
| CustomNPCs binary `.dat`/`saves` data | no | no verified export sample exists — the source kind reports `UNSUPPORTED_NEEDS_EVIDENCE` rather than silently reading it |

## Package import — `/storynpcs import`

Drop a package under `<world>/storynpcs/import/<name>/` with family-keyed
subdirectories (`npcs/`, `dialogues/`, `quests/`, `factions/`, `templates/`),
then run `/storynpcs import <name> [policy] [apply]` (op level 2).

- **Dry-run by default**: `import <name>` and `import <name> <policy>` plan
  and report without a single write; appending `apply` executes for real.
- **Conflict policy is explicit**: `skip` (default) keeps existing
  definitions; `fail` aborts before any write; `replace` overwrites with a
  rollback snapshot; `rename` assigns a free `__importN` id.
- **Field-level report**: every document lists per-field outcomes
  (`DIRECT`/`MIGRATED`/`UNSUPPORTED`/`ENVELOPE`) linked to a `P0-1` evidence
  ref — `UNKNOWN` fields quarantine the whole document rather than dropping
  data silently.
- **Scripts/assets**: referenced `scripts`, textures, skin URLs, and model
  presets surface as `res[...]` rows per document, including quarantined ones.
- **Rollback**: a mid-apply failure deletes what this run added and restores
  pre-replace snapshots; the report prints `rollback=RESTORED` or
  `ROLLBACK_INCOMPLETE`.
- All writes route through the canonical service saves — the same validation,
  revisioning, and atomic persistence as in-game authoring.

## Unsupported / explicit gaps

- CustomNPCs dialog option arrays — StoryNPCs uses directed graphs (`nodes:` + `options:` edges).
- LinkedData class — no equivalent; transform rules cover the use case.
- Patch-plan apply scope is npc/dialogue/quest/faction; other families reject `PATCH_APPLY_SCOPE` (use their canonical commands).

## Repeat modes

Target modes `normal, repeatable, daily, weekly, reset, instant` all supported.
DAILY/WEEKLY use the explicit `RepeatSchedule` policy (UTC, day-start 00:00, Monday week start)
unless the server config overrides.

## Errors you will see

`DIALOGUE_UNREACHABLE_NODE`, `DIALOGUE_DANGLING_EDGE`, `QUEST_MISSING_PREREQUISITE`,
`QUEST_DEPENDENCY_CYCLE`, `RECIPE_BAD_SLOT`, `SCENE_OVER_BUDGET`,
`TEMPLATE_UNKNOWN_FIELD`, `PATCH_STALE_BASE` — each names the file/field it came from.
