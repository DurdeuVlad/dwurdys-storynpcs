# Testing strategy: two proof tiers

This repository proves correctness at two boundaries. Pick the tier that
matches what the change actually touches; most changes only need the first.

## Tier 1 — JUnit 5 (`src/test`, run via `./gradlew test`)

Use for anything expressible as pure domain logic: validators, services,
YAML serde, the dialogue graph, persistence repositories, rule engines,
command parsing, and GUI/HUD **logic** pulled out of the actual `Screen`
subclass into a plain "ScreenModel" object (see
`DialogueEditorScreenModelTest`, `NpcRulesScreenModelTest`,
`QuestEditorScreenModelTest` for the pattern — the `Screen` itself stays a
thin renderer over a model that is tested headlessly, no Minecraft client
needed).

This is the default tier and, per `AGENTS.md`, mandatory for every domain
model, validator, and service operation. It runs in milliseconds and needs
no Minecraft server or client.

## Tier 2 — NeoForge GameTest (`src/main/java/com/storynpcs/gametest`, run via `./gradlew runGameTestServer`)

Use when a change can only be verified against a live, ticking Minecraft
world: entity spawning, AI goals, world-mutating commands, or anything
where the behavior depends on actual server/world state rather than logic
that can be pulled out and unit tested.

`./gradlew runGameTestServer` boots a **headless** dedicated server (no
window), runs every `@GameTest`-annotated method registered under the
`storynpcs` namespace, prints a pass/fail report per test, and **exits on
its own** — 0 on success, non-zero if any required test fails. No human
interaction, no client, no visual inspection required.

Example: `StoryNpcsGameTests` spawns a real `StoryNpcEntity` through the
registered `EntityType` and drives a definition through the actual
`StoryNpcsApplicationService.createNpc(...)` canonical mutation path (never
a direct registry poke, per `AGENTS.md`'s Canonical Operation Parity rule),
then asserts on the resulting live entity/world state.

### Adding a new GameTest

- Add methods to `StoryNpcsGameTests` (or a new `@GameTestHolder(StoryNpcs.MOD_ID)` class under the same package).
- Reference the existing empty test platform with `@GameTest(template = "gametest/empty_3x3x3")`, or add a new structure under `src/main/resources/data/storynpcs/structure/<path>.nbt` if a test needs pre-placed blocks.
- Keep `@PrefixGameTestTemplate(false)` on the class so the template path isn't additionally prefixed with the class's simple name.
- Call `helper.assertTrue(condition, message)` for assertions and `helper.succeed()` at the end of a passing path.

### What this tier does not cover

Actual pixel-level rendering (GUI layout, HUD appearance, model/texture
previews) is out of reach in this environment — there is no way to launch
a windowed Minecraft client here. That's exactly why GUI logic is split
into the testable ScreenModel pattern in Tier 1: the transition logic gets
real proof, the pixels do not.

## Evidence expectation

Per `AGENTS.md`, never declare a feature or milestone complete without
running the relevant tier(s) and pasting the actual console output (or
summarizing it accurately) as evidence — a plan or code review is not a
substitute for having actually run `./gradlew test` and, where the change
touches live world/entity behavior, `./gradlew runGameTestServer`.
