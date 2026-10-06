# Dwurdy's StoryNPCs (`storynpcs`)

> Next-generation Minecraft NPC and narrative orchestration framework for NeoForge 1.21.1+.

Dwurdy's StoryNPCs is a modern, clean-room reimagining of the storytelling capabilities traditionally found in CustomNPCs.

**[v0.11.0-beta.1 is out](https://github.com/DurdeuVlad/dwurdys-storynpcs/releases/tag/v0.11.0-beta.1)** — first public beta. New here? [Getting started](docs/getting-started.md) · [Changelog](CHANGELOG.md) · [Beta testing guide](docs/beta-testing.md)

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

The full CustomNPCs-parity program is **implemented** in v0.11.0-beta.1 — but parity with the target runtime is **not certified**: no CustomNPCs runtime probe exists, so behavioral rows are `UNVERIFIED_TARGET_RUNTIME` and the release gate remains `BLOCKED` by design. The exact inventory, evidence states, intentional deviations, and unsupported claims are tracked in the [parity roadmap](docs/CUSTOMNPCS_PARITY_ROADMAP.md) and [release notes](docs/RELEASE-NOTES.md). Static target inventory and passing unit tests do not prove runtime parity.

## Architecture

See [Business.md](Business.md) for domain architecture, [Decision.md](Decision.md) for architectural decision records, and [Milestones.md](Milestones.md) for roadmap progress.
