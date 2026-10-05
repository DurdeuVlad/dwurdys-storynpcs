# P1-1 closeout — typed canonical mutation boundary

Status: `DONE-LOCAL` — every mutation family listed in the acceptance criteria routes through a typed request boundary: definition create/replace/delete (`MutationRequest` + canonical payload fingerprints), quest start/progress/completion, faction standing, follower state/formation, template replace/delete, bank vault access and operations (`BankAccessMutationRequest`/`BankOperationRequest`), trade execution (`TradeExecutionRequest`), and player-scoped progression actions (`PlayerProgressionActionRequest` — transport unlock, mail read/delete, and dialogue node-visit recording).

## Residuals closed 2026-10-05

- **Mutating `getQuestState` read on dialogue condition evaluation — FIXED.** `evalCondition`'s `QUEST_STATUS` check auto-vivified a `QuestProgressState` into the persisted map for every unstarted-quest evaluation. `PlayerProgression.peekQuestState` now serves read-only paths with a detached default; the mutation paths keep `getQuestState`. Regression test: `DialogueAvailabilityReloadTest.questStatusConditionDoesNotPersistUnstartedQuestState`.
- **Rule `ADJUST_FACTION` / `RuleContext.adjustFaction` — already enveloped; earlier note corrected.** The only `RuleActionHandler.onAdjustFaction` implementation (`WitnessProtectionManager`) calls `adjustFactionReputation`, which delegates to `adjustFactionPoints` → typed `FactionProgressionMutationRequest` (system actor). The batched `recordedFactionAdjustments` map is a read-model for rule summaries, not a second write path.
- **Retained, documented:** system-internal progression writes (`deliverMail`, faction repair, pending-completion cleanup) stay inside the service's own critical sections; `PlayerProgressionActionRequest` carries no expected-revision/replay machinery — flag-set writes are naturally idempotent ("duplicate requests are safe" holds without envelopes); non-journaled world side-effects of `GIVE_ITEM`/`EXECUTE_COMMAND` are world effects, not progression mutations, and remain recorded as accepted risk.

## Dialogue node-visit typed migration (current)

- `PlayerProgression.recordDialogueNodeVisit` was the last *session-driven*
  progression write issued by raw field mutation inside
  `StoryNpcsApplicationService` (`startDialogue` and `advanceAlongEdge`), and —
  worse — it mutated cached state with **no durable save**, so visits only
  survived if a later periodic `saveAll` ran.
- Added `recordDialogueNodeVisit(PlayerProgressionActionRequest, NamespacedId, String)`:
  the same authorized player-scoped boundary used by transport unlock and mail
  read/delete. Dialogue navigation submits `"dialogue"`-actor requests bound to
  the visiting player (`playerUuid == actorId`), so visit recording honors the
  same actor/subject policy as every other progression mutation and every
  rejection carries a machine-readable `AuthorizationDecision` code. The public
  boundary additionally rejects node ids absent from the named dialogue
  (`DIALOGUE_NODE_NOT_FOUND`) so privileged callers cannot persist arbitrary keys.
- Visits are now keyed per dialogue (`dialogueId#nodeId` via scoped
  `PlayerProgression` overloads): bare node ids collide across graphs — every
  dialogue conventionally has a `"start"` entry — and nothing consumed the flat
  set yet, so the durable format was scoped before it gained readers. Legacy
  bare-node-id entries remain readable through the unscoped accessors.
- `recordDialogueNodeVisitInternal(...)` writes through only when the node is
  newly visited and persists via `saveProgression(...)` in the same critical
  section: first visits are durable, repeat navigation is a side-effect-free
  `applied=false` no-op, and session paths reuse the `PlayerProgression`
  instance already fetched for the view build.
- Consistent with the rest of the `PlayerProgressionActionRequest` family, this
  path emits no `CanonicalMutationEvent` and `applied=true` means in-memory
  commit (a swallowed `IOException` during save is reported on stderr only) —
  thinner than the definition/faction envelopes, proportionate for a flag-set
  write, recorded here as a known envelope gap.
