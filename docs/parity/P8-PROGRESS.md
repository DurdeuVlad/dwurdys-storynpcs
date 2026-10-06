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

- `CarpentryRecipe`: schemaVersion, namespaced id/group, exact 3x3 grid, output stack ≤64, shapeless flag; slot-accurate validation diagnostics; unsupported fields fail closed at load.
- YAML family `definitions/recipes/*.yaml` loads through `YamlDefinitionLoader`, registers in `DefinitionRegistry` (+ group index), mutates via canonical `saveRecipe`/`deleteRecipe` + `storynpcs recipe {list,info,delete}` commands; malformed recipes reject before any file/registry write — no partial registration.
- `CarpentryBenchMatcher`: pure shaped (exact grid) / shapeless (multiset) matching; ambiguous matches reported via `allMatches` — never silently resolved by iteration order.
- `RecipesLoadedEvent` published on initial load, reload, import apply, and canonical save/delete.
- Deferred honestly: `BlockCarpentryBench`/`ContainerCarpentryBench`/GUIs (`GuiNpcManageRecipes`, `GuiNpcCarpentryBench`, `GuiRecipes`) — P10-1 screen wave; `SPacketRecipe*` — P1-3 protocol; `RecipesDefault` bundled-content seeding — StoryNPCs ships no target recipe corpus (authors write YAML).

## P8-5 — Links/scenes/transforms/timers/natural spawn

- `LinkedNpcGraph`: SELF/CYCLE/TARGET_MISSING rejection; durable `LinkedNpcStore` ledger rehydrates links on world open (`LinkedNpcRuntime.attach`), unload cleanup on stop.
- `SceneDefinition`: entity ≤64/duration budgets (`SCENE_OVER_BUDGET` diagnostics at load), stages, CancelRecovery policy (RESTORE_POSITIONS / LEAVE_IN_PLACE); `SceneRuntime` spawns participants via the canonical `createNpc` path, tags them `storynpcs:scene_owner`, advances stage markers, terminates on the duration budget with recovery — sessions can never run unbounded.
- `SceneTimer`: durable `SceneTimerStore` ledger; `TimerRuntime` rehydrates on open, catch-up is coalesced (one fire per overdue timer — no burst), actor-scoped cancel, at-most-once execution.
- `TransformRule`: PRESERVE (facet merge via canonical `npc.mutate` — identity + progression kept) vs REPLACE (canonical `npc.create` under a derived id) identity policy; `storynpcs transform apply <rule> <npc>` exercises MANUAL rules end-to-end. ON_DEFEAT/ON_QUEST_COMPLETE/ON_TIMER triggers are declared and validated but not yet hooked — timer events already fire `NpcTimerEvent` and defeat/quest-completion event seams exist, so trigger wiring is a bounded follow-up, not a redesign.
- `NaturalSpawnRule`: weight ≤1000, per-dimension cap ≤128, min player distance, deterministic seeded `eligible`; `NaturalSpawnRuntime` runs a 200-tick per-dimension eval that weighted-picks ONE eligible rule (seeded per dim+tick — deterministic, and the rule set cannot amplify into one-spawn-per-rule-per-pass), then spawns through the canonical `createNpc` path under the rule's tagged-actor quota.
- YAML families `definitions/{scenes,transforms,naturalspawns}/*.yaml` load through `YamlDefinitionLoader` (lowercase enum values accepted), register in `DefinitionRegistry`, mutate via canonical `saveScene`/`deleteScene`/`saveTransform`/`deleteTransform`/`saveNaturalSpawn`/`deleteNaturalSpawn` + matching `storynpcs {scene,transform,naturalspawn}` commands; `storynpcs link`/`unlink` bind actor pairs through `LinkedNpcRuntime`; `storynpcs timer {list,schedule,cancel}` manages durable timers (schedule/cancel persist through `TimerRuntime`, never through a static map).
- Orchestration events (`SceneLifecycleEvent`/`NpcTimerEvent`/`NpcLinkEvent`/`NpcTransformEvent`/`NaturalSpawnEvent` in `P85OrchestrationEvents`) publish through the mod event bus on each transition.

