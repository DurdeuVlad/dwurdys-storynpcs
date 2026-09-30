# Quickstart — author a multi-role NPC in 5 minutes

Status: `CURRENT` — every snippet below matches a file in `examples/` that CI
parses through the production YAML loader (`CreatorDocsExamplesTest`).

## 1. The definition files

Authoring is YAML-first. Each definition is one YAML document with a
`schemaVersion: 1` envelope and a namespaced `id`. Drop files into the world's
`storynpcs/definitions/<family>/` folder:

| Family folder | Document |
|---|---|
| `npcs/` | `NpcDefinition` — display, stats, ai, inventory, roles |
| `dialogues/` | `DialogueGraph` — nodes + option edges |
| `quests/` | `Quest` — objectives, rewards, repeat policy |
| `factions/` | `Faction` — standings and relationships |

## 2. A multi-role NPC

`examples/merchant_guard.yaml` is a complete working definition: a gate guard
who also runs a trader market. It wires together:

- `display` — name, title, model, skin source.
- `stats` — health, attack damage, melee timing, XP reward.
- `ai` — `tacticalStance: GUARD`, `defendAllies`, movement policy.
- `inventory` — equipped weapon plus a drop table (`lootMode: NORMAL`).
- `trader` — `marketName`, `restockIntervalTicks`, and `listings` with up to
  two price items (`priceItemId` + `secondaryPriceItemId`), `maxUses`, `page`.
- `factionId` + `dialogueId` — cross-references into the other families.

One NPC = one file. No Java required.

## 3. Its dialogue

`examples/guard_dialogue.yaml` shows the directed-graph format:

- `entryNodeId` picks the first node; `nodes:` is a map keyed by node id.
- Each node's `options:` are edges with `text` + `targetNodeId`.
- Edges may carry `conditions` (e.g. `FACTION_POINTS >= 100` gates a branch)
  and `actions` (`START_QUEST`, `ADJUST_FACTION`, `CLOSE_DIALOGUE`, ...).
- Cycles are legal (loops produce validation warnings, not errors); dangling
  `targetNodeId` and unreachable nodes are hard errors.

## 4. Its quest

`examples/shopkeeper_quest.yaml` is a `repeatType: DAILY` quest with a
`COLLECT_ITEM` + `KILL_ENTITY` objective mix and `EXPERIENCE` / `ITEM` /
`FACTION_POINTS` rewards. Repeat modes: `normal`, `repeatable`, `daily`,
`weekly`, `reset`, `instant` (`ONCE` loads as `NORMAL`).

## 5. Load & verify

Reload picks up changed files; invalid files are rejected with
file/line/field diagnostics (e.g. `YAML_MAPPING_ERROR`,
`SCHEMA_FAMILY_UNSUPPORTED`, `DIALOGUE_DANGLING_EDGE`) and never partially
applied. Legacy `inventory:` string arrays still load — see
`MIGRATION_GUIDE.md` for accepted inputs and known gaps.

## Evidence & traceability

Parity status per issue lives in `docs/parity/` (`*-PROGRESS.md`,
`*-CLOSEOUT.md`) — cite those, not this page, for claim verification.
