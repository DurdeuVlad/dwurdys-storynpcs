# Dwurdy's StoryNPCs (`storynpcs`)

> Next-generation Minecraft NPC and narrative orchestration framework for NeoForge 1.21.1+.

Dwurdy's StoryNPCs is a modern, clean-room reimagining of the storytelling capabilities traditionally found in CustomNPCs.

## Key Features

- 📜 **YAML-First Content**: NPCs, dialogues, quests, and factions defined in clean, readable, versioned YAML files.
- 🧭 **Canonical Mutation Path (in progress)**: The GUI, commands, Java API, and future script adapters are being converged on one application-service boundary; cross-surface CustomNPCs parity is not yet claimed. See [P0-2 and the issue register](docs/CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p0-2--remove-unsupported-completion-claims).
- 🕸️ **Directed-Graph Dialogue Engine**: True narrative graphs with branching, deliberate cycles, conditional option gating, and side-effect actions.
- 🛡️ **Separated Progression (partial)**: Definitions are intended to remain immutable and progression is separated from content, but crash-recovery evidence is still unverified. See [P2-2/P2-3](docs/CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p2-2--implement-durable-worldplayer-economy-stores).
- 🔌 **Event-Driven Extensibility**: Built-in NeoForge domain events for other mods to intercept and extend quests, dialogues, and NPC behavior.

## Building and Testing

```bash
# Run automated unit and integration tests
./gradlew test

# Build production mod jar
./gradlew build
```

## Current truth gate

StoryNPCs is a partial implementation and CustomNPCs parity is an active roadmap, not a delivered feature claim. The exact target inventory, 42-issue implementation sequence, evidence states, and unsupported claims are tracked in the [CustomNPCs parity roadmap](docs/CUSTOMNPCS_PARITY_ROADMAP.md). Static target inventory and passing unit tests do not prove runtime parity.

## Architecture

See [Business.md](Business.md) for domain architecture, [Decision.md](Decision.md) for architectural decision records, and [Milestones.md](Milestones.md) for roadmap progress.
