# Dwurdy's StoryNPCs — Business & Product Architecture

Document status: active  
Last reviewed: 2026-09-17  
Author / Owner: Vlad Durdeu  

## 1. Executive Summary

**Dwurdy's StoryNPCs** (`storynpcs`) is a next-generation Minecraft NPC and narrative orchestration framework targeting NeoForge 1.21.1+. It delivers the creative richness traditionally sought in legacy mods like CustomNPCs while completely replacing their fragile, coupled, and legacy architecture with:
1. **YAML-first, human-authored definitions**: Clean, versioned, namespaced definition files with schema validation, rich diagnostics, and git-friendly change tracking.
2. **Canonical Application Service Layer (target architecture)**: GUI, CLI commands, Public Java API, and future scripting adapters are intended to use unified application use-case operations. Current cross-surface CustomNPCs parity is `0/15` proven and remains an implementation goal.
3. **Directed-Graph Dialogue Engine**: True visual graph topology supporting cycles, multi-choice branching, dynamic availability predicates, and side-effect actions (freeing creators from legacy 12-slot array limits).
4. **Separation of Definition vs. Progression (partial)**: Immutable content files are separated from player progression (quests, reputation, interaction history) in the intended architecture; durable crash-recovery behavior remains unverified until P2-2/P2-3.
5. **Event-Driven Extensibility**: First-class NeoForge domain events and typed registries for dialogue conditions, actions, and quest objectives.

## 2. Core Pillars & Value Propositions

| Dimension | Legacy CustomNPCs Flaw | StoryNPCs Modern Solution |
|---|---|---|
| **Content Authoring** | Trapped in opaque binary NBT world files or GUI-only state; unmergeable in Git | Human-readable, versioned YAML files in `data/storynpcs/definitions/` |
| **Mutation Parity** | 0/15 cross-surface parity; GUI, Commands, and Scripting have divergent logic | Canonical Application Service executes all mutations through single validation pipeline |
| **Dialogue Engine** | Fixed 12-slot option array, linear branching, no native cycle support | Directed graph supporting arbitrary nodes, edges, cycles, conditions, and actions |
| **Progression State** | State mutated directly into world/NPC NBT; corruption risk on crash | Target design separates content definitions from progression; atomic transactional recovery is not yet certified |
| **Extensibility** | 250 mutable static singletons, unmanaged thread pools, silent catches | Pure dependency injection, NeoForge EventBus lifecycle events, typed plugin registries |

## 3. High-Level Domain Architecture

```text
       ┌────────────────────────┐
       │   YAML Content Files   │
       │ (NPCs, Dialogues, etc) │
       └───────────┬────────────┘
                   │
                   ▼
       ┌────────────────────────┐
       │ Definition Loader &    │
       │ Schema Validator       │
       └───────────┬────────────┘
                   │
                   ▼
┌────────┐  ┌───────────┐  ┌──────────┐  ┌─────────┐
│  GUI   │  │ Commands  │  │ Java API │  │ Scripts │
└───┬────┘  └─────┬─────┘  └────┬─────┘  └───┬─────┘
    │             │             │            │
    └─────────────┴──────┬──────┴────────────┘
                         ▼
       ┌───────────────────────────────────┐
       │ Canonical Application Service     │
       │ (15 Canonical Operations)         │
       └─────────────────┬─────────────────┘
                         │
        ┌────────────────┴────────────────┐
        ▼                                 ▼
┌────────────────────────┐      ┌────────────────────────┐
│  Domain Model Engine   │      │ Progression Store      │
│  - Directed Dialogue   │      │  - Player Quests       │
│  - NPC State           │      │  - Factions & Standing │
│  - Quest Engine        │      │  - Dialogue History    │
└──────────────┬─────────┘      └────────────┬───────────┘
               ▼                             ▼
┌────────────────────────┐      ┌────────────────────────┐
│ Domain Event Publisher │      │ Crash-Safe Persistence │
│ (NeoForge EventBus)    │      │ (Atomic .tmp -> rename)│
└────────────────────────┘      └────────────────────────┘
```
