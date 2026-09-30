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
| targetNodeId | ✔ | ✔ transition | N/A | ✔ | ✔ | ✔ | option-select | read-only def |
| onceOnly | ✔ | ✔ filter | ✔ hidden | ✔ | ✔ | ✔ | N/A | read-only def |
| conditions | ✔ | ✔ eval | ✔ filter | ✔ | ✔ | ✔ | choice-rejected | read-only def |
| actions | ✔ | ✔ execute | N/A | ✔ | ✔ | ✔ | canonical mutation | read-only def |

## Session lifecycle (runtime, not definition)

| Concern | Surface |
|---|---|
| session open/close/timeout | `DialogueOpenEvent`, `DialogueClosedEvent` (PLAYER_EXIT/TIMEOUT/SERVER_CLOSE/GRAPH_END) |
| choice accept | `DialogueOptionSelectEvent` (carries from/to node + index) |
| choice reject | `DialogueChoiceRejectedEvent` (reason) |
| definition reload | `DialogueReloadedEvent` (affected/closed sessions) |
| choice identity | `DialogueChoiceProtocol` opaque tokens — see P5-2 |

## Diagnostics

`DialogueGraphValidator` emits: `DIALOGUE_EMPTY_GRAPH`, `DIALOGUE_MISSING_ENTRY`,
`DIALOGUE_UNREACHABLE_NODE`, `DIALOGUE_DANGLING_EDGE` (errors) and `DIALOGUE_CYCLE` (warning —
cycles are legal in directed graphs but visible to authors).

## Explicit gaps

- Localization IDs are not yet carried on node/edge text (fields not yet defined).
- Condition/action registries accept the existing typed sets; target-only condition
  kinds remain unverified until fixture coverage exists.
