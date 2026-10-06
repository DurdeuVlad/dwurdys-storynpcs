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
- Canonical `spawner.delete` clears the durable+cached runtime ledger via `spawnerDeleteListener` → `NpcSpawnerRuntime.resetState` — a recreated same-id spawner can never inherit orphaned quota debt.
- Live evidence: `templateSpawnerSpawnsOwnedNpcInLiveWorld` GameTest (12/12 pass) — real ServerLevel spawn, owner-tag binding, quota-1 never exceeded, `storynpcs:spawned/` definition resolution.

## P8-2 — Movement/utility tools

- `domain.ai.WaypointPath`: ≤64 waypoints (add returns false at the cap — a diagnostic, never silent growth; oversized loads truncate at the model boundary); `moveWaypoint`/`removeWaypoint` with index diagnostics; LOOP/PING_PONG/ONCE traversal.
- `NpcPathItem`: op-2 select/append/clear via canonical `npc.mutate` + cap diagnostic surfacing; `npc path list|add|move|delete|mode|clear` commands cover the full editing vocabulary through the same canonical path with pre-validation (a rejected op never writes a no-op revision bump) + live `applyDefinition` refresh.
- `NpcMounterItem`: `MountPolicy.check` runs against the live mount graph (`mountChain` builds the passenger→vehicle edges the policy needs) — rejects SELF/CYCLE/STACK_TOO_DEEP(≤4)/PASSENGER_ALREADY_MOUNTED before `startRiding`; chair seating also enforces the already-mounted gate; sneak-click dismount/eject audited.
- `NpcTeleporterItem` (`npc_teleporter`): op-2 click-NPC select → click-block teleport; sneak clears; stale selection recovers with a diagnostic; `npc teleport <npc_id> <pos>` command maps the same op.
- `NpcRemoverItem` (`npc_remover`): op-2 two-click confirm (10s TTL via `RuntimeSessionRegistry` pending-tool confirmations — mismatching second clicks consume fail-closed, expiry cannot execute) → live `discard`; definition preserved.
- `NpcSoulStoneItem` (`npc_soulstone`): op-2 two-click capture binds the definition id into the stack's `CUSTOM_DATA` component and despawns the live NPC; block-click redeploys via `StoryNpcRegistry.create` + `setDefinitionId` (stale bindings rejected with a diagnostic).
- `CreatorToolAuditEvent` (`tool, action, playerUuid, target, outcome`) published on every consequential op: waypoint.add/clear, mount/seat apply+reject, dismount/eject, teleport, despawn, soulstone capture/deploy.
- Selections live in `RuntimeSessionRegistry` (per-server instance, per-player keys — no mutable static maps; `clearPlayer` drops them on logout).
- Live evidence: `mountPolicyGatesLiveMountStack` GameTest — real 3-deep mount stack accepts a legal rider while bottom→top is refused CYCLE, a mounted passenger is refused PASSENGER_ALREADY_MOUNTED, self-mount is refused SELF.

## P8-3 — World tools

- `WorldToolDefinition` + `ScriptedHookBinding`: 7 families, schemaVersion, namespaced blockId (validated for block families), dimension binding, bounded blocks-per-activation ≤1024, authored inert hook/scene payloads — never executes user code (script EXECUTION stays deferred to P9-2 / #84).
- YAML family `definitions/worldtools/*.yaml` loads through `YamlDefinitionLoader` (case-insensitive family enum), registers in `DefinitionRegistry`, mutates via canonical `saveWorldTool`/`deleteWorldTool`/`activateWorldTool` + `storynpcs worldtool {list,info,activate,delete}` commands.
- `WorldToolOpsCatalog`: explicit reversible/irreversible ops (`block.place`/`block.remove` reversible; `hooks.bind`/`signal.pulse`/`mail.deposit`/`scene.activate` irreversible); SCENE requires authored steps, SCRIPTED_BLOCK requires an authored payload.
- `WorldToolExecutor`: preview/apply/rollback `executePlan` (pure, failure ⇒ reverse-order undo of completed reversible legs, `LOGGED` for irreversible, honest `rollbackFailed` reporting), activation enforcement (dimension+position binding, dimension-locked tools), mailbox deposit via `QuestMailStore`, scene activations registered in `RuntimeSessionRegistry` (cleared on logout/world unload).
- `WorldToolBindingStore`: per-position inert hook bindings under `world/storynpcs/world_tool_bindings/` (IndexedRecordStore), opened/closed by `WorldLifecycleHandler`; persisted bindings survive reactivation.
- Live evidence: `worldToolActivationMutatesLiveWorld` GameTest — canonical save, real activation places REDSTONE_BLOCK + persists the authored binding, dimension-locked tool rejected cross-dimension, missing tool id reported cleanly.

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

- P8-1, P8-2, and P8-3 are wired end-to-end (runtime, items/commands, audit, durable state). P8-4..P8-6 remain domain contracts only — no network packets, client screens, or entity wiring yet.
- Templates persist via `templates/*.yaml`; spawner rules via `spawners/*.yaml`; world tools via `worldtools/*.yaml`; spawner runtime state and world-tool activation bindings via durable `IndexedRecordStore` ledgers.
- A spawner whose template is deleted keeps spawning from its last-instantiated definition (snapshot semantics); deleting a spawner stops future spawns but leaves already-spawned actors in-world — both are surfaced explicitly.
- Placement uses a seeded `RandomSource` (deterministic per rule+tick sequence); quota/interval/chunk/unload rules are deterministic.

## Verification

`./gradlew test`: 1272 tests, 0 failures. `./gradlew runGameTestServer`: 14/14 pass (live spawner, mount-policy, and world-tool activation fixtures). `git diff --check` clean.