- `DialogueVisitMutationTest` covers: root-node visit on `startDialogue`,
  target-node visit on `chooseDialogueOption`, durability across a repository
  reload, per-dialogue scoping, idempotent repeats, `DIALOGUE_NODE_NOT_FOUND`
  rejection, and denial diagnostics for script actors, cross-subject dialogue
  actors, and under-privileged command actors.

## Stale-gap corrections (current tree vs. older wording)

- **Faction via rules/combat**: `adjustFactionReputation(...)` (used by
  `WitnessProtectionManager` and `RuleContext`/`AdjustFactionAction` handlers)
  now delegates to `adjustFactionPoints(...)`, which submits a typed
  `FactionProgressionMutationRequest` with system actor. The earlier note that
  these paths were deliberately unmigrated is obsolete.
- **Bank/trade**: `BankOperationRequest`, `BankAccessMutationRequest`, and
  `TradeExecutionRequest` are typed actor-scoped envelopes evaluated by
  `AuthorizationPolicy` — not bare request-ID journals. The durable journal is
  the recovery substrate *behind* the typed request, not a substitute for it.
- **Templates**: `template.replace`/`template.delete` route through
  `executeCanonicalMutation` with capability checks, revision binding, and
  payload fingerprints (merged separately).
- **Remaining honest residuals** are tracked in *Explicit residual risks* below:
  non-journaled world side-effects of `GIVE_ITEM`/`EXECUTE_COMMAND` dialogue
  actions, and in-process replay-cache bounds.

## Faction progression typed migration (2026-09-25)

- Added `FactionProgressionMutationRequest` (SET/ADJUST, player-scoped, expected-revision, request-ID) and `AuthorizationPolicy.evaluate(FactionProgressionMutationRequest)` — same actor semantics as the quest progression request: player/dialogue actors are self-only, command actors need permission level 2 to touch another player, script actors are always denied, system is always allowed.
- Added `PlayerProgression.factionRevision`, a single per-player revision clock mirroring `questRevision`, included in `copy()`/`restoreFrom()`.
- Added `StoryNpcsApplicationService.mutateFactionProgression(...)`: per-request-ID and per-player lock arrays (mirroring the quest mutation lock pattern), a bounded in-process replay cache (`completedFactionMutations`, `BoundedReplayCache`) that rejects request-ID reuse with a changed payload as `REQUEST_PAYLOAD_MISMATCH` and replays an unchanged payload as a non-re-applied duplicate, stale-revision rejection before any mutation, atomic commit with snapshot/restore rollback on a durable-write failure, and a single `CanonicalMutationEvent` plus `FactionReputationChangeEvent` published only on the non-replayed path (a cache-hit replay must not re-publish either event).
- Migrated three adapter call sites off the untyped `setFactionPoints`/`adjustFactionPoints` service methods onto this typed request, each reading the player's current faction revision from the `currentFactionProgressionRevision(...)` accessor before building the request (the same pattern the existing `startQuest` command handler already uses for quest progression):
  - Command adapters `/storynpcs faction set` and `/storynpcs faction adjust`.
  - The dialogue-graph `ADJUST_FACTION` action (`executeAction(...)`, `DialogueAction.Type.ADJUST_FACTION`). The request carries an unbounded `amount`; the domain clamps the resulting standing to [-100000, 100000], preserving the previously tolerated huge authored delta rather than turning it into an uncaught exception that would fail the dialogue turn.
