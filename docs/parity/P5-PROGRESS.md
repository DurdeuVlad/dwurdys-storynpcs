# P5 — Dialogue + quest milestone progress (P5-1..P5-5)

Status: local implementation of P5-1/P5-2/P5-4/P5-5 acceptance surfaces
complete; P5-3 authoring/editor floors delivered in the P10-1 integration work
and closed via this branch's PR.

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

- `DialogueGraphValidator` runs in production: `YamlDefinitionLoader.loadDialogue` rejects cyclic/unreachable/dangling graphs at load; `StoryNpcsApplicationService.saveDialogue` re-validates before cross-reference checks and persistence (unknown quest/item/faction refs reject via `CrossReferenceValidator`).
- The standalone `DialogueAuthoringModel` duplicate was removed — `DialogueEditorScreenModel` remains the single editor mutation path.
- Editor floors delivered (this branch): undo/redo with deep-copy snapshots (`DialogueGraphLayout.deepCopy` duplicates condition/action/availability lists — verified by snapshot-isolation fixture), coalesced keystroke edits, redo-clear on mutation; case-insensitive search across every authored field (node id/text/speaker/sound/textKey, edge text/textKey/endpoints, condition/action payloads) with wrap-around match cycling; full node editing (id rename with edge/entry remap and collision refusal, text, speaker, sound, textKey, x/y position); full edge editing (option text, textKey, once-only, retarget, condition CRUD, action CRUD); dialogue-level editing (title key, availability conditions, entry node via Set Entry); diagnostics pane driven by the same `DialogueGraphValidator` the save path runs (clickable node-jump rows); entry-first BFS preview with bounded walk and gated-option annotations; JSON import/export through `DialogueGraphSerde` (all semantic fields round-trip; bad JSON refused); keyboard surface (Ctrl+Z/Y, Tab/Shift-Tab selection cycling, Ctrl+F search, Ctrl+E export, Delete with armed-confirm); `EditorViewport` documents the 427x240-minimum layout contract with the paged edge inspector keeping every field reachable.
- Known limit: undo restores full layout snapshots including canvas positions, so undoing a text edit also reverts node drags since that snapshot — consistent snapshot semantics, positions are editor-visual only (not persisted to the graph).

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
- P5-3 editor floors delivered under this branch's PR (undo/redo, search, panes, keyboard nav, min-res viewport contract).
- COMMAND reward execution is verified at service level (headless marks delivered); the live command-dispatch path shares the dialogue `EXECUTE_COMMAND` sanitizer and is exercised in the GameTest environment only.

## Verification

`./gradlew test`: 1,390 tests, 0 failures. `git diff --check` clean. Editor verification is headless model-level (the Screen is a thin adapter; widget rendering itself is exercised only in the client runtime).
