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
| CustomNPCs binary `.dat`/`saves` data | no | importer tracked in #91 |

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