- Found and deliberately did **not** migrate two further untyped faction-mutation paths, so P1-1's "faction mutations route through the dispatcher" criterion is still not fully met:
  - `RuleContext.adjustFaction(...)` / `AdjustFactionAction` (the YAML `BehaviorRule` action `ADJUST_FACTION`) — this records into a batched `recordedFactionAdjustments` map and an `actionHandler.onAdjustFaction(...)` callback rather than mutating progression directly inline; migrating it needs its own scoped follow-up to trace where that callback ultimately commits.
  - `adjustFactionReputation(...)`, used by `WitnessProtectionManager` for real-time combat-driven reputation loss — a hot path called from combat AI ticks; adding the typed request's per-request-ID replay cache and lock pattern there needs a dedicated performance-aware review rather than a drive-by swap.
- The untyped `setFactionPoints`/`adjustFactionPoints` methods are kept for the one remaining internal caller inside the typed boundary already: the quest-completion reward commit (`commitQuestCompletion`), which is already inside its own atomic, locked, revisioned transaction and would be redundant (and lock-order-risky) to route through a second typed envelope. That internal commit now also advances `factionRevision` once per completion commit, so a client's cached faction revision cannot silently go stale after a quest reward changes their points.
- `FactionProgressionMutationTest` covers typed replay-safety across an in-process restart, adjust accumulation/clamping at the [-100000, 100000] domain bound, stale-revision rejection without mutating state, request-ID reuse with a changed payload, unknown-faction rejection, player-actor self-only enforcement, command-actor permission enforcement (both denied and allowed), script-actor denial, and the revision accessor.

## Delivered

