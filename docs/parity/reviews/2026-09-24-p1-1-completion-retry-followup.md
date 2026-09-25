# P1-1 completion retry follow-up — independent adversarial review

Status: `CHANGES-REQUESTED` — read-only follow-up review of the quest completion and notification remediation. The review is scoped to the current working-tree snapshot; it does not approve the wider P1-1 slice or merge readiness.

## Findings

### P1 — Pending replay can complete a quest after its objective is no longer eligible

`StoryNpcsApplicationService.applyQuestProgressionMutation(...)` recognizes an in-process cached completion-pending request and returns `shouldComplete=true` without rechecking the current quest status, objective counts, or quest cycle. `completeQuestUnderLock(...)` only refuses a quest already marked completed. A later mutation can reduce or reset objective progress before the original request is replayed; once reward validation is repaired, replay can turn in at an ineligible count and award rewards.

Required correction: completion retry must validate the currently persisted quest state and full objective threshold under the same per-player lock before applying rewards. Add a regression that fails initial reward validation at threshold, changes the objective state, fixes the invalid reward, replays the original request, and proves no completion/reward occurs.

### P1 — XP/item rewards can be duplicated after completion persistence fails

`completeQuestUnderLock(...)` grants XP and inserts/drops items before persisting `COMPLETED`. A progression save failure restores the progression snapshot but cannot retract those external player-side effects. Retrying completion can grant them a second time. The in-process faction-only retry test does not cover these reward types.

Required correction: use a durable reward intent/outbox with idempotent recipient delivery or another recovery protocol that can prove no loss and no duplication across a process crash. Until proven, keep this explicitly open under P2-3 and do not claim crash-safe reward fan-out.

### P2 — Pending completion recovery is process-local

`CompletedQuestMutation.completionPending` is stored in the bounded in-memory request cache. After a failed completion write and server restart, the old request encounters the already-advanced persisted quest revision and is rejected as stale; no durable pending completion record triggers recovery.

Required correction: persist a completion intent/recovery state or define and test a supported durable recovery operation that discovers threshold-complete in-progress quests after restart. Test restart between objective commit and completion.

### P2 — An escaping publisher failure can leave the per-player queue marked dispatching

The event publisher currently isolates `Exception` and `AssertionError`, but other `Error` values or a runtime failure from an overridden publisher can escape `drainQuestEvents(...)`. The queue's `dispatching` flag then remains true and later notifications for that stripe are enqueued without a drainer.

Required correction: make queue draining restore a recoverable dispatch state on every exit, define behavior for partially delivered groups, and test a failure escaping publication followed by a later event group. Fatal VM errors need not be swallowed, but must not leave silently stuck state if execution continues.

## Verified evidence

- Exact-source CI-mode runner on 2026-09-24: 382 JUnit cases / 51 suites, 83 mapped selectors, 20 observed fixtures, 5 declared feature blockers, zero mapped failures; CI test-execution validation `PASS`, target parity `BLOCKED`.
- Full `\.\gradlew.bat test --rerun-tasks --console=plain`: `BUILD SUCCESSFUL`.
- Python parity tooling: 108 tests passed; documentation truth gate passed.
- Focused quest progression suite passed after adding receipt revision, listener `AssertionError`, and in-process faction reward retry regressions.
- The separate exact-source fixture report does not run CustomNPCs inside Minecraft and does not certify target-runtime behavior.

## Scope and residual risk

The review made no source edits. Implementation follow-up has since addressed stale eligibility, process-local pending state, and notification queue recovery. Ordinary request receipts remain process-local. XP/item side effects remain outside the progression transaction, and the five unmapped feature fixtures plus all 25 target-runtime probes prevent certification from passing.

## Remediation follow-up

- Stale eligibility: `PendingQuestCompletion` is persisted in `PlayerProgression` with request ID, SHA-256 payload fingerprint, quest ID, and quest-local state revision. Every accepted mutation of that quest advances the local revision and replaces/removes its older pending intent. Retry checks current `IN_PROGRESS` status and every objective threshold. Regressions: `pendingCompletionReplayCannotCompleteAfterObjectiveProgressIsWithdrawn` and `pendingCompletionReplayAfterRestartUsesDurableIntentWithoutRepeatingProgress`.
- Restart recovery: after repository-cache reload, the exact original request ID/fingerprint locates its pending intent before the stale global revision check. It resumes completion without applying objective progress a second time. Successful completion clears the pending record in the same durable write as the completed state.
- Publisher failure: queue dispatch catches nonfatal publisher failures per event, logs/skips that notification, and continues the remainder of the group and later groups. VM/thread termination resets dispatch state and requeues the undelivered tail. Regression: `publisherFailureDoesNotStrandLaterQuestNotificationGroups`.
- XP/item reward duplication remains unresolved: Minecraft player-side effects and the progression JSON store do not share a transaction or recipient idempotency key. Faction-only retry coverage does not close that gap.
- Exact-source verification and independent review of this remediation are pending; this note is not a merge approval.

## Design research

- FTB Quests invokes `markRewardAsClaimed(...)` before `reward.claim(...)`. This prevents duplicate claims but may leave the claim marked if delivery fails: [claim flow](https://github.com/FTBTeam/FTB-Quests/blob/main/common/src/main/java/dev/ftb/mods/ftbquests/quest/TeamData.java#L2740), [claim marker](https://github.com/FTBTeam/FTB-Quests/blob/main/common/src/main/java/dev/ftb/mods/ftbquests/quest/TeamData.java#L2367).
- AWS transactional-outbox guidance states consumers still need idempotency for duplicate delivery, and cross-store operations need explicit recovery semantics ([AWS Prescriptive Guidance](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)).
- NeoForge 1.21.1 `SavedData` requires marking changed records dirty before the game saves them ([NeoForge Saved Data docs](https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/)). StoryNPCs stores this intent in its existing atomic `PlayerProgression` file instead, allowing objective progress and pending request identity to share a commit; neither option alone makes Minecraft inventory/XP changes atomic with the mod's file.
