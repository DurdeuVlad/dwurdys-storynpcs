# P5 — Dialogue + quest milestone progress (P5-1..P5-5)

Status: local implementation of P5-1/P5-2/P5-4/P5-5 acceptance surfaces
complete; P5-3 editor floors remain deferred to P10-1/P10-2.

## P5-1 — Authoritative dialogue runtime

- `DialogueGraphValidator`: entry existence, reachability, dangling edges (errors); cycle diagnosis (warnings — legal in directed graphs).
- Deep edge semantics: per-type condition validation (`QUEST_STATUS` values + quest ref, `FACTION_STANDING` values + faction ref, `FACTION_POINTS` operator/numeric/faction ref, `HAS_ITEM` item id, `HAS_PERMISSION` level or namespaced node) and per-type action validation (quest/faction refs, `GIVE_ITEM` item id + positive count, `EXECUTE_COMMAND` non-blank payload). Author-time malformed payloads can no longer survive to runtime.
- New events: `DialogueClosedEvent` (4 reasons), `DialogueChoiceRejectedEvent`, `DialogueReloadedEvent`.
- `docs/parity/P5-1-DIALOGUE-MANIFEST.md` — every field mapped to YAML/service/UI/editor/API/script/event/persistence or N/A.

## P5-2 — Exactly-once dialogue choice

- `DialogueChoiceProtocol`: opaque random tokens binding session/player/dialogue/node/choice/graphRevision/expiry.
- Accept = atomic consume; deterministic single winner; rejects: UNKNOWN/WRONG_SESSION/WRONG_PLAYER/SESSION_CLOSED/EXPIRED/STALE_REVISION/ALREADY_CONSUMED. No index-only path exists.
- `revokeSession` drops pending tokens on close/reload.
- Service-level fixture proves a replayed choice cannot re-apply effects through the full canonical path (`StoryNpcsApplicationServiceTest`).
- Reconnect/timeout policy is explicit: 60s token expiry + close-on-logout (`WorldLifecycleHandler`); sessions do not resume.

## P5-3 — Authoring workflow

- `DialogueGraphValidator` runs in production: `YamlDefinitionLoader.loadDialogue` rejects cyclic/unreachable/dangling graphs at load; `StoryNpcsApplicationService.saveDialogue` re-validates before cross-reference checks and persistence.
- The standalone `DialogueAuthoringModel` duplicate was removed — `DialogueEditorScreenModel` remains the single editor mutation path.
- Still open (owned by P10-1/P10-2): undo/redo, search, and minimum-viewport floors for the editor.

## P5-4 — Quest definitions

- `RepeatType` six target modes: NORMAL, REPEATABLE, DAILY, WEEKLY, RESET, INSTANT + `autoCompletes()`; `ONCE` YAML alias → NORMAL.
- `RepeatSchedule`: explicit timezone/day-start/week-start policy — deterministic DAILY/WEEKLY boundaries, consumed by the canonical START gate via the durable `lastCompletedEpochMillis` stamp on `QuestProgressState`.
- RESET has a full issuance path: canonical `QuestProgressionMutationRequest.reset(...)`, `quest.reset` capability registration, `/storynpcs quest reset` (op level 2), and the START gate honoring reset semantics.
- `QuestDependencyValidator`: missing-prereq diagnostics (both sides named), cycle detection, deterministic topological `completionOrder`.
- `QuestObjective.customType`: namespaced handler id = typed extension contract for CUSTOM.
- `Quest.category` present; fixtures cover all objective families and all six repeat modes.

## P5-5 — Completion, team, mail

- `QuestMail` + `QuestMailStore`: durable indexed mail records; `pendingFor` sorted; `claim` exactly-once with durable claimed mark; survives reopen.
- `RewardOverflowPolicy`: MAIL default — silent dropping is never the default; full-inventory ITEM remainder mails the uninserted remainder only.
- COMMAND rewards are deliverable: pre-validated (blank target → `COMMAND_REWARD_TARGET_MISSING`; offline player → `PLAYER_OFFLINE`), executed once under the durable delivered mark. A failed command is marked *attempted* durably, reported (`REWARD_EXECUTION_FAILED`), and never re-executed — the non-atomicity report is explicit.
- `TeamProgression` + `TeamProgressionStore` (IndexedRecordStore, durable across reopen): membership, owner, per-quest claim slot (`claimedBy`), invite tracking.
- `PlayerProgression.teamId` binds a player to at most one team.
- Canonical ops through `PlayerProgressionActionRequest` + `runProgressionAction` (revisioned, request-id replay-safe, non-terminal failures not memoized): `team.create`, `team.invite`, `team.join`, `team.leave`, `team.owner`; `/storynpcs team ...` command surface.
- Invite-gated joins: uninvited `team.join` → `NOT_INVITED`; command actor level ≥2 bypasses the invite gate (admin add).
- Ownership: non-owner transfer → `NOT_OWNER`; non-member target → `NOT_A_MEMBER`; owner leaving promotes the first remaining member; last member leaving disbands.
- Shared progress: post-commit propagation mirrors every canonical quest state into the team's `quests` map; first completer records `claimedBy` — a later teammate completion cannot steal the claim.

## Explicit limits

- Reconnect/resume policy is close-only (no session resume).
- Mail claiming has no player-facing UI yet — delivery runs through `deliverQuestMail` on login/postman paths.
- P5-3 editor floors (undo/redo, search, viewport) remain open under P10-1/P10-2.
- COMMAND reward execution is verified at service level (headless marks delivered); the live command-dispatch path shares the dialogue `EXECUTE_COMMAND` sanitizer and is exercised in the GameTest environment only.

## Verification

`./gradlew test`: 1,222 tests, 0 failures, 1 skipped. `git diff --check` clean. No live MC testing for this slice.