- Added `MutationRequest` with operation, actor type, capability, target ID, expected revision, and request ID.
- Added `CanonicalMutationResult` with applied/duplicate status, revision, diagnostics, event names, and recovery outcome.
- Added typed mutate/replace/delete paths for NPC, dialogue, quest, and faction definitions.
- Made canonical check/execute/cache atomic under an application-service lock; request-ID reuse is rejected, duplicate replay is side-effect-free, and stale revisions fail before mutation.
- Scoped revision clocks by definition type and preserved tombstone revisions across delete/recreate.
- Added `CanonicalMutationEvent`, emitted once for each non-replayed mutation attempt.
- Migrated command and network saves/deletes to typed requests with client-captured expected revisions and request IDs.
- Added typed create operations for NPC, dialogue, quest, and faction definitions; caller-authored dialogue graphs can be created atomically without first exposing a scaffold graph.
- Bound typed NPC/dialogue/quest/faction create and replace cache entries to SHA-256 fingerprints of canonical JSON snapshots; typed delete paths bind deterministic target intent. A reused request ID with changed typed payload returns `REQUEST_PAYLOAD_MISMATCH`, while opaque `Consumer` mutations without a verifiable payload reject duplicate replay as `IDEMPOTENCY_PAYLOAD_UNBOUND` instead of returning a false success.
- Hardened payload fingerprinting against malformed UTF-16 aliases; preserved explicit NPC skin source across JSON/YAML round-trips; bound editor save/delete request IDs to exact payload intent; retire IDs on matching acknowledged success/rejection while preserving IDs for unanswered retries; ignore stale replies; prevent duplicate NPC entity refresh; detach all four callback-mutation commit objects; reject callback reentrancy without publishing an event; and isolate cached receipt diagnostics. Trade-open usage overlays now mutate a detached trader projection only. Details and regression evidence are in `docs/parity/reviews/2026-09-23-p1-1-adapter-boundary-adversarial.md`.
- Migrated NPC/quest/faction edits, create/delete handlers, quickstart scaffolding, and NPC/path/dialogue wand mutations to typed requests. Player-originated wand requests carry the player UUID and level-2 permission proof; command requests distinguish players from console/command sources and capture the current definition revision.
- The quest-completion command now maps the service's `COMPLETED`, `ALREADY_COMPLETED`, `REJECTED`, and `FAILED` outcomes to accurate success/failure feedback instead of reporting success for every returned result; the focused selector is registered under the command parity fixture. This does not yet envelope quest progression with typed request identity/revision.
- Definition-specific service entry points reject operation/capability families that do not match the target domain before applying any mutation.
- The mutation dispatcher now enforces the exact operation/action and capability pairing; the public generic dispatcher was removed. NPC, dialogue, quest, and faction mutate callbacks cannot redirect writes by changing the target ID.
- Path clearing rechecks operator permission at use time. Trader/banker role command receipts report the configured role names rather than the inaccurate string `default` and no longer read the stale pre-mutation NPC object.
- Added canonical `deleteUnreferencedDialogue(...)` compensation for dialogue-wand and quickstart create-then-assign/scaffold failures. It checks NPC references and deletes under the canonical mutation lock plus service monitor; legacy NPC create/save entry points now share the canonical lock, and NPC creation validates its cross-references.
- Removed live-definition mutation from NPC field setters, `NpcPathItem`, and rule edits; these now mutate detached copies through the service and report rejection diagnostics.
- Added `BankWithdrawalOperationResult` and `withdrawEntireStackFromBank(...)`; bank packet adapters now pass only player/tab/slot and render the service result instead of reading vault state to choose an amount.
- Added service-owned delivery compensation in `withdrawAndDeliverFromBank(...)`: an unmaterializable stored item is re-deposited through the canonical service with its original slot, tag, and count; a full inventory remains a successful drop rather than a failed withdrawal.
- Preserved `SLOT_EMPTY`, `TAB_LOCKED`, `INVALID_TAB`, and `INVALID_SLOT` diagnostics instead of translating unchanged mutations into `DURABLE_COMMIT_FAILED`; component-bearing item parse failures now fail closed.
- Added request-ID propagation for whole-stack withdrawals with a subject-bound durable journal; committed retries return `REPLAYED` without creating a second item, while prepared operations fail closed pending recovery.
- Added `QuestProgressionMutationRequest` and `mutateQuestProgression(...)` for typed quest start/progress. The request carries actor/subject identity, operation, quest, objective/amount, expected per-player quest revision, and request ID; the service enforces self/admin authorization, rejects stale revisions and changed-payload request-ID reuse, writes progression before publishing success, and restores the cached state when persistence fails. `/storynpcs quest start` now submits this typed request.
- Persisted `PlayerProgression.questRevision` with backward-compatible revision-zero loading for older records. A successful quest request retried after restart is rejected as stale (no second increment); an identical retry during the same process returns the cached duplicate receipt.
- Serialized quest turn-in per player and moved completion event publication after the state lock. A 24-caller concurrent regression test proves an in-process faction reward is applied once; this does not prove crash-safe reward fan-out.
- Completion now advances the persisted quest revision in the same write as `COMPLETED`, so a delayed repeatable-quest start at the pre-completion revision is rejected as stale. Completion write failures restore a full progression snapshot, including its prior revision.
- Quest objective validation now shares `QuestProgressState.MAX_OBJECTIVE_COUNT` with the counter clamp and rejects impossible `requiredCount` values above 100,000.
- Per-player striped notification queues deliver each canonical/objective/completion event group in order. Reentrant listeners can submit mutations, but their events are queued after the current group; automatic quest completion is persisted before the group is dispatched.
- Objective mutations that make a quest eligible persist a `PendingQuestCompletion` in the same progression write as the count/revision. It binds request ID and payload fingerprint to the quest-local state revision; exact-request retries after repository-cache reload can resume it, and later quest-state changes invalidate the prior intent. Completion clears the intent atomically with the completed state.
- Pending completion replay rechecks in-progress status, saved quest-state revision, and every current objective threshold before turn-in. Notification dispatch isolates publisher failures so later events can proceed; VM/thread termination resets dispatcher state and requeues the undelivered tail.
- Added `QuestCompletionMutationRequest` and `completeQuest(request)`: typed actor/subject identity, expected per-player quest revision, and request ID. Authorization failures are `REJECTED` with machine-readable codes; identical requests return the cached receipt without re-delivering rewards; `FAILED` outcomes are not cached so a same-request retry re-attempts through the durable pending record; the durable completion intent now binds the caller's request ID and payload fingerprint instead of an anonymous UUID. Completion of an already-`COMPLETED` quest is an idempotent `ALREADY_COMPLETED` regardless of expected revision, preserving the concurrent-turn-in contract.
- Added `FactionProgressionMutationRequest` and `mutateFactionProgression(request)` for typed SET/ADJUST standing changes: persisted `PlayerProgression.factionRevision`, stale-revision rejection, request-ID payload binding, snapshot-restore on commit failure, and ordered canonical + `FactionReputationChangeEvent` publication. Quest faction rewards inside a completion commit advance `factionRevision` atomically with the completed state.
- Added `FollowerStateMutationRequest` and `mutateFollowerState(request, role)`: typed actor/owner identity, request-ID replay, `FOLLOWER_NOT_OWNED`/`FOLLOWER_ROLE_UNAVAILABLE` diagnostics, and atomic formation field updates under the role monitor.
- Dialogue actions now submit `"dialogue"`-actor typed requests bound to the interacting player for `START_QUEST`, `ADVANCE_QUEST`, `COMPLETE_QUEST`, and `ADJUST_FACTION` instead of anonymous system mutations.
- `/storynpcs faction points set|add`, `/storynpcs quest complete`, follower state/formation commands, and the shift-click follower cycle now submit typed requests carrying command actor identity and level-2 permission proof. The former `setFactionPoints`/`adjustFactionPoints`/`setFollowerState`/`setFollowerFormation`/`completeQuest(player, quest)` entry points remain as system-actor compatibility delegates over the same canonical path.
- New `CanonicalRuntimeMutationTest` (13 tests) covers apply/replay/stale/auth/commit-failure/compat behavior across faction progression, quest completion, and follower state.

