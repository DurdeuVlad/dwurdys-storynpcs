# P8 — Creator tools progress (P8-1..P8-6)

Status: `IN-PROGRESS` for all six issues (local implementation under `com.storynpcs.creator`).

## P8-1 — Templates/spawners

- `NpcTemplate`: namespaced, schemaVersion, revision, description, tags; `instantiate` deep-copies the definition — no shared mutable state.
- `TemplateLibrary`: deterministic search, spawner-dependent registry (dedup + unregister), `delete` reports dependents, `importValidation` rejects unknown fields + unsupported schema.
- `SpawnerRule`: quota ≤64, interval ≥20 ticks, placement radius ≤64, unload cleanup, respawn policy, `enabled` flag, and an all-or-none world anchor (`dimension` + `anchorX/Y/Z`) — a rule without an anchor is an inert preset.
- `spawners/*.yaml` is a loadable definition family (schemaVersion, duplicate-id, partial-anchor and missing-template diagnostics; missing template is a warning — the spawner stays inert).
- Canonical ops: `spawner.replace`/`spawner.delete` through `executeCanonicalMutation` with revision/replay/fingerprint handling (`spawner.mutate`/`spawner.edit`/`spawner.delete` capability rows); `spawner place` command mints anchored rules via the same op.
- `NpcSpawnerRuntime`: staggered 20-tick evaluation; spawn only when the anchor chunk is loaded; owned-actor ledger via the `TemplateSpawner` persistent-data key + `EntityLeaveLevelEvent` resolution; `cleanupOnChunkUnload` discards on unload; `respawnOnDeath=false` permanently consumes quota slots; a 400-tick missing-grace releases dead-in-unloaded-chunk actors; spawn definitions are instantiated lazily once per rule under `storynpcs:spawned/<path>` via canonical `npc.create` (system actor) — clone-spawns share one definition rather than polluting the registry.
- `SpawnerRuntimeStore` (`IndexedRecordStore`, `storynpcs/spawner_state/`): owned UUIDs, last spawn tick, deaths, instantiated definition id — durable per change so restart enforces quotas against surviving actors; stale records prune on a bounded cadence.
- `NpcSpawnerLifecycleEvent` audit events: spawned, released-death, released-unload-cleanup, released-despawned, released-missing-grace, state-pruned.
- Live evidence: `templateSpawnerSpawnsOwnedNpcInLiveWorld` GameTest (12/12 pass) — real ServerLevel spawn, owner-tag binding, quota-1 never exceeded, `storynpcs:spawned/` definition resolution.

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

- P8-2..P8-6 remain domain contracts only — no network packets, client screens, or entity wiring yet (P8-1 spawner runtime is wired; the rest are not).
- Templates persist via `templates/*.yaml`; spawner rules via `spawners/*.yaml`; spawner runtime state via the durable `IndexedRecordStore` ledger.
- A spawner whose template is deleted keeps spawning from its last-instantiated definition (snapshot semantics); deleting a spawner stops future spawns but leaves already-spawned actors in-world — both are surfaced explicitly.
- Placement uses a seeded `RandomSource` (deterministic per rule+tick sequence); quota/interval/chunk/unload rules are deterministic.

## Verification

`./gradlew test`: 1253 tests, 0 failures, 1 skipped. `./gradlew runGameTestServer`: 12/12 pass (includes the live spawner fixture). `git diff --check` clean.
