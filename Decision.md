# Architecture Decision Records (ADRs) — Dwurdy's StoryNPCs

## ADR-001: YAML as Authoring Definition Format
- **Context**: Legacy CustomNPCs serializes NPC definitions into binary NBT world chunks and complex server-side data files. This makes version control, collaborative editing, and automated validation impossible.
- **Decision**: All authorable content (NPCs, Dialogues, Quests, Factions, Roles, Jobs) will be defined in versioned YAML files with namespaced identifiers (e.g. `storynpcs:blacksmith_dialogue`).
- **Consequences**:
  - Content can be edited in text editors, reviewed in PRs, and validated in CI.
  - Requires a schema validation engine that reports human-readable diagnostics with file path, line, and column numbers.
  - Definitions are strictly read-only at runtime.

## ADR-002: Canonical Application Service Layer for Surface Parity
- **Context**: Phase 1 technical archaeology revealed 0/15 cross-surface parity in CustomNPCs; GUI packets, server commands, public API methods, and script wrappers all duplicated business logic, had disparate authorization checks, and diverged in side effects.
- **Decision**: All mutations must route through a single `StoryNpcsApplicationService` defining 15 canonical operations. GUI packets, command executors, API methods, and script bindings become thin adapters that translate inputs and call the canonical operation.
- **Consequences**:
  - Guarantees 100% functional parity across all entry surfaces.
  - Prevents authorization bypass and validation drift.
  - Enables end-to-end integration testing against the application layer without requiring a graphical client.

## ADR-003: Directed-Graph Dialogue Architecture
- **Context**: CustomNPCs restricted dialogue options to a fixed 12-slot array, severely limiting complex branching narratives, loops, and conditional dialogues.
- **Decision**: Implement a directed-graph model where Dialogues consist of arbitrary `DialogueNode` instances connected by `DialogueEdge` options.
- **Consequences**:
  - Supports arbitrary branching, deliberate cycles (e.g. returning to a hub topic), and dynamic condition gating.
  - The server authoritatively evaluates available edges and advances nodes upon player selection.
  - Future UI can render and edit dialogues as node-graphs rather than flat slot lists.

## ADR-004: Strict Separation between Content Definitions and Progression State
- **Context**: Storing live player progress (completed quests, faction standing, NPC health) directly in the same structures as definitions risks content corruption and breaks data-pack immutability.
- **Decision**: Definition files remain completely immutable at runtime. Player progression (quest stages, faction scores, dialogue visit history) is stored in a dedicated player progression repository using atomic `.tmp` -> flush -> rename writes.
- **Consequences**:
  - Zero risk of user actions corrupting authored content.
  - Crash-safe progression storage.
  - Simplifies world resets and pack updates.

## ADR-005: Event-Driven Extensibility
- **Context**: Third-party mod developers need to hook into NPC interactions, dialogue transitions, and quest completions without fragile bytecode manipulation.
- **Decision**: Expose clean, strongly-typed NeoForge events on the standard event bus (`StoryNpcInteractEvent`, `DialogueOptionSelectEvent`, `QuestCompleteEvent`, `FactionReputationChangeEvent`).
- **Consequences**:
  - Other mods can cleanly intercept, cancel, or react to storytelling events.
  - Safe, backward-compatible API boundary.