## Verification

- Latest exact-source combined run: `BUILD SUCCESSFUL`; Gradle reported 385 cases across 51 suites; 86 selectors, 20 fixtures observed, 5 declared blockers, 0 mapped failures; CI test-execution validation `PASS`, while fixture and target parity remain `BLOCKED`.
- The latest full Gradle run reports 51 suites, 385 tests, 0 failures, 0 errors, and 1 skipped. The Python parity-tool suite passed 108 tests and the truth gate passed.
- Focused payload/service run — `BUILD SUCCESSFUL`:
  `NetworkPayloadsTest`, `StoryNpcsApplicationServiceTest`.
- Service tests cover detached rollback, replay, stale revisions, request-ID reuse, unknown targets, type-scoped revisions, delete tombstones, typed delete replay, and canonical event publication.
- Payload-fingerprint tests cover canonical object-key order, order-sensitive arrays, operation domain separation, changed-payload rejection across all definition creates and replacements, and durable NPC replacement state.
- Payload tests cover revision/request-ID codec round trips.
- Parity infrastructure: 108 tests pass after probe-report hardening and fixture-catalog reconciliation; fixture harness validates 25 fixtures, 15 operation families, and 22 domains; target parity remains `BLOCKED` until target runtime evidence exists.
- The 2026-09-22 architecture review failed the complete P1-1 acceptance lens; this adapter slice does not supersede that finding. The 2026-09-23 adversarial audit and remediation status are recorded in `docs/parity/reviews/2026-09-23-p1-1-adapter-boundary-adversarial.md`. The bounded payload-binding/acknowledged-failure-retry slice received independent read-only approval from Planck; this is not whole-issue approval, merge approval, or runtime verification.
- Narrow bank-boundary verification: `BankRepositoryTest` and `NetworkPayloadsTest` — `BUILD SUCCESSFUL`; independent follow-up audit requested changes and did not approve the slice.
- Follow-up read-only audit: changes requested; findings and remaining durable-receipt/crash risks are recorded in `docs/parity/reviews/2026-09-22-bank-withdrawal-followup.md`.
- Recovery follow-up: prepared withdrawals now have login-time compound-subject discovery and conservative exact-stack-plus-revision reconciliation; ten recovery fixtures cover untouched, removed, changed/restocked, identical-restocked, cross-player, malformed and legacy intents, legacy vault revision zero, and journal abort-write failure. A separate concurrent-change fixture verifies stale revisions are rejected before item removal.
- 2026-09-25 typed-runtime slice: full `gradlew test` — `BUILD SUCCESSFUL`, 52 suites / 455 tests / 0 failures (includes `CanonicalRuntimeMutationTest`, 13 tests). No live Minecraft verification performed or claimed.

