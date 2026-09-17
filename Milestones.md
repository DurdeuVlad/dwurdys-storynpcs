# Dwurdy's StoryNPCs Milestones & Issue Plan

Document status: active  
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
- **Status**: IN PROGRESS
- **Issues**:
  - **Issue #1**: NeoForge Entity Registration & `StoryNpcEntity` Implementation
    - *Intent*: Implement custom PathfinderMob entity `StoryNpcEntity` backed by `NpcDefinition`, registering attributes, AI goals, and right-click dialogue interaction.
    - *Files*: `src/main/java/com/storynpcs/entity/StoryNpcEntity.java`, `src/main/java/com/storynpcs/entity/StoryNpcRegistry.java`.
    - *Tests*: `StoryNpcEntityTest.java`.
  - **Issue #2**: World Data Lifecycle & Server Hooks
    - *Intent*: Hook server lifecycle events to auto-load YAML definitions and synchronize player progression safely.
    - *Files*: `src/main/java/com/storynpcs/lifecycle/WorldLifecycleHandler.java`.
    - *Tests*: `WorldLifecycleHandlerTest.java`.
- **Evidence**: 100% test pass on entity attributes and world lifecycle hooks.

---

## Milestone M2: Network Protocol & Client Dialogue Screen
- **Status**: PLANNED
- **Issues**:
  - **Issue #3**: Client Dialogue UI Screen & Input Handling
    - *Intent*: Modern Minecraft Screen (`DialogueScreen`) rendering speaker, dialogue text, selectable options, audio cue triggers, keyboard navigation (1-9), and network response dispatch.
    - *Files*: `src/main/java/com/storynpcs/client/gui/DialogueScreen.java`, `src/main/java/com/storynpcs/client/StoryNpcsClient.java`.
    - *Tests*: `DialogueScreenTest.java`.
- **Evidence**: UI layout calculations, option selection, and packet dispatch verified.

---

## Milestone M3: Directed-Graph Dialogue Visual Editor
- **Status**: PLANNED
- **Issues**:
  - **Issue #4**: Graph Visual Layout & Node-Canvas Data Model
    - *Intent*: 2D graph layout model (`DialogueGraphLayout`, `VisualNode`, `VisualEdge`) supporting pan/zoom coordinates, cycle detection, and bidirectional conversion with `DialogueGraph`.
    - *Files*: `src/main/java/com/storynpcs/editor/DialogueGraphLayout.java`, `src/main/java/com/storynpcs/editor/VisualNode.java`, `src/main/java/com/storynpcs/editor/VisualEdge.java`.
    - *Tests*: `DialogueGraphLayoutTest.java`.
  - **Issue #5**: In-Game Graph Editor Screen & CRUD Operations
    - *Intent*: Interactive in-game editor (`DialogueEditorScreen`) supporting visual node/edge addition, linking, deletion, and YAML serialization.
    - *Files*: `src/main/java/com/storynpcs/client/gui/DialogueEditorScreen.java`.
    - *Tests*: `DialogueEditorScreenTest.java`.
- **Evidence**: Node CRUD, edge linking, canvas coordinate transformations, and serialization verified.