# P9 — API, scripting, commands, admin progress (P9-1..P9-4)

Status: P9-1 `MERGED` (#179), #125 engine pin `MERGED` (#178), P9-3 implemented pending delivery.

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

- `ScriptHook` ×9 (INIT..TIMER) with per-hook budget class (TICK vs STANDARD).
- `ScriptBudget` + `Meter`: instructions (20k tick/100k standard), 1MiB live memory, depth 16,
  32 canonical ops — over-budget throws `BudgetExceeded`, never crashes.
- `ScriptScheduler`: 4ms aggregate per tick (TICK hooks 1ms, others 5ms spanning ticks),
  consecutive-failure quarantine (3 strikes), unquarantine, observable handles.

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

- `RemoteAccessProof` models session, capability, and expiry claims, but no server-owned session
  issuer/validator is wired. API mutations therefore fail closed; a caller-supplied proof object is
  not authorization evidence.
- `PlayerDataScope` SELF (operator==target only) vs ADMIN.
- `ConfigTransaction`: stage → validate → commit-or-rollback; stale revision and validation
  failures leave the live map untouched; committed revision increments.

## Explicit limits

- API facade exposes all 13 definition families through typed `DefinitionDomain`s with canonical
  session-gated writes; role/job/trade/bank surfaces are PLAYER_SCOPED runtime operations, not
  definition families, and remain intentionally unreachable through API sessions (P9-4 owns the
  admin surfaces that would exercise them).
- Script host enforces quotas cooperatively — no actual script-language runtime/wrappers yet.
- `CommandSuggestionEngine` is wired: `idSuggestions` routes candidates through `suggest` (invalid-token filter + prefix match + sort) and `getNamespacedId` parses through `parseId`.
- Remote proof/config transaction not yet integrated into network handlers.

## Verification

`./gradlew test`: full suite green after P9-3 (P93CommandParityTest 7/7, StoryNpcsCommandsTest +
CommandParityCatalogTest green, 0 failing suites). Command-tree behavior is headless-verified
through Brigadier parse/dispatch fixtures and service-op tests; no live MC run for the new leaves
(entity-affecting leaves follow the same adapter pattern as existing GameTest-covered commands).