## P8-6 — Custom GUI/HUD

- `CustomGuiLayout`: schemaVersion, bounded tree (depth ≤6, children ≤8, elements ≤64, dimension ≤512) with element-path diagnostics; closed element-type vocabulary (panel/button/label/texture/input/scroll/item_slot/entity_display); `texture` elements require a namespaced `textureRef`; optional authored `textKey` localization key per element.
- `ModelPreset`: schemaVersion, namespaced `modelRef`, display name, ≤8 named color layers (RGB range-checked, duplicate names rejected), ≤8 namespaced texture refs — the server-authoritative schema for the target Preset/ModelColor/GuiPresetSave surfaces.
- YAML families `guilayouts/` + `presets/` load through `YamlDefinitionLoader`, register in `DefinitionRegistry`, mutate via canonical `saveGuiLayout`/`deleteGuiLayout`/`saveModelPreset`/`deleteModelPreset`; malformed/preset/layout payloads reject before any file write — no partial registration.
- `OverlaySession`: session-scoped overlays ≤8 per session, tick-bounded expiry pruned on read, deterministic logout close (with `OverlayExpiredEvent` per dropped entry), `clearAll` on server stop; per-server instance in `StoryNpcs`, login opens the session, never a static map.
- Commands: `storynpcs layout {list,show,preview,delete}` (preview validates + projects structure with zero writes — preview ≠ commit), `storynpcs preset {list,show,delete}`, `storynpcs overlay {list,show}` (player-scoped).
- Events (`P86GuiEvents`): `CustomGuiOpenedEvent`/`CustomGuiActionEvent`/`CustomGuiClosedEvent`/`OverlayShownEvent`/`OverlayExpiredEvent` — parity records for the target `CustomGuiEvent` family; interaction events publish only through the canonical packet boundary.
- Deferred honestly: `GuiCustom*`/`GuiCreation*`/`GuiPresetSave`/`GuiModelColor` screens and `CustomGui*` component widgets → P10-1; `PacketOverlay*`/GUI packets → P1-3; client-side `PresetController` rendering/caching → P10-1 (server-authoritative preset schema is the mapped surface); keyboard nav/high-DPI/min-resolution/discoverability fixtures are client-screen properties → P10-1; `ICustomGui`/`IOverlay`/`ILabel` typed API bindings → P9-1.

## Explicit limits

- All six P8 slices are wired end-to-end (runtime, items/commands, audit, durable state or session-scoped runtime where durability is wrong). No P8 surface writes through a mutable static singleton.
- Templates persist via `templates/*.yaml`; spawner rules via `spawners/*.yaml`; world tools via `worldtools/*.yaml`; recipes via `recipes/*.yaml`; scenes/transforms/natural-spawn rules via `scenes|transforms|naturalspawns/*.yaml`; GUI layouts via `guilayouts/*.yaml`; model presets via `presets/*.yaml`; spawner, world-tool binding, scene-participant, timer, and link state via durable `IndexedRecordStore` ledgers under `world/storynpcs/`; overlays are deliberately session-scoped (never durable — stale overlays on a client must not resurrect).
- A spawner whose template is deleted keeps spawning from its last-instantiated definition (snapshot semantics); deleting a spawner stops future spawns but leaves already-spawned actors in-world — both are surfaced explicitly.
- Scene participants persist in-world after session end (both RESTORE_POSITIONS and LEAVE_IN_PLACE leave spawned actors; no despawn-on-finish semantic exists yet — scripted despawn is a P9-2 script surface).
- Script execution inside scenes/timers/hooks stays deferred to P9-2 (#84): orchestration fires events and executes bounded structural actions only, never user code.
- Transform and natural-spawn runtime drivers evaluate per server tick with bounded work (quota/interval/dimension gates short-circuit before any entity scan); placement uses a seeded `RandomSource` (deterministic per rule+tick sequence).

## Verification

`./gradlew test`: 1298 tests, 0 failures. `./gradlew runGameTestServer`: 15/15 pass (live spawner, mount-policy, world-tool activation, and scene spawn/terminate fixtures). `git diff --check` clean.
