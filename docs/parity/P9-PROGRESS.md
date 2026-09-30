# P9 — API, scripting, commands, admin progress (P9-1..P9-4)

Status: `IN-PROGRESS` for all four issues (local implementation).

## P9-1 — Public API

- `ApiVersion` + negotiate: major mismatch/minor-ahead/null fails clearly; `CURRENT = 1.0.0`.
- `NpcView`: immutable detached snapshot (id, faction, dialogue, display name/model, hitboxState).
- `StoryNpcsApi` facade is read-only: negotiate → immutable views + diagnostics. The unsafe
  `canonical()` service escape hatch was removed; safe typed writes still require capability grants.

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

- `RemoteAccessProof`: bound session + capability + expiry — `permits` requires all three.
- `PlayerDataScope` SELF (operator==target only) vs ADMIN.
- `ConfigTransaction`: stage → validate → commit-or-rollback; stale revision and validation
  failures leave the live map untouched; committed revision increments.

## Explicit limits

- API facade covers NPC view + diagnostics; per-family views (dialogue/quest/trade/...) not yet exposed.
- Script host enforces quotas cooperatively — no actual script-language runtime/wrappers yet.
- `CommandSuggestionEngine` is wired: `idSuggestions` routes candidates through `suggest` (invalid-token filter + prefix match + sort) and `getNamespacedId` parses through `parseId`.
- Remote proof/config transaction not yet integrated into network handlers.

## Verification

`./gradlew test`: 72 suites, 596 tests, 0 failures. No live MC testing.