## Explicit residual risks

- Opaque `Consumer` mutation methods do not accept a canonical payload intent. Their first request may execute, but any same-ID replay is rejected with `IDEMPOTENCY_PAYLOAD_UNBOUND`; adapters that need successful replay must migrate to typed patch/request DTOs or supply a verifiable intent. Typed create/replace requests use canonical JSON snapshots and reject changed-body key reuse; deletes bind the operation and target. This follows the local bank/trade journal's serialized-intent comparison and Stripe's parameter-bound idempotency behavior ([Stripe idempotent requests](https://docs.stripe.com/api/idempotent_requests)).
- Expected revisions follow the established optimistic-concurrency pattern, but must be captured from the same read that supplies any adapter-side decision (such as an indexed removal). Kubernetes uses `resourceVersion` to reject stale writes with a conflict; StoryNPCs should preserve that read-version-write relationship as remaining adapters migrate ([Kubernetes API concurrency](https://kubernetes.io/docs/reference/using-api/api-concepts/#updates-to-existing-resources)).
- Public compatibility methods remain callable without caller-supplied request envelopes; they now construct system-actor typed requests internally, so adapters cannot bypass authorization, replay, or revision checks — but callers cannot supply their own expected revisions or request IDs through those signatures. Bank tab unlock/deposit/withdraw and trade execution use request-ID durable journals rather than `MutationRequest` envelopes (tracked under P2-3/P7), and no typed template/tool operation family exists yet (P8-1 scope). These remain part of P1-1 acceptance, not certified coverage.
- Ordinary quest start/progress, faction, completion, and follower receipts remain cached in-process (bounded at 4,096); successful old-revision requests still return stale after restart rather than restoring their original receipt. Rejected no-op requests are not durably remembered. Completion-pending requests are a durable exception, and XP/item rewards now use durable mark-before-deliver, but no live process-kill/inventory-delivery fixture exists under the no-live-MC constraint.
- `PlayerProgression.recordDialogueNodeVisit` is now routed through the typed `PlayerProgressionActionRequest` boundary and persists on first visit (see *Dialogue node-visit typed migration* above). Dialogue `GIVE_ITEM`/`EXECUTE_COMMAND` action effects remain non-journaled world side-effects inside committed operations.
- Historical independent follow-up findings for stale completion revision and event order were fixed. The 2026-09-24 review additionally found stale pending-retry eligibility, lost pending state on restart, XP/item duplication risk, and queue wedge on publisher failure. Eligibility, pending-intent durability, and queue recovery have now been remediated with regression tests; XP/item delivery remains open. See `docs/parity/reviews/2026-09-24-p1-1-completion-retry-followup.md`. No target-runtime behavior is claimed.
- The same review found the cross-reference validator accepts `requiredCount=100001` although `QuestProgressState` clamps progress at 100,000. P2-1 now requires rejecting objectives above the supported state maximum; a validator boundary test is required.
- Revision advancement, the objective-count bound, and reentrant notification ordering have now been implemented and regression-tested; reward duplication after progression-save failure remains open under P2-3.
- Definition-file deletion currently logs a warning after registry removal when the disk delete fails; restart resurrection and durable recovery are tracked under persistence issues.
- List-mode quest/faction editor synchronization and full payload/session/version policy remain part of P1-3.
