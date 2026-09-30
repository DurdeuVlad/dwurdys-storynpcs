# P1-2 Closeout — Stable actors and managed runtime lifecycles

Status: `IN-REVIEW`

This slice implements the runtime foundation for separating durable logical NPC actors from replaceable Minecraft entity projections. It does not certify overall CustomNPCs parity; the target runtime evidence gate remains separate.

## Implemented

- `ActorProjectionRegistry` is instance-owned and scoped to a server/world identity. It maps a namespaced logical actor ID to the current entity UUID and maintains a reverse projection index.
- Projection transitions have explicit reasons and states: spawn, unload, reload, replacement, refresh, failure, retry success, and restore.
- Replacement requires an explicit expected projection UUID; stale rebinds and stale failure reports cannot overwrite the active projection.
- `ActorLifecycleService` publishes `ActorLifecycleEvent` with scope, actor ID, projection UUID, reason, state, applied/retryable outcome, and diagnostic text.
- `ActorStateRepository` persists logical actor IDs and definition IDs using `.tmp` write, `FileChannel.force(true)`, and replace/atomic move. Projection UUIDs are intentionally excluded from durable state.
- `StoryNpcState` and `StoryNpcEntity` persist `StoryNpcActorId` in entity NBT. Fresh entities receive unique `storynpcs:actor/<entity-uuid>` IDs, so clones sharing a definition remain separate actors.
- Saved-data loading binds the durable actor ID before projection registration. Legacy NBT without an actor ID reuses the only unloaded actor for that definition, reports ambiguity as retryable, or creates and immediately binds a new actor when no candidate exists.
- `WorldLifecycleHandler` creates/restores/saves actor state per world root and saves it during autosave and server stop.
- Server-start/stop/logout hooks use the event/entity server context; server-scoped actor/repository lookups do not fall back to another server’s mutable context.
- Player tool selections, clone templates, passenger selections, packet throttles, and follower formation slots are now held in instance-owned runtime registries. Logout and server-stop cleanup clear them.

## Evidence

- `.\gradlew.bat test --console=plain --rerun-tasks`: `BUILD SUCCESSFUL`; 259 tests, 0 failures, 0 errors.
- Focused actor/session/follower/state tests pass.
- Static search confirms the former mutable collections (`PLAYER_CLONE_TEMPLATES`, `SELECTED_PASSENGERS`, `SELECTED_NPC`, `LAST_CHOICE_MILLIS`, `LAST_ROLE_ACTION_MILLIS`, and `FollowerGroup.LEADER_FOLLOWERS`) are absent from production code.
- Independent read-only audits found and drove remediation of stale projection failure, orphan-prone legacy NBT load ordering, stale replacement binding, and cross-server cleanup/fallback paths. The final scope-tightening was revalidated locally with the full suite and static scan; no fresh auditor slot was available after the last two-line fail-closed helper adjustment.
- NeoForge’s entity model treats registered `EntityType` objects as shared definitions and `Entity` objects as per-instance state; the implementation follows that separation and persists actor state outside the projection UUID. See [NeoForged Entities](https://docs.neoforged.net/docs/entities/) and [NeoForged Saved Data](https://docs.neoforged.net/docs/datastorage/saveddata/).

## Tests added or updated

- `ActorLifecycleServiceTest`: replacement, unload/reload, failure/retry, scope isolation, atomic persistence, and projection-conflict protection.
- `RuntimeSessionRegistryTest`: per-instance isolation and logout cleanup.
- `StoryNpcStateTest`: logical actor identity independent of definition identity.
- `FollowerGroupTest` and `FollowerFormationGoalTest`: instance-owned formation state.

## Lifecycle governance update (review-remediation slice)

- The mutable `StoryNpcs.instance` singleton is **removed entirely**. The mod instance is published onto every loaded `Level` — server dimensions and the client world — as a transient NeoForge data attachment (`StoryNpcsAttachments.MOD_HANDLE`) at `LevelEvent.Load`, so the handle lives and dies with the level lifecycle.
- All 129 former `getInstance()`/`requireInstance()` call sites now resolve through `StoryNpcsAccess` (`mod(LevelAccessor|Entity|MinecraftServer)` + `require(...)` variants): commands resolve via the command source's server, packet handlers via the `ServerPlayer`, entities/goals via their own level, tool items via the acting player, and `WitnessProtectionManager` via the attacker entity. The unused UUID-only `StoryNpcsNetwork.clearPlayer` overload — which had no lifecycle object to resolve through — was deleted.
- Repository-wide managed-lifecycle scan: **zero** non-final static fields remain under `src/main/java` — the allowlist is gone. `ManagedLifecycleScanTest` now fails on *any* new mutable static field and asserts the null-safe resolution contract plus test-fixture isolation.
- Server-scoped service resolution (`serverActorServices`, `serverActorRepositories`, `serverRuntimeSessions`, `serverFollowerGroups`) remains keyed by `MinecraftServer` with `clearServerRuntime` teardown; no static fallback exists.

## Known boundary

`StoryNpcsAccess` resolution depends on `LevelEvent.Load` firing for every level the mod serves — NeoForge fires it for the overworld, each dimension, and the client world, and the listener is registered in the mod constructor so no load can precede publication. Certification still requires an independent adversarial review of this resolution layer plus the target-runtime evidence gate; attachment-population timing inside the live client bootstrap is part of that gate.

The logical actor registry currently persists identity and definition association. Role-specific durable payload migration and progression/economy recovery remain tracked by P2-2 and the role milestone; this issue provides the stable owner and lifecycle hooks those stores will use.
