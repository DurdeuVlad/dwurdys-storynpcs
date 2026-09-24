# Independent follow-up — bank withdrawal slice — 2026-09-22

Status: the request-lock concurrency remediation is `APPROVED` with a single-live-repository runtime assumption; the broader parity slice remains `IN-REVIEW`.

## Handoff

- **Lens:** Attempt value loss, duplicate withdrawal, invalid coordinate, event-order, component-data, and adapter-boundary failures in the new whole-stack withdrawal path.
- **Evidence examined:** `DurableOperationJournal.withOperationLock`, `StoryNpcsApplicationService.withdrawAndDeliverJournaled`, `reconcilePendingBankWithdrawal`, `WorldLifecycleHandler`, and `BankRepositoryTest.recoveryWaitsForInFlightJournaledWithdrawal`.
- **Findings fixed in this checkpoint:** rejection codes are preserved through unchanged transactions; invalid tab/slot bounds are explicit; materialization and compensation are service-owned; component-bearing item parse failures no longer silently degrade to a plain item; successful withdrawal events occur after service-owned delivery.
- **Residual P1/P2 findings:** a crash after vault removal and before delivery/journal commit can still leave an ambiguous `PREPARED` operation for explicit operator recovery; the operator recovery UI and live process-crash/inventory fixture do not exist. Compensation failure remains visible as `DELIVERY_RECOVERY_REQUIRED`.
- **Verification:** Focused `BankRepositoryTest` passed. `.\gradlew.bat test --rerun-tasks --console=plain` passed; JUnit XML reports 323 tests, 0 failures, and 0 errors. Parity tooling passed (47 Python tests), truth and evidence gates passed, the fixture harness validated 24 structural fixtures, and `git diff --check` found no whitespace errors beyond line-ending notices. The fixture harness still reports parity certification `BLOCKED` because target-runtime outcomes are unverified. An earlier full run had a transient three-test failure; the lifecycle/static-singleton flake remains an unresolved P1-2 risk.
- **Assumptions:** Production owns one live `BankRepository`/`DurableOperationJournal` for the bank directory, as wired by `WorldLifecycleHandler`. Server packet handlers execute on the server thread. No live GameTest or process-crash inventory fixture exists yet.
- **Initial blocker:** At the time of this audit, durable withdrawal journaling and restart reconciliation were missing. Both have since been implemented, with ambiguous delivery retained as pending recovery.

## Remediation checkpoint

The initial audit's compound-subject, malformed-intent, and removed/restocked-state findings were addressed in the shared worktree. A fresh read-only audit then found one remaining P1 data-integrity case: exact item identity and count alone could mistake an identical restock for the untouched source stack.

- Added a durable monotonic `BankVault.revision`, advanced only by a committed `BankRepository.transact(...)` mutation and restored on rollback.
- Added `vaultRevision` to `BankOperationIntent`; new withdrawal intents capture the revision before the journal pivot.
- Recovery now auto-aborts only when the exact stack and captured revision both match. An identical restock therefore remains `PREPARED` and unresolved.
- Added an exact-identical-restock regression fixture and durable-revision reload assertion.

The second read-only audit found the identical-restock case before the revision fix and requested changes. That finding is now covered by `BankVault.revision`, revision-checked withdrawal mutation, and an exact-identical-restock test. A later audit found that recovery could abort a prepared withdrawal while its transaction was in flight, then allow the bank removal to commit against an already-aborted journal record. Remediation and the read-only follow-up audit are recorded below.

- Added bounded, per-journal striped request locks and held the withdrawal lock from initial replay classification through prepare, bank mutation, delivery, and commit/abort. Recovery acquires the same request lock and rereads the journal record after acquiring it, so a stale pending-list snapshot cannot abort a now-committed request.
- Added a deterministic two-thread test that pauses withdrawal after `PREPARED` and before the repository transaction, verifies recovery is blocked on that request lock, and then asserts `COMMITTED`, zero unresolved recoveries, and an empty vault.
- A fresh read-only audit approved the concurrency remediation. Its explicit residual is that locks do not coordinate two concurrent journal instances/processes sharing one bank directory; approval assumes the current production single-repository lifecycle.
- **Post-remediation local verification:** `BankRepositoryTest`, `BankVaultTest`, `RoleSerdeTest`, and `DurableOperationJournalTest` focused runs passed. The full `.\gradlew.bat test --rerun-tasks --console=plain` run passed with 323 tests and 0 failures/errors. Parity tooling passed (47 Python tests), truth gate passed, evidence gate passed, fixture harness exited 0 with 24 fixtures but reports target-runtime certification blockers, and `git diff --check` reported no whitespace errors beyond repository line-ending notices.

## Related established patterns

- Microsoft's [Saga design guidance](https://learn.microsoft.com/en-us/azure/architecture/patterns/saga) describes local transactions, compensating actions, retryable idempotent steps, and versioned records for handling cross-store failures. StoryNPCs uses the journal as the operation state and the vault revision as evidence that the bank record has not changed since intent capture.
- Stripe's [idempotent request guidance](https://docs.stripe.com/api/idempotent_requests) binds retries to an idempotency key and the original request parameters. StoryNPCs similarly binds its request ID to player, tab, slot, item, and count, then refuses changed or pending requests instead of executing a second withdrawal.
