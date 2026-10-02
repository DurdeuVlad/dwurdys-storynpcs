# P5-1 — Dialogue field → surface manifest

Status: `CURRENT` — maps every supported dialogue field to its surfaces; `N/A` is explicit, not absent.

## DialogueGraph

| Field | YAML | Service | Player UI | Editor UI | API | Script | Event | Persistence |
|---|---|---|---|---|---|---|---|---|
| id (namespaced) | ✔ | ✔ registry | ✔ title | ✔ | ✔ | ✔ | open/close/reload | read-only def |
| title | ✔ | ✔ | ✔ header | ✔ | ✔ | ✔ | N/A | read-only def |
| entryNodeId | ✔ | ✔ session start | N/A | ✔ | ✔ | ✔ | open | read-only def |
| nodes | ✔ | ✔ runtime walk | ✔ lines | ✔ | ✔ | ✔ | option-select | read-only def |

## DialogueNode

| Field | YAML | Service | Player UI | Editor UI | API | Script | Event | Persistence |
|---|---|---|---|---|---|---|---|---|
| id | ✔ | ✔ | N/A | ✔ | ✔ | ✔ | option-select (from/to) | read-only def |
| text | ✔ | ✔ | ✔ body | ✔ | ✔ | ✔ | N/A | read-only def |
| speaker | ✔ | ✔ | ✔ name | ✔ | ✔ | ✔ | N/A | read-only def |
| sound | ✔ | ✔ → client | ✔ playback | ✔ | N/A | N/A | N/A | read-only def |
| options (edges) | ✔ | ✔ | ✔ buttons | ✔ | ✔ | ✔ | option-select | read-only def |

## DialogueEdge

| Field | YAML | Service | Player UI | Editor UI | API | Script | Event | Persistence |
|---|---|---|---|---|---|---|---|---|
| text | ✔ | ✔ | ✔ button | ✔ | ✔ | ✔ | N/A | read-only def |
| textKey (localization) | ✔ | ✔ validated | N/A (P5-3 key resolution) | ✔ | ✔ | ✔ | N/A | read-only def |
| targetNodeId | ✔ | ✔ transition | N/A | ✔ | ✔ | ✔ | option-select | read-only def |
| onceOnly | ✔ | ✔ filter | ✔ hidden | ✔ | ✔ | ✔ | N/A | read-only def |
| conditions | ✔ | ✔ eval | ✔ filter | ✔ | ✔ | ✔ | choice-rejected | read-only def |
| actions | ✔ | ✔ execute | N/A | ✔ | ✔ | ✔ | canonical mutation | read-only def |

## DialogueGraph additions (this slice)

| Field | YAML | Service | Player UI | Editor UI | API | Script | Event | Persistence |
|---|---|---|---|---|---|---|---|---|
| titleKey (localization) | ✔ | ✔ validated | N/A (P5-3) | ✔ | ✔ | ✔ | N/A | read-only def |
| availability | ✔ | ✔ gates `startDialogue` | ✔ deny → closed view | ✔ | ✔ | ✔ | open-denied/reload | read-only def |
| nodes[].textKey | ✔ | ✔ validated | N/A (P5-3) | ✔ | ✔ | ✔ | N/A | read-only def |

## Target surface mapping (VERIFIED_TARGET_SOURCE)

Decompiled `noppes.npcs.controllers.data.Dialog` / `DialogOption` / `Availability`
(`CustomNPCs-Unofficial-NeoForge-1.21.1.20251230.jar`).

