# AGENTS.md — Dwurdy's StoryNPCs Development Directives

This repository is the production implementation of **Dwurdy's StoryNPCs** (`storynpcs`).
All agents working in this codebase must adhere strictly to the following principles:

## 1. Architectural Integrity
- **YAML-First Definitions**: Never hardcode NPC or dialogue definitions in Java classes or binary files. Definitions live in YAML with namespaced IDs.
- **Canonical Operation Parity**: Every mutation must be routed through `StoryNpcsApplicationService`. Commands, GUI packets, and APIs are thin adapters; business logic must never live in GUI or packet handlers.
- **Directed-Graph Dialogues**: Dialogue structures must use the graph model (`DialogueGraph`, `DialogueNode`, `DialogueEdge`). Never revert to fixed-length option arrays.
- **Zero Mutable Static Singletons**: Avoid the legacy CustomNPCs pitfall of 250 mutable static singletons. Use dependency injection, service instances, and managed lifecycles.
- **Clean Persistence Separation**: Content definitions are read-only at runtime. Progression state is written to dedicated transactional stores using atomic `.tmp` -> flush -> rename writes.

## 2. Test-First & Evidence Verification
- Every domain model, validator, and service operation must be backed by automated JUnit 5 tests.
- Never declare a feature or milestone complete without executing `./gradlew test` and verifying that all tests pass.
