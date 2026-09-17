# Dwurdy's StoryNPCs Milestones & Issue Plan

Document status: completed  
Last reviewed: 2026-09-17  
Owner: Vlad Durdeu  

---

## Milestone M0: Core Domain Engine & YAML-First Framework
- **Status**: DONE (Commit `7721dc2`)
- **Deliverables**:
  - Domain models: NamespacedId, NpcDefinition, DialogueGraph, Faction, Quest, PlayerProgression.
  - Jackson-based YamlDefinitionLoader with line/col error diagnostics.
  - CrossReferenceValidator detecting dangling edges and cyclic quest prerequisites.
  - ProgressionRepository with crash-safe atomic tmp-write and replace.
  - StoryNpcsApplicationService implementing the 15 canonical parity operations.
  - Domain events on EventPublisher.
- **Evidence**: 18 automated unit tests passing 100%.

---

## Milestone M1: NeoForge Server Integration, Entity System & World Lifecycle
- **Status**: DONE (Commits `035e7e8`, `af570f2`)
- **Issues**:
  - **Issue #1**: NeoForge Entity Registration & `StoryNpcEntity` Implementation
    - *Intent*: Implement custom PathfinderMob entity `StoryNpcEntity` backed by `NpcDefinition`, registering attributes, AI goals, and right-click dialogue interaction.
    - *Deliverables*: `StoryNpcEntity.java`, `StoryNpcRegistry.java`, `StoryNpcState.java`.
    - *Tests*: `StoryNpcStateTest.java` (3/3 tests PASS).
  - **Issue #2**: World Data Lifecycle & Server Hooks
    - *Intent*: Hook server lifecycle events to auto-load YAML definitions and synchronize player progression safely.
    - *Deliverables*: `WorldLifecycleHandler.java`, loader folder matching fix in `YamlDefinitionLoader.java`.
    - *Tests*: `WorldLifecycleHandlerTest.java` (3/3 tests PASS).
- **Evidence**: 100% test pass on entity state, attributes, world initialization, and player progression caching/saving.

---

## Milestone M2: Network Protocol & Client Dialogue Screen
- **Status**: DONE (Commit `4a0e911`)
- **Issues**:
  - **Issue #3**: Client Dialogue UI Screen & Input Handling
    - *Intent*: Modern Minecraft Screen (`DialogueScreen`) rendering speaker, dialogue text, selectable options, audio cue triggers, keyboard navigation (1-9), and network response dispatch.
    - *Deliverables*: `DialogueScreen.java`, `DialogueScreenModel.java`, `StoryNpcsClient.java`, network hook wiring.
    - *Tests*: `DialogueScreenModelTest.java` (4/4 tests PASS), `NetworkPayloadsTest.java` (3/3 tests PASS).
- **Evidence**: UI layout calculations, option selection, key code routing (1-9, Enter, Space, Escape), and packet codecs verified.

---

## Milestone M3: Directed-Graph Dialogue Visual Editor
- **Status**: DONE (Commits `43e3ec8`, `fdad09b`)
- **Issues**:
  - **Issue #4**: Graph Visual Layout & Node-Canvas Data Model
    - *Intent*: 2D graph layout model (`DialogueGraphLayout`, `VisualNode`, `VisualEdge`) supporting pan/zoom coordinates, cycle detection, and bidirectional conversion with `DialogueGraph`.
    - *Deliverables*: `DialogueGraphLayout.java`, `VisualNode.java`, `VisualEdge.java`, `GraphEditorState.java`.
    - *Tests*: `DialogueGraphLayoutTest.java` (5/5 tests PASS).
  - **Issue #5**: In-Game Graph Editor Screen & CRUD Operations
    - *Intent*: Interactive in-game editor (`DialogueEditorScreen`, `DialogueEditorScreenModel`) supporting visual node/edge addition, linking, deletion, and `/storynpcs dialogue edit <id>` command.
    - *Deliverables*: `DialogueEditorScreen.java`, `DialogueEditorScreenModel.java`, command registration in `StoryNpcsCommands.java`.
    - *Tests*: `DialogueEditorScreenModelTest.java` (4/4 tests PASS), `StoryNpcsCommandsTest.java` (1/1 test PASS).
- **Evidence**: Node CRUD, edge linking, canvas coordinate transformations, cycle detection, and graph export verified.