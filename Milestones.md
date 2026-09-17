# Dwurdy's StoryNPCs Milestones

Document status: active  
Last reviewed: 2026-09-17  
Owner: Vlad Durdeu  

## M0: Core Domain Engine & YAML-First Framework (Phase 2 MVP)
- **Status**: in progress
- **Outcome**: Complete domain model, Jackson-based YAML definition loader with schema validation and cross-reference diagnostics, canonical application service implementing the 15 parity operations, directed-graph dialogue engine with cycle support, and progression persistence.
- **Acceptance evidence**: Automated test suite passing 100% of unit and integration tests across schemas, cross-references, dialogue graph traversal, and application service parity.

## M1: NeoForge Server Integration & Commands
- **Status**: planned
- **Outcome**: NeoForge server lifecycle hooks, entity registry for custom StoryNPC entities, command trees (`/storynpcs npc|dialogue|quest|faction|reload`), and atomic world data saving.
- **Acceptance evidence**: GameTest suites running in headless server environment.

## M2: Network Protocol & Client Session
- **Status**: planned
- **Outcome**: Minimal network DTOs for client dialogue presentation, option selection, and state preview without transferring authoritative logic.
- **Acceptance evidence**: Bidirectional packet tests on local test server.

## M3: Directed-Graph Dialogue Visual Editor
- **Status**: planned
- **Outcome**: In-game node-graph editor supporting visual node/edge CRUD, search, pan/zoom, cycle inspection, and YAML serialization.
- **Acceptance evidence**: In-game UI verification.
