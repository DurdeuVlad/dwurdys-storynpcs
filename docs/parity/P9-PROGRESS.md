# P9 — API, scripting, commands, admin progress (P9-1..P9-4)

Status: `IN-PROGRESS` for all four issues (local implementation).

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
- Full 70-leaf manifest mapping remains in the parity docs.

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

`./gradlew test`: 72 suites, 596 tests, 0 failures. No live MC testing.
