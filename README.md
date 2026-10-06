# Dwurdy's StoryNPCs (`storynpcs`)

> Next-generation Minecraft NPC and narrative orchestration framework for NeoForge 1.21.1+.

Dwurdy's StoryNPCs is a modern, clean-room reimagining of the storytelling capabilities traditionally found in CustomNPCs.

## Key Features

- 📜 **YAML-First Content**: NPCs, dialogues, quests, and factions defined in clean, readable, versioned YAML files.
- 🧭 **Canonical Mutation Path**: Every mutation — commands, GUI payloads, Java API, scripted calls — routes through one application-service boundary with capability checks, per-definition revision guards, and idempotent request ids. See the [issue register](docs/CUSTOMNPCS_PARITY_ISSUE_REGISTER.md).
- 🕸️ **Directed-Graph Dialogue Engine**: True narrative graphs with branching, deliberate cycles, conditional option gating, and side-effect actions — authored in the graph editor or YAML.
- 🛡️ **Separated Progression**: Definitions are immutable at runtime; progression lives in dedicated transactional stores with atomic writes.
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
