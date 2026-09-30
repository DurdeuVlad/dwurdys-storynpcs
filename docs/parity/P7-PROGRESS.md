# P7 — Trader + bank parity progress (P7-1, P7-2)

Status: `IN-PROGRESS` (local implementation, on top of the existing typed/journaled economy core).

## P7-1 — Trader

- `TradeListing`: + `secondaryPriceItemId`/`secondaryPriceCount` (up to two inputs/one output),
  `hasTwoInputs()`, `page` (0–99), `restockIntervalTicks` + durable `lastRestockTick`.
- `validate()`: output + both input slots must be coherent before a transaction begins —
  secondary id requires count, count requires id.
- `restock(now)`: deterministic period-boundary reset; never double-rests within a window;
  0 interval = permanent uses. Survives restart via persisted lastRestockTick.
- `canPurchase(factionPoints, hasPermission)`: server-side uses/faction/permission gate —
  complements the existing reservation protocol for one-winner-per-use concurrency.
- `executeTradeMutationCore` resolves and verifies BOTH input legs before mutating,
  deducts both under the owned reservation, and compensates both identically on
  delivery failure (StoryNpcsApplicationService secondary-input path).
- Existing (prior): reservation/commit/rollback protocol, faction access fields, typed
  `TradeExecutionRequest` envelope + journal from P1-4/P2-3.

## P7-2 — Bank

- `BankVault.MAX_TABS = BankerRole.MAX_TABS = 6` — target bound enforced on both
  `setUnlockedTabs` and `setMaxTabs` (previously unbounded).
- `BankVault.AccessPolicy` PRIVATE (default) / SHARED + `sharedMemberUuids` +
  `canAccess(player)` — unauthorized access rejected; state survives copy/restore.
- Existing (prior): six-channel revisioned item ops, journaled UNLOCK_TAB with
  emerald-cost intent capture and reconcile-on-recover, typed `BankOperationRequest`
  envelope, durable vault repository with restart/corruption fixtures.

## Explicit limits

- Restock has no scheduler driver yet (deterministic function of ticks, needs a tick source).
- Player/admin screens do not yet round-trip the new fields (UI work).
- SHARED vault UI/API surface not yet exposed.

## Verification

`./gradlew test`: 70 suites, 575 tests, 0 failures. `git diff --check` clean. No live MC testing.
