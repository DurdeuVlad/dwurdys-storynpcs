# P5 — Dialogue + quest milestone progress (P5-1..P5-5)

Status: `IN-PROGRESS` for all five issues (local implementation).

## P5-1 — Authoritative dialogue runtime

- `DialogueGraphValidator`: entry existence, reachability, dangling edges (errors); cycle diagnosis (warnings — legal in directed graphs).
- New events: `DialogueClosedEvent` (4 reasons), `DialogueChoiceRejectedEvent`, `DialogueReloadedEvent`.
- `docs/parity/P5-1-DIALOGUE-MANIFEST.md` — every field mapped to YAML/service/UI/editor/API/script/event/persistence or N/A.

## P5-2 — Exactly-once dialogue choice

- `DialogueChoiceProtocol`: opaque random tokens binding session/player/dialogue/node/choice/graphRevision/expiry.
- Accept = atomic consume; deterministic single winner; rejects: UNKNOWN/WRONG_SESSION/WRONG_PLAYER/SESSION_CLOSED/EXPIRED/STALE_REVISION/ALREADY_CONSUMED. No index-only path exists.
- `revokeSession` drops pending tokens on close/reload.

## P5-3 — Authoring workflow

- `DialogueGraphValidator` now runs in production: `YamlDefinitionLoader.loadDialogue` rejects cyclic/unreachable/dangling graphs at load; `StoryNpcsApplicationService.saveDialogue` re-validates before cross-reference checks and persistence.
- The standalone `DialogueAuthoringModel` duplicate was removed — `DialogueEditorScreenModel` remains the single editor mutation path; undo/redo/search/viewport floors are still open (see P10-1).

## P5-4 — Quest definitions

- `RepeatType` now six target modes: NORMAL, REPEATABLE, DAILY, WEEKLY, RESET, INSTANT + `autoCompletes()`; `ONCE` YAML alias → NORMAL.
- `RepeatSchedule`: explicit timezone/day-start/week-start policy — deterministic DAILY/WEEKLY boundaries, consumed by the canonical START gate via the durable `lastCompletedEpochMillis` stamp on `QuestProgressState`.
- `QuestDependencyValidator`: missing-prereq diagnostics (both sides named), cycle detection, deterministic topological `completionOrder`.
- `QuestObjective.customType`: namespaced handler id = typed extension contract for CUSTOM.

## P5-5 — Completion, team, mail

- `QuestMail` + `QuestMailStore`: durable indexed mail records; `pendingFor` sorted; `claim` exactly-once with durable claimed mark; survives reopen.
- `TeamProgression`: team-keyed shared quest states, member set, owner, shared revision.
- `RewardOverflowPolicy`: MAIL default — silent dropping is never the default.

## Explicit limits

- Duplicate-token replay rejection (a second accept of the same token) has no dedicated fixture; reconnect/resume policy is close-only.
- Mail claiming has no player-facing UI yet — delivery runs through `deliverQuestMail` on login/postman paths.
- `TeamProgression` exists as a domain contract but has no production callers — team/shared progress remains open.
- RESET has no issuance path yet: re-start is rejected once completed — an
  explicit reset operation (clearing the quest state) is still unimplemented,
  so RESET currently behaves as non-repeatable rather than reset-gated.

## Verification

`./gradlew test`: 68 suites, 563 tests, 0 failures. `git diff --check` clean. No live MC testing.
