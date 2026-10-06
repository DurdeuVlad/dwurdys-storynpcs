# P9 — API, scripting, commands, admin progress (P9-1..P9-4)

Status: P9-1 `MERGED` (#179), #125 engine pin `MERGED` (#178), P9-3 `MERGED` (#180), P9-4 implemented pending delivery.

## P9-1 — Public API

- `ApiVersion` + negotiate: major mismatch/minor-ahead/null fails clearly; `CURRENT = 1.1.0`.
- `NpcView`: immutable detached snapshot (id, faction, dialogue, display name/model, hitboxState).
- `ApiSessionRegistry`: server-owned capability sessions — opaque `api:<uuid>` actors, bounded
  (≤128 sessions), TTL-expiring (default 72k ticks), revocable, per-instance (never static).
  Only DEFINITION-policy capabilities can be granted; player-scoped authority (vaults, trades,
  progression) is structurally unreachable through a session.
- `AuthorizationPolicy` evaluates `api:<sessionId>` actors against the session registry:
  bare `api` → `API_SESSION_REQUIRED`, malformed → `API_SESSION_MALFORMED`,
  ungranted → `API_CAPABILITY_NOT_GRANTED`, no registry → `REMOTE_AUTH_UNAVAILABLE` (fail closed).
- `DefinitionDomain<T>`: typed per-domain interface for all 13 definition families — npc/dialogue/
  quest/faction/transport/template/spawner/worldtool/recipe/scene/transform/naturalspawn/guilayout/
  modelpreset. `ids()`/`summaries()` return detached views only; `save`/`delete` build the canonical
  `MutationRequest` internally (actor + revision filled by the service — callers can supply neither)
  and return the same `ValidationResult` diagnostics as every other adapter.
- `StoryNpcsApi.subscribe` → `AutoCloseable` subscription; listener failures isolated by
  `EventPublisher`; `unregister` detaches cleanly.
- `StoryNpcsApiAccess` is the supported Level/Entity/MinecraftServer resolver and returns only
  `Optional<StoryNpcsApi>`; the canonical service is never exposed through the facade.
- Transport delete is documented as `TRANSPORT_DELETE_UNSUPPORTED` (no canonical op exists — P9-4).

## P9-2 — Scripting host

- `ScriptHook` ×9 (INIT..TIMER) with per-hook budget class (TICK vs STANDARD); the full matrix is
  auditable in `ScriptHookMatrix` (9 hooks + engine/sandbox/budget surfaces, SUPPORTED or
  INTENTIONAL_DEVIATION per entry).
- `ScriptDefinition` YAML family (`scripts/*.yaml`): namespaced id, `language: javascript`
  (only supported), source ≤64KiB, ≤32 hook names validated against `ScriptHook`, ≤64 capability
  grants each required to be a registered PLAYER_SCOPED capability, `enabled`, `version`.
  Canonical `saveScript`/`deleteScript` run the same validation as the loader — DEFINITION-policy
  grants are rejected on every write path.
- `ScriptHost` — Rhino 1.9.1 interpreted ES6 (#125/ADR-008), per-script fresh standard scope:
  `ClassShutter` admits only `com.storynpcs.script.api.*` (Class/ClassLoader/reflection/IO/process
  unreachable), Java bridge globals stripped per scope, E4X off, `setJavaPrimitiveWrap(false)` for
  native strings, `WrappedException` unwrapping so meter/service failures surface as real types.
- `ScriptBudget` + `Meter`: instructions (20k tick/100k standard via the interpreter observer at
  512-step granularity), 1MiB host-visible live memory, depth 16 (interpreter stack cap), 32
  canonical ops — over-budget throws `BudgetExceeded`, never crashes. Residual disclosed: a single
  interpreter step can grow the JS heap faster than the observer fires, so raw allocation is not
  hard-bounded — `OutOfMemoryError`/`StackOverflowError` at the dispatch boundary route through the
  failure/quarantine path instead of crashing the tick.
- `ScriptScheduler`: 4ms aggregate per tick charged on ACTUAL elapsed time (TICK hooks 1ms, others
  5ms; all three tunable via `RuntimeTunables` script.* keys), consecutive-failure quarantine
  (3 strikes), unquarantine, observable handles, ≤4096 registered scripts.
- `ScriptRuntime`: registration-time precompile (compile cost absorbed at load, not billed to the
  first dispatch), INIT fires at registration, reload drops/evicts/clears and re-registers enabled
  definitions (bumped `version` recompiles), named timers ≤8/script fired on the overworld clock
  inside the aggregate window, `dispatch`/`dispatchFor`/`dispatchScript` surfaces.
- `ScriptHostApi` — the only script-reachable object: free read views + `log`, timers, and
  canonical progression ops (`factionSet|Adjust|Remove`, `questStart|Progress|Reset|Complete`,
  `markDialogueRead`, `clearDialogueReadMarkers`, `unlockTransport`, `sendMail`). Every mutation
  counts one canonical op and routes through the service's scripted boundary —
  `AuthorizationPolicy.evaluateScripted` allows exactly when the authored grant set contains the
  operation; a bare `script` actor is denied `SCRIPT_CAPABILITY_REQUIRED` on every non-scripted
  path (definition writes have no scripted boundary at all).
- NPC binding: `NpcDefinition.scripts` (≤8 namespaced refs, `REF_NPC_SCRIPT_MISSING` cross-ref
  error when dangling); entity hooks dispatch `init/tick/interact/damaged/killed/target` to bound
  scripts only, `dialog`/`quest` dispatch globally from dialogue/quest lifecycle events.
- Commands (perm 2): `script list|info|reload|trigger <id> <hook> [args ≤256]|unquarantine`.
- Migration/schema surfaces updated: `scripts` is a known NPC field (legacy file paths migrate as
  namespaced ids — dangling refs error at load); `SchemaBundle` marks it supported.
- `P92ScriptHostTest`: 24 fixtures — YAML round-trip + canonical save/delete, sandbox Java denial,
  instruction/memory/recursion/canonical-op budgets, granted vs ungranted progression mutation,
  quarantine + isolation, timers, aggregate deferral, reload/version recompile, init ordering,
  trigger args, hook matrix, bound-only dispatch, scheduler domain bounds.

## P9-3 — Command parity

- `CommandSuggestionEngine`: deterministic sorted prefix filtering; `validIdToken`/`argumentValid`
  reject malformed ids/fields before lookup; `parseId` never throws into the command tree.
- `CommandParityCatalog`: all 70 target leaves classified — 58 `SUPPORTED` with an explicit
  equivalent command, 12 `INTENTIONAL_DEVIATION` with rationale, 0 `UNVERIFIED`.
- New command leaves (all thin adapters over canonical service ops):
  `template capture|spawn|grid|delete` (clone parity, grid bounded ≤16 cells),
  `config get|set` (canonical `ConfigTransaction`/`mutateRuntimeTunables`),
  `dialogue markread|unmarkread` (canonical `dialogue.mark.read|clear` player ops),
  `faction reset|remove` (`faction.progress.set`-to-default / `faction.progress.remove`),
  `npc set marks|visibility`, `npc home`, `npc respawn`,
  `follower owner` report + `follower owner <npc_id> <player>` reassign (canonical
  `follower.owner.set`, owner-scoped), `quest objectives|progress`,
  `scene cancelall`.
- New service ops: `markDialogueRead`/`clearDialogueReadMarkers` (player-scoped boundary,
  `DIALOGUE_NOT_FOUND` fail-closed), `FactionProgressionMutationRequest.Action.REMOVE`,
  `FollowerStateMutationRequest.setOwner` + `FollowerOwnerChangeEvent`.
- Intentional deviations (disclosed, not silently mapped): chunk-loader config (dead subsystem),
  font config (client-render → P10-1), scene tick-set/pause (stage-relative lifecycle, no clock),
  script reload/trigger (requires #84 script host), slay class filter (bounded-radius NPC despawn
  only).
- `P93CommandParityTest`: 7 fixtures covering remove semantics, replay dedup, read-marker
  round-trip/scope, unknown-dialogue rejection, owner reassign + non-owner denial.

## P9-4 — Remote/admin/config

- `AdminParityCatalog`: all 16 named target surfaces mapped — `SPacketRemote*` (5 SUPPORTED +
  remote-menu deviation), `SPacketPlayerData*` (SUPPORTED), `SPacketMenu*` (SUPPORTED via
  CustomGuiLayout canonical ops + OverlaySession close), `GuiNpcRemoteEditor`/`GuiNpcManagePlayerData`/
  `GuiAchievement`/`PacketAchievement`/`PacketConfigFont` (client surfaces → P10-1, disclosed).
- Remote ops (`/storynpcs remote freeze|delete|reset|tp|list`, perm 2): entity-uuid targets
  resolved per-level — unloaded or wrong-world targets fail `REMOTE_TARGET_UNLOADED` before any
  mutation; every consequential op publishes `RemoteAdminAuditEvent`.
- Player-data admin (`/storynpcs playerdata read|clear [player]`): canonical
  `adminReadPlayerData`/`adminClearPlayerData` through the player-scoped boundary — SELF for own
  data, ADMIN (perm 2) for cross-player; clear deletes the durable record, idempotent.
- Config durability: `RuntimeTunablesStore` (atomic JSON record under `data/storynpcs/admin/`),
  `RuntimeTunables.restore` validates persisted state on load (corrupt records keep defaults),
  and `mutateRuntimeTunables` persists the committed snapshot before reporting success —
  `CONFIG_PERSIST_FAILED` on store failure.
- `RemoteAccessProof` remains untrusted claims only — authorization evidence is the server-owned
  `ApiSessionRegistry` from P9-1; fabricated proofs still fail closed.

## Explicit limits

- API facade exposes all 13 definition families through typed `DefinitionDomain`s with canonical
  session-gated writes; role/job/trade/bank surfaces are PLAYER_SCOPED runtime operations, not
  definition families, and remain intentionally unreachable through API sessions (P9-4 owns the
  admin surfaces that would exercise them).
- Script source allocation growth inside one interpreter step is not hard-bounded (JVM-level
  OOM remains possible from authored scripts); containment converts it to quarantine rather than
  a crash — the only known residual of the sandbox contract.
- Script timers are in-memory dispatch state (bounded per script); they do not survive restart.
- `CommandSuggestionEngine` is wired: `idSuggestions` routes candidates through `suggest` (invalid-token filter + prefix match + sort) and `getNamespacedId` parses through `parseId`.
- Remote proof/config transaction not yet integrated into network handlers.

## Verification

`./gradlew test`: full suite green after P9-2 (P92ScriptHostTest 24/24, P93CommandParityTest 7/7,
1370 tests / 0 failures). Command-tree behavior is headless-verified through Brigadier
parse/dispatch fixtures and service-op tests; no live MC run for the new leaves (entity-affecting
leaves follow the same adapter pattern as existing GameTest-covered commands).