| Target `Dialog` field | StoryNPCs mapping |
|---|---|
| `id` (int) | `DialogueGraph.id` — namespaced IDs, declared deviation |
| `title` | `title` (+ `titleKey` extension) |
| `text` | per-node `text` — our graph has per-node lines, not one body (richer model) |
| `quest` (DialogQuest link) | edge `START_QUEST`/`COMPLETE_QUEST` actions carry the linkage |
| `category` | **N/A** — no dialogue category grouping exists yet (P5-3 authoring scope) |
| `options` (6-slot map) | `nodes[].options` edges — directed graph, declared deviation (non-goal: option arrays) |
| `availability` | `DialogueGraph.availability` — dialog-level `DialogueCondition` list (this slice) |
| `factionOptions` | edge `ADJUST_FACTION` actions |
| `sound` | per-node `sound` — target has one sound per dialog; ours is per-node (richer) |
| `command` | edge `EXECUTE_COMMAND` actions — no dialog-level open command; edge-scoped only |
| `mail` (PlayerMail) | **N/A** — quest mail exists; dialog-attached mail not implemented |
| `hideNPC` | **N/A** — presentation flag, P3-1 display surface owns NPC hiding |
| `showWheel` | **N/A** — presentation flag (P5-3 editor/UI) |
| `disableEsc` | **N/A** — UI flag (P5-3) |
| `version`/`ModRev` | YAML `schemaVersion` boundary (P2-1) |

| Target `DialogOption` field | StoryNPCs mapping |
|---|---|
| `option`/`title` | edge `text` (+ `textKey` extension) |
| `dialogId` (linked dialog) | edge `targetNodeId` — in-graph node, not cross-dialogue hops (graph model replaces dialog chaining) |
| `optionType` (link/quit/disabled) | transition edges; terminal nodes = quit; `conditions` hide = disabled-with-filter |
| `optionColor` | **N/A** — presentation (P5-3) |
| `command` | edge `EXECUTE_COMMAND` action |
| per-option `isAvailable` | edge `conditions` |

| Target `Availability` field | StoryNPCs mapping |
|---|---|
| `quest1-4Available` + ids | `QUEST_STATUS` conditions (list, unbounded count — richer) |
| `factionId1/2` + stance | `FACTION_STANDING` / `FACTION_POINTS` conditions |
| `daytime` | **N/A** — `DAY_TIME` condition type deferred (condition-registry extension) |
| `scoreboard*Objective/Value/Type` | **N/A** — `SCOREBOARD` condition type deferred |
| `minPlayerLevel` | **N/A** — deferred; `HAS_PERMISSION` covers authority gating today |
| `dialog1-4Available` + ids | **N/A** — no cross-dialogue completion gate type yet |

## Session lifecycle (runtime, not definition)

| Concern | Surface |
|---|---|
| session open/close/timeout | `DialogueOpenEvent`, `DialogueClosedEvent` (PLAYER_EXIT/TIMEOUT/SERVER_CLOSE/GRAPH_END) |
| open denied by availability | `DialogueOpenDeniedEvent` (this slice) |
| choice accept | `DialogueOptionSelectEvent` (carries from/to node + index) |
| choice reject | `DialogueChoiceRejectedEvent` (reason) |
| definition reload | `DialogueReloadedEvent` — emitted by canonical dialogue mutate/replace/delete and `/storynpcs reload`; live sessions on the dialogue are re-evaluated (missing graph/node or failing availability → `SERVER_CLOSE`; survivors rebind to the new instance) with affected/closed counts (this slice) |
| choice identity | `DialogueChoiceProtocol` opaque tokens — see P5-2 |

## Diagnostics

`DialogueGraphValidator` emits: `DIALOGUE_EMPTY_GRAPH`, `DIALOGUE_MISSING_ENTRY`,
`DIALOGUE_UNREACHABLE_NODE`, `DIALOGUE_DANGLING_EDGE`, `DIALOGUE_BAD_LOCALIZATION_KEY`,
`DIALOGUE_BAD_AVAILABILITY_CONDITION` (errors) and `DIALOGUE_CYCLE` (warning —
cycles are legal in directed graphs but visible to authors). Edge-level condition
fields are still lenient (not validated) — documented residual.

## Explicit gaps

- Localization keys are typed/validated but not yet resolved client-side (P5-3 UI scope);
  target itself uses literal text — keys are a StoryNPCs extension.
- `DAY_TIME`/`SCOREBOARD`/`minPlayerLevel` availability kinds from the target are not
  implemented — deferred as condition-registry extensions, tracked above as N/A rows.
- Dialog categories and dialog-attached mail are unimplemented target surfaces.
- Edge-level `conditions`/`actions` payload fields are not deep-validated on load.
