# P8 — Creator tools progress (P8-1..P8-6)

Status: `IN-PROGRESS` for all six issues (local implementation under `com.storynpcs.creator`).

## P8-1 — Templates/spawners

- `NpcTemplate`: namespaced, schemaVersion, revision, description, tags; `instantiate` deep-copies the definition — no shared mutable state.
- `TemplateLibrary`: deterministic search, spawner-dependent registry, `delete` reports dependents, `importValidation` rejects unknown fields + unsupported schema.
- `SpawnerRule`: quota ≤64, interval ≥20 ticks, placement radius ≤64, unload cleanup, deterministic `shouldSpawn`.

## P8-2 — Movement/utility tools

- `WaypointPath`: ≤64 waypoints, add/move/delete with explicit index validation, LOOP/PING_PONG/ONCE traversal.
- `MountPolicy`: rejects SELF/CYCLE/STACK_TOO_DEEP/PASSENGER_ALREADY_MOUNTED (depth ≤4).
- Selection sessions already managed via RuntimeSessionRegistry — no static maps added.

## P8-3 — World tools

- `WorldToolDefinition`: 7 families, dimension binding, bounded blocks-per-activation ≤1024.
- `ScriptedHookBinding`: inert typed bindings (namespaced hookId, event, ≤16 params) — never executes code.

## P8-4 — Recipes

- `CarpentryRecipe`: namespaced id/group, exact 3x3 grid, output stack, shapeless flag; slot-accurate validation diagnostics.

## P8-5 — Links/scenes/transforms/timers/natural spawn

- `LinkedNpcGraph`: SELF/CYCLE/TARGET_MISSING rejection, unload cleanup.
- `SceneDefinition`: entity ≤64/duration budgets, stages, CancelRecovery policy.
- `SceneTimer`: durable timer entries, catch-up without burst, actor cancel.
- `TransformRule`: PRESERVE/REPLACE identity policy, triggers, replaced facets.
- `NaturalSpawnRule`: weight ≤1000, per-dimension cap ≤128, min player distance, deterministic `eligible`.

## P8-6 — Custom GUI/HUD

- `CustomGuiLayout`: depth ≤6, children ≤8, elements ≤64, size bounds; element-path diagnostics.
- `OverlaySession`: session-scoped overlays ≤8, tick expiry, logout cleanup.

## Explicit limits

- Domain contracts only — no network packets, client screens, or entity wiring yet.
- Templates persist via `templates/*.yaml` (loadable family, canonical saves through `RegistryImportSink`/`saveNpc` instantiation); world-mutation executors absent.

## Verification

`./gradlew test`: 71 suites, 588 tests, 0 failures. `git diff --check` clean. No live MC testing.
