# P7 — Trader + bank parity progress (P7-1, P7-2)

Status: `IN-PROGRESS` (local implementation, on top of the existing typed/journaled economy core).

## P7-1 — Trader

- `TradeListing`: `secondaryPriceItemId`/`secondaryPriceCount` (two inputs/one
  output), `hasTwoInputs()`, `page` (0–99), `restockIntervalTicks`.
- `validate()`: output + both input slots coherent before a transaction begins —
  secondary id requires count, count requires id, identical input items rejected
  (under-charge vector). The admin commit path runs the same `validate()`.
- **Restock is now driven and durable**: `TradeStateRepository.State.restockTicks`
  carries the per-listing boundary marker beside `uses`; `restockIfDue` resets
  uses and advances the marker (interval catch-up) in ONE atomic durable write —
  the reset and the marker cannot split across a crash, and both survive restart.
  Drivers: `executeTradeJournaled` checks before reading uses, and the trade-open
  view (`sendTradeOpen`) checks before rendering availability — a due restock is
  visible before it is purchasable. Interval precedence: the listing's own
  `restockIntervalTicks` wins; `TraderRole.restockIntervalTicks` (default 24000 =
  1 in-game day) is the role-wide fallback; 0 + 0 means permanent stock. A
  listing needing an explicit opt-out under a nonzero role interval sets a very
  large interval (documented edge — no dedicated "never" flag).
- `executeTradeMutationCore` resolves and verifies BOTH input legs before
  mutating, deducts both under the owned reservation, and compensates both
  identically on failure.
- **Full-inventory output now follows the P5-5 mail policy**: `inventory.add`
  places what fits; ANY remainder — including a partial fit that a bare `!add`
  check would have voided — is queued through `enqueueItemRemainderMail` as
  durable claim-once mail provenanced to the trading NPC. Mail-store
  unavailability or a write failure aborts the whole exchange (inventory
  snapshot restore + durable use rollback), so a purchase that cannot complete
  its delivery contract is fully side-effect-free.
- Prior (verified): reservation/commit/rollback protocol (one-winner-per-use),
  faction access fields + server-side `canPurchase`, typed
  `TradeExecutionRequest` envelope + journal with prepare/commit/recover and
  pending-record reconciliation.
- Admin + player screens round-trip identical listing data: the admin form now
  edits second input, page, and restock interval; the save path is the whole-
  definition `ServerboundNpcSavePayload`, so fields serialize identically to YAML.

## P7-2 — Bank

- `BankVault.MAX_TABS = BankerRole.MAX_TABS = 6` — enforced on both
  `setUnlockedTabs` and `setMaxTabs`.
- `BankVault.AccessPolicy` PRIVATE (default) / SHARED + `sharedMemberUuids` +
  `canAccess(player)` — unauthorized access rejected; state survives copy/restore.
- `bank.share` canonical op wired: `BankAccessMutationRequest` (owner-scoped or
  admin command actor), `CapabilityRegistry` policy, `AuthorizationPolicy`
  evaluator, `/storynpcs bank share` command adapter. Shared vaults open through
  `sendBankOpen` only via `canAccess` — the vault owner resolves from the
  authored banker role, never a client field.
- Prior (verified): six-channel revisioned item ops, journaled UNLOCK_TAB with
  emerald-cost intent capture and reconcile-on-recover, typed
  `BankOperationRequest` envelope, durable vault repository with
  restart/corruption fixtures, login recovery for prepared operations.

## Explicit limits

- Overflow mail provenance reuses the mail record's `questId` slot to carry the
  trading NPC id — structural fit, semantic rename deferred.
- Trade UI button does not pre-disable on predicted-full inventory — the
  server-side mail overflow path is the authoritative contract.
- Companion/trade/bank interactive container UIs beyond the existing screens →
  wave-2 scope (#150).

## Verification

`./gradlew test`: 1232 tests, 0 failures, 1 skipped. `git diff --check` clean.
New fixtures: durable restock boundary/catch-up/restart (`P7DomainTest`),
role-interval fallback precedence, admin round-trip of second-input/page/
restock fields, incoherent-secondary rejection (`TraderBankerAdminScreenModelTest`).
No live MC testing this pass.
