# Dwurdy's StoryNPCs (`storynpcs`)

> Next-generation Minecraft NPC and narrative orchestration framework for NeoForge 1.21.1+.

Dwurdy's StoryNPCs is a modern, clean-room reimagining of the storytelling capabilities traditionally found in CustomNPCs.

## Key Features

- 📜 **YAML-First Content**: NPCs, dialogues, quests, and factions defined in clean, readable, versioned YAML files.
- 🔄 **100% Mutation Parity**: The In-Game GUI, CLI commands, Java API, and scripts all route through a unified Canonical Application Service Layer.
- 🕸️ **Directed-Graph Dialogue Engine**: True narrative graphs with branching, deliberate cycles, conditional option gating, and side-effect actions.
- 🛡️ **Crash-Safe Progression Store**: Authored definitions remain immutable; player progression and world state are journaled separately with atomic file replacement.
- 🔌 **Event-Driven Extensibility**: Built-in NeoForge domain events for other mods to intercept and extend quests, dialogues, and NPC behavior.

## Building and Testing

```bash
# Run automated unit and integration tests
./gradlew test

# Build production mod jar
./gradlew build
```

## Architecture

See [Business.md](Business.md) for domain architecture, [Decision.md](Decision.md) for architectural decision records, and [Milestones.md](Milestones.md) for roadmap progress.
