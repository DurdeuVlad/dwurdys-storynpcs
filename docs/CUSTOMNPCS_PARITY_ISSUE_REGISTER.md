# StoryNPCs CustomNPCs parity issue register

Status: `REVISION-4` — 47 granular local issue drafts. A direct consistency pass corrected dependency cycles and clarified dependency shorthand; this does not count as an independent completeness review. Foundation statuses remain evidence-gated; no issue is certified by documentation alone.

This is the implementation handoff register. The authoritative traceability mapping is [CUSTOMNPCS_PARITY_TRACEABILITY.md](CUSTOMNPCS_PARITY_TRACEABILITY.md). The issues are ordered by dependency. GitHub linkage is recorded in the [external tracker map](CUSTOMNPCS_PARITY_GAP_INVENTORY.md); the original M1–M8 tickets remain historical and are not renumbered or repurposed.

## Shared issue policy

- Assignee: `unassigned` unless a user assigns one.
- Project and iteration: `unknown`; no project/iteration was selected.
- Target horizon: none supplied.
- Priority values are proposed ordering, not commitments.
- Every implementation issue must produce a PR body containing Intent, Expectation, Acceptance criteria with evidence, Non-code context, Scope/non-goals, Verification/risk, assumptions, and unsupported checks.
- Every target behavior must be labeled `VERIFIED_TARGET_RUNTIME`, `UNVERIFIED_TARGET_RUNTIME`, `VERIFIED_PARITY`, or another state from the traceability register. Static class-name matching is never parity proof.
- Every mutation must remain inside or behind `StoryNpcsApplicationService`; a separate dispatcher may organize that service but may not bypass it.
- Every issue must preserve the supplied JAR as evidence only and must not copy decompiled code.
- Dependency semantics are explicit: text before the first semicolon is blocking; text after it is a non-blocking open-decision note. `M5` means every issue ID listed under M5 in the milestone map; `M0-M10` means every issue ID in each listed milestone, inclusive. `P6-*` means every numbered issue with the `P6-` prefix in this register. Expand these local shorthands to exact issue IDs before creating or updating tracker tickets. A reference after the semicolon never creates a dependency edge.

## Milestone map

| Milestone | Outcome | Issues |
|---|---|---|
| M0 | Truth baseline and executable parity evidence | P0-1, P0-2, P0-3, P0-4 |
| M1 | Stable actors, canonical operations, authorization, protocol | P1-1, P1-2, P1-3, P1-4 |
| M2 | Versioned content and recoverable state | P2-1, P2-2, P2-3 |
| M3 | Core CustomNPCs NPC capability parity | P3-1, P3-2, P3-3, P3-4 |
| M4 | MMO-scale simulation and measurable battles | P4-1, P4-2, P4-3 |
| M5 | Dialogue and quest parity | P5-1, P5-2, P5-3, P5-4, P5-5 |
| M6 | Factions, roles, jobs, transport, companions | P6-1, P6-2, P6-3, P6-4, P6-5 |
| M7 | Trade and bank parity | P7-1, P7-2 |
| M8 | Creator tools and world systems | P8-1, P8-2, P8-3, P8-4, P8-5, P8-6 |
| M9 | Public API, scripting, command, administration, and integration compatibility | P9-1, P9-2, P9-3, P9-4, P9-5 |
| M10 | Familiar creator UI and AI authoring | P10-1, P10-2, P10-3 |
| M11 | Import, evidence closure, and release certification | P11-1, P11-2, P11-3 |

---

## M0 — Truth baseline and executable parity evidence

### P0-1 — Generate the exact target surface manifest

- **Intent:** Give every target class, command, packet, GUI, event, role, job, companion job, persistence participant, persistence store, asset entry, data entry, and operation-matrix row a traceable owner issue.
- **Expectation:** A deterministic manifest generated from the exact SHA-256-matched JAR lists each target inventory row with a stable inventory ID, target symbol, domain, operation family, surface, evidence state, owner issue, and explicit links to applicable executable parity fixtures. The unresolved eleventh job is resolved from the artifact or explicitly recorded as unknown.
- **Acceptance criteria:** The manifest contains 1,230 classes, 972 assets, 49 data entries, 149 GUI sources, 155 payloads, 70 command leaves, 97 event classes, 7 roles, 11 jobs, 3 companion-job entries, 60 persistence participants, 18 persistence stores, and exactly the 15 canonical operation IDs; every row carries a unique `inventory_id`, domain, operation family, surface, evidence state, provenance, and semantically valid owner issue; every decompiled-source row carries a SHA-256 of the actual source file, and every research-inventory row carries a SHA-256 of the exact inventory file; the verifier checks those hashes, the source-to-class JAR entry, and the exact JAR filename/SHA-1/SHA-256 before generation; behavioral rows resolve through the 22-domain/15-operation fixture crosswalk to catalog `fixture_id` values, while inventory-only rows are explicitly marked `INVENTORY_ONLY` and are never mislabeled as tests; every operation row names its closing implementation issues in addition to the harness owner; missing/duplicate/unmapped rows, duplicate/unknown/incomplete operation IDs, dangling fixture/issue references, stale source or inventory hashes, malformed provenance, oversized fields/records, corrupt archives, and wrong artifact identity fail validation.
- **Context code cannot infer:** Target counts and the exact artifact hash are recorded in `CUSTOMNPCS_PARITY_TRACEABILITY.md`; static inventory does not prove runtime behavior.
- **Scope:** Research manifest generator, traceability metadata, target inventory validation. Owner: research/tooling. Metadata: type `research/tooling`; priority `P0`; milestone `M0`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Implementing target behavior, copying decompiled code, or claiming runtime parity.
- **Dependencies and open decisions:** None; the exact supplied JAR is mandatory.
- **Verification:** Focused validator tests, exact-artifact generation, source/inventory provenance checks, hash/count/no-unmapped-row tests, semantic issue-mapping negative tests, deterministic regeneration diff, and manifest review.

### P0-2 — Remove unsupported completion claims

- **Intent:** Ensure project documentation tells creators and maintainers what is actually implemented.
- **Expectation:** README, Business, Decision, milestone, UX, and performance documents distinguish delivered, partial, proposed, and unverified behavior.
- **Acceptance criteria:** Claims of 100% mutation parity, scripting/API parity, crash-safe progression, LOD/async/squad performance, 500-NPC performance, and complete UI parity are either backed by an implementation plus evidence fixture or marked aspirational/unverified; every changed claim links to a register issue; CI rejects the banned completion phrases unless they appear in a truth-gate exception list.
- **Context code cannot infer:** Current source and research contradict several broad claims; the research performance numbers are directional prototypes, not current Java evidence.
- **Scope:** `README.md`, `Business.md`, `Decision.md`, `Milestones.md`, research-facing links, truth-gate check. Owner: documentation/release. Metadata: type `documentation`; priority `P0`; milestone `M0`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Hiding gaps, deleting research evidence, or changing implementation to satisfy wording.
- **Dependencies and open decisions:** P0-1; none blocking.
- **Verification:** `python tools/parity/check_truth_gate.py` with scoped exceptions, repository-wide claim scan, link check, reviewer comparison with source symbols, CI workflow execution, and full Gradle test.

### P0-3 — Define the target-runtime evidence gate

- **Intent:** Prevent a static comparison from being misreported as behavioral parity when target runtime probes are unavailable.
- **Expectation:** Each fixture records target setup, probe status, target result, StoryNPCs result, comparison rule, and an evidence state. `UNVERIFIED_TARGET_RUNTIME` blocks final parity certification but does not block implementation of the corresponding adapter.
- **Acceptance criteria:** The gate distinguishes verified target behavior, verified StoryNPCs behavior, verified parity, unavailable target runtime, blocked environment, and intentional deviation; P0-4 and P11-2 consume the same schema; a missing target probe cannot produce green parity; blocked probes report the exact reason and next evidence needed.
- **Context code cannot infer:** Research Phase 1 has 0/15 semantic target equivalences proven and graphical/packet/runtime cases remain incomplete.
- **Scope:** Evidence schema, gate evaluator, report format, blocked-probe policy. Owner: verification. Metadata: type `testing`; priority `P0`; milestone `M0`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Treating target runtime access as permanently unnecessary.
- **Dependencies and open decisions:** P0-1; target launch environment remains an external dependency.
- **Verification:** `python -m unittest tools.parity.test_evidence_gate` with verified/unverified/blocked fixtures, contradictory-result and strict JSON type fixtures, malformed/empty report fixtures, intentional-deviation fixtures, and report snapshot inspection.

### P0-4 — Build the 15-operation/22-domain fixture harness

- **Intent:** Make every parity requirement executable at the real boundary instead of relying on prose.
- **Expectation:** A fixture runner executes YAML load, canonical operation, each applicable adapter, event, persistence, reload, failure, and evidence comparison for every traceability row.
- **Acceptance criteria:** All 15 operation families and 22 domains have at least one named fixture; each fixture declares StoryNPCs execution layers and a separate target-probe policy; target runtime is never a prerequisite for executing a StoryNPCs fixture and unavailable target probes remain `UNVERIFIED_TARGET_RUNTIME`. Each implemented behavior maps to at least one globally unique, semantically matching exact JUnit selector with observed and uncovered scope recorded. A not-yet-implemented behavior may keep an empty selector list only when its fixture names the owning feature issue(s); the default combined certification runner must report that fixture as `BLOCKED` and exit nonzero, and may never count it as observed or parity evidence. The explicit CI-only `--ci-allow-declared-blockers` mode may exit zero only when every mapped JUnit case passed, no mapped case is absent or skipped, and the complete empty-selector set exactly matches the catalog's owner-linked blocker declarations; its report must keep overall fixture coverage and target parity `BLOCKED` and mark only CI test-execution validation `PASS`. This lets the fail-closed harness be delivered before later feature issues without inventing coverage; those owning issues must add real selectors and tests when implementing the behavior. Each JUnit testcase is a direct child of a testsuite, declared suite totals reconcile with all contained case outcomes, and malformed/nested outcome markers fail closed. The runner invokes the full target-manifest validator and, when provenance inputs are supplied, rehashes the exact target JAR and verifies every source-to-JAR entry and source SHA-256; absent JAR/research/decompiled inputs or missing source hashes yield `BLOCKED`, never `PASS`. Empty/unmapped selectors, absent/skipped tests, failed tests, inconsistent JUnit totals, malformed manifests, duplicate/unknown/incomplete operation IDs, corrupt archives, dangling inventory-to-fixture references, or incomplete domain/operation crosswalks fail the strict run and also fail CI unless they are precisely the declared empty-selector blockers; a fixture failure identifies issue ID, target symbol, input, expected result, actual result, and evidence state; one documented command runs the StoryNPCs fixture suite.
- **Context code cannot infer:** Existing JUnit coverage is useful but does not cover the target’s 149 GUI/155-packet/70-command surface.
- **Scope:** Fixture schema, Gradle test source sets, optional live-server harness, evidence reporter. Owner: test infrastructure. Metadata: type `testing`; priority `P0`; milestone `M0`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Automated GUI scraping or declaring target behavior without a target observation.
- **Dependencies and open decisions:** P0-1, P0-3; the P0-4 gate may precede P1-1 while the combined run stays blocked on unimplemented fixtures owned by P6-3, P6-4, P8-1, P8-2, P8-3, P9-1, and P9-2. Those feature issues must add semantically matching selectors as they implement their behavior; target runtime access is optional per StoryNPCs fixture and remains necessary for target-parity evidence.
- **Verification:** `python -m unittest tools.parity.test_fixture_harness`, `python -m unittest discover -s tools/parity -p "test_*.py"`, manifest/catalog reference integrity, all 15/22 crosswalk checks, blocked target-probe reporting, one passing and one intentionally failing catalog case, an empty-selector case that remains `BLOCKED` and makes the default combined runner exit nonzero, CI mode accepting only the exact declared blocker set with mapped tests passing, and the clean-checkout CI command.

## M1 — Stable actors, canonical operations, authorization, protocol

### P1-1 — Introduce typed canonical operations inside StoryNpcsApplicationService

Status: `IN-REVIEW` — typed player-scoped quest start/progress now has persisted revision checks; completion advances that revision; objective thresholds are bounded by the supported state maximum; and reentrant quest-event listeners cannot overtake the active notification group. Reward fan-out crash safety, typed quest completion/rewards, faction progression, follower/bank runtime state, template/tool operations, and remaining direct adapters remain incomplete. See [P1-1 closeout](parity/P1-1-CLOSEOUT.md).

- **Intent:** Make commands, packets, GUI, API, and scripts reliable adapters instead of independent mutation implementations.
- **Expectation:** Typed requests carry actor context, capability, target ID, expected revision, idempotency key, and validated payload; typed results carry diagnostics, revision, events, and recovery outcome; all domain mutation remains inside/behind `StoryNpcsApplicationService`.
- **Acceptance criteria:** NPC, dialogue, quest, faction, role, trade, bank, follower, rule, template, tool, and progression mutations route through this boundary; direct adapter field mutation is absent; duplicate requests are safe; every quest state transition including completion advances its persisted revision; stale revisions fail without partial writes; reentrant event listeners cannot reorder notifications across a committed operation; every rejection has a machine-readable diagnostic.
- **Context code cannot infer:** Current service is a large partial façade and commands/network handlers still mutate objects before save.
- **Scope:** Service request/result types, dispatcher organization, adapter migration, operation tests. Owner: application architecture. Metadata: type `architecture`; priority `P0`; milestone `M1`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Adding all missing CustomNPCs features in this issue or changing the target’s behavior.
- **Dependencies and open decisions:** P0-4; operation naming/versioning must use the traceability register.
- **Verification:** Adapter static inspection, per-operation JUnit, replay/stale/failure tests, Gradle test.

### P1-2 — Separate stable NPC actors from entity projections

Status: `IN-REVIEW` — stable actor/projection behavior has local coverage, but the independent architecture audit still found mutable `StoryNpcs.instance` lifecycle state and other static runtime dependencies. The closeout is reopened until a repository-wide managed-lifecycle scan and isolation fixtures pass.

- **Intent:** Preserve NPC identity, progression, roles, and content when a Minecraft entity unloads, dies, respawns, or is replaced.
- **Expectation:** A namespaced logical actor owns durable state; entity UUIDs are projections with explicit spawn/despawn reasons, loaded/unloaded transitions, and projection refresh.
- **Acceptance criteria:** Actor identity remains stable across entity replacement and restart; two worlds/servers do not share mutable actor state; projection failure is reported and retryable; lifecycle events include reason and actor ID; no mutable runtime selection or singleton cache remains in domain state.
- **Context code cannot infer:** `StoryNpcs`, network handlers, creator items, and event managers currently contain mutable static runtime state.
- **Scope:** Actor registry, lifecycle services, entity projection, cleanup hooks, isolation tests. Owner: runtime foundation. Metadata: type `architecture`; priority `P0`; milestone `M1`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Removing static NeoForge registration objects that are immutable registration infrastructure.
- **Dependencies and open decisions:** P1-1, P2-2; entity UUID compatibility is not logical-ID compatibility. P2-2 must establish durable logical actor state before P1-2 closes.
- **Verification:** Spawn/despawn/unload/reload, two-world, logout, replacement, crash/restart, and memory-retention tests; static scan.

### P1-3 — Version and harden the network protocol

Status: `DONE-LOCAL` — current packet hardening, focused tests, full-suite verification, truth gate, and static audit are complete. The target's 115 server-bound / 40 client-bound mapping remains a separate inventory gate; the independent auditor did not return a handoff within bounded waits and is therefore not counted as approval.

- **Intent:** Replace ad-hoc JSON/static-handler payload behavior with bounded, revisioned, replay-safe protocol contracts.
- **Expectation:** Every applicable client/server payload has a version, size limit, actor/session binding, expected revision, request ID, and structured result; stale screens refresh instead of overwriting newer state.
- **Acceptance criteria:** The 115 server-bound and 40 client-bound target payload mappings are inventoried; malformed/oversized/unknown-version payloads fail safely; duplicate requests are idempotent or rejected; reconnect/session expiry is explicit; network handlers call only canonical operations. The StoryNPCs implementation must additionally put an explicit schema version and total-byte bound on every registered payload, bound strings/lists, correlate mutation results to request IDs and revisions, bind dialogue/trade/bank actions to server-issued sessions, and clear transient tokens on player cleanup.
- **Context code cannot infer:** Current network code uses static handlers and millisecond throttles rather than operation identity and revision checks.
- **Scope:** Payload DTOs/codecs, protocol registry, session binding, client refresh/error handling, fuzz tests. Owner: networking. Metadata: type `security/protocol`; priority `P0`; milestone `M1`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Preserving undocumented internal packet formats from the target.
- **Dependencies and open decisions:** P1-1, P1-2; packet size limits proposed at 64 KiB per payload and 1 MiB per editor document.
- **Verification:** Codec round trips, unknown-version/oversize/list-boundary tests, partial-frame rollback, replay-window/session-expiry tests, structured-result correlation, stale revision, reconnect, permission, handler-routing inspection, and full Gradle test. Target mapping remains `UNVERIFIED` until the exact 115/40 mapping is linked to fixtures.

### P1-4 — Enforce actor capability and authorization policy

Status: `IN-REVIEW` — canonical definition mutations now fail closed through `AuthorizationPolicy`; complete command/script/economy/world-tool coverage remains open. See [P1-4 closeout](parity/P1-4-CLOSEOUT.md).

- **Intent:** Ensure creator tools, scripts, commands, dialogue effects, economy, and persistence cannot bypass server authority.
- **Expectation:** Every operation declares required capability and actor type; player, operator, console, script, and internal lifecycle actors receive distinct authorization; denial is side-effect-free and observable.
- **Acceptance criteria:** Unauthorized NPC edits, command effects, item/economy mutations, world tools, script operations, and persistence reads are rejected; permission changes are applied on the next request; replayed authorized requests do not double-apply effects; audit events identify actor, target, operation, result, and request ID.
- **Context code cannot infer:** Current commands and script-like effects are not yet unified under an explicit capability policy.
- **Scope:** Capability model, policy evaluator, audit events, adapter integration, tests. Owner: security/runtime. Metadata: type `security`; priority `P0`; milestone `M1`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Providing arbitrary OP privileges to scripts or clients.
- **Dependencies and open decisions:** P1-1; default policy is server/player/admin with explicit per-tool grants.
- **Verification:** Allow/deny matrix, privilege-change, replay, command-effect, script, economy and world-mutation tests.

## M2 — Versioned content and recoverable state

### P2-1 — Version and migrate every YAML definition family

Status: `IN-REVIEW` — current top-level NPC/dialogue/faction/quest loading and generic writing now have a shared version boundary; quest objective counts above the progression limit are rejected; the full role/job/template/tool/world fixture matrix is not yet complete. See [P2-1 closeout](parity/P2-1-CLOSEOUT.md).

- **Intent:** Let creators evolve NPC, dialogue, quest, faction, role, job, template, tool, and world definitions without silent corruption.
- **Expectation:** Every document has schema version, stable namespaced IDs, deterministic migration chain, strict field diagnostics, and forward-version refusal.
- **Acceptance criteria:** NPC, display, stats, AI, dialogue, quest, faction, role, job, trade, bank, companion, template, tool, and world schemas each have a version fixture; old fixtures migrate and round-trip; unknown future versions fail with file/field location; cross-reference failures list both sides; quest objective `requiredCount` cannot exceed the progression state's supported maximum (currently 100,000); bundled quickstart/demo NPCs and dialogues are versioned, namespaced YAML resources loaded read-only; quickstart resolves those loaded IDs and may spawn/reuse their entity projection but never constructs or saves definition objects from Java; malformed starter resources emit diagnostics and cause no partial definition writes.
- **Context code cannot infer:** Current loader/writer/validator have useful structure but no complete migration policy; `StoryNpcsCommands` currently constructs quickstart dialogue/NPC definitions in Java and persists them at runtime, violating YAML-first and read-only definition rules.
- **Scope:** Schema records, migration registry, loader/writer, validation diagnostics, fixtures. Owner: content platform. Metadata: type `data-model`; priority `P0`; milestone `M2`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Replacing human-readable YAML with an opaque binary source of truth.
- **Dependencies and open decisions:** P1-1; normalized generated YAML is the canonical written form.
- **Verification:** Migration chains, forward refusal, malformed YAML, deterministic serialization, cross-reference, and rollback tests.

### P2-2 — Implement durable world/player/economy stores

Status: `IN-REVIEW` — progression, bank, and actor state now share a versioned forced-write/recovery policy; the full 18-store mapping and injected crash matrix are not yet complete. See [P2-2 closeout](parity/P2-2-CLOSEOUT.md).

- **Intent:** Make progression, role state, containers, templates, sessions, and actor state survive crash, restart, unload, and migration.
- **Expectation:** Stores use versioned records, temp write, flush/force, atomic replace, backup retention, corruption quarantine, recovery diagnostics, and lifecycle ownership.
- **Acceptance criteria:** The 18 target store categories are mapped to StoryNPCs stores; every store declares world/player/entity ownership; crash injection at write, force, rename, and index-update stages recovers the last valid commit; one corrupt record does not erase unrelated records; backups retain the last three valid generations by default; failed migration leaves original data untouched.
- **Context code cannot infer:** Existing temporary replacement does not call explicit `FileChannel.force` and does not define complete per-store recovery policy.
- **Scope:** Repository interface, SavedData/file implementation, journal/index, backup policy, migration integration. Owner: persistence. Metadata: type `reliability`; priority `P0`; milestone `M2`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Supporting arbitrary third-party save formats before P11-1.
- **Dependencies and open decisions:** P2-1; proposed split is SavedData for world indexes and dedicated repositories for large/player/economy state.
- **Verification:** Fault injection, restart, corruption quarantine, backup restore, migration failure, unload/load, and multi-world isolation tests.

### P2-3 — Add operation-specific transaction and recovery fixtures

Status: `IN-REVIEW` — bank vault mutations now have a durable commit/rollback boundary and fault fixtures, live held-item deposit owns inventory removal with a vault-side marker and login replay, trade request IDs use a durable subject-bound journal alongside owned reservations, listing-use state now uses a durable reserve/rollback store outside YAML, and quest reward preflight rejects known invalid/non-atomic fan-out; concurrent in-process quest turn-ins are serialized. XP/item reward duplication after a failed progression save, inventory-side crash proof, and remaining operation families are not complete. See [P2-3 closeout](parity/P2-3-CLOSEOUT.md).

- **Intent:** Prove that trade, bank, quest rewards, faction changes, companion wages, equipment, and teleport unlocks cannot duplicate or lose value.
- **Expectation:** Each multi-step operation has a commit boundary, compensation/recovery path, idempotency behavior, and user-visible failure result.
- **Acceptance criteria:** Trade never charges without output; bank never removes without durable deposit; quest completion never duplicates or loses XP/item/faction rewards across a failed progression write, crash, restart, or retry; equipment rejects without loss; wages and teleport unlocks are exactly-once; crash/retry/stale-revision/full-inventory/failed-command cases each produce a deterministic result. Completion-save fault tests must include reward side effects before the failed write and prove recovery cannot redeliver them.
- **Context code cannot infer:** Quest completion now rejects known invalid/non-atomic reward plans before mutation, but crash recovery between external rewards and progression persistence is still open; trade/bank paths need operation-level recovery proof.
- **Scope:** Transaction fixtures, journal hooks, service integration, failure diagnostics. Owner: persistence/economy/progression. Metadata: type `testing/reliability`; priority `P0`; milestone `M2`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Making arbitrary third-party commands transactional without a defined adapter contract.
- **Dependencies and open decisions:** P1-1, P1-3, P2-2; command rewards are either compensatable or reported as non-atomic before commit.
- **Verification:** Fault injection at each side effect, duplicate request, restart, full-container, permission and migration tests.

## M3 — Core CustomNPCs NPC capability parity

### P3-1 — Implement stable display, variants, model, hitbox, and render features

Status: `IN-PROGRESS` — the YAML-first display contract now carries validated skin-source, cloak/glow, visibility/name-mode, tint, animation, hitbox, boss-bar, model identity, and model-size fields, with renderer name-mode and round-trip fixtures. Full skin/model/boss-bar/hitbox projection, editor/network coverage, and target runtime parity remain open. See [P3-1 progress](parity/P3-1-PROGRESS.md).

- **Intent:** Give creators the recognizable CustomNPCs display and model controls while keeping a clean render-feature boundary.
- **Expectation:** Definitions support name/title, default/custom/URL/player skin sources, cloak, glow, boss-bar style/color, tint, model variant, scale bounds, hitbox, and animation/render feature state; entity projection renders validated data.
- **Acceptance criteria:** Every target display field in the manifest round-trips YAML/UI/API; invalid URLs/assets use deterministic fallback and diagnostics; scale/hitbox limits prevent unsafe values; renderer cache is per actor; client refreshes after server revision; model assets are not hardcoded into domain definitions.
- **Context code cannot infer:** Current `NpcDisplay` and renderer cover only basic humanoid name/title/skin/scale/show-name fields.
- **Scope:** Display schema, renderer projection, asset validation/cache, model capability API, editor/API adapters. Owner: NPC/rendering. Metadata: type `feature`; priority `P1`; milestone `M3`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Copying the target’s model wrapper classes or claiming every model asset before the capability contract works.
- **Dependencies and open decisions:** P1-2, P2-1; proposed implementation uses vanilla/GeckoLib-backed render providers behind a stable interface.
- **Verification:** Field manifest, client render smoke tests, cache isolation, invalid asset, hitbox, scale, reload and multiplayer projection fixtures.

### P3-2 — Implement stats, melee, ranged, resistances, and defeat behavior

- **Intent:** Close the central combat capability gap required for CustomNPCs-style encounters and MMO battles.
- **Expectation:** Data-driven health/health regen/combat regen; melee strength/delay/range/knockback/potion effect; ranged projectile damage/speed/impact/size/area/trail/delay/range/fire rate/shot count/accuracy/physics/effects/sounds; aggro; four damage-resistance channels; six immunity toggles; defeat mode; respawn; XP; and drop hooks are authoritative.
- **Acceptance criteria:** Every manifest field has a bounded schema and fixture; melee/ranged damage is server-authoritative; the target's four resistance channels (`knockback`, `arrow`, `melee`, `explosion`) clamp to [0, 2] and serialize; six immunity settings (potion, fall, sunlight, fire, drowning, cobweb) round-trip; projectile settings produce deterministic effects; Dead/Hide/Flee defeat modes and respawn timers emit lifecycle events; invalid values fail without partial mutation.
- **Context code cannot infer:** Current stats and entity cover only basic health, melee damage, movement speed, respawn time and aggro range.
- **Scope:** Combat components, attributes, projectile/effect adapters, defeat/respawn lifecycle, drops hooks. Owner: combat runtime. Metadata: type `feature`; priority `P1`; milestone `M3`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Client-authoritative combat or unbounded projectile entity creation.
- **Dependencies and open decisions:** P1-1, P3-1; projectile implementation may use pooled entities or direct server traces, provided observable behavior matches the fixture.
- **Verification:** Numeric boundaries, damage/resistance, ranged burst/spread, potion, defeat/respawn, XP/drop, reload and concurrency tests.

### P3-3 — Implement AI, movement, targeting, and tactical behavior

- **Intent:** Match the target’s movement and combat behavior vocabulary while enabling later LOD/squad optimization.
- **Expectation:** Standing/wandering/path movement, six animation stances, wander radius, return, door/water/leap behavior, faction targeting, attack-on-sight, defend-allies, retaliation, target priority, tactical retreat/stalk/ambush/circle/hit-and-run, and Dead/Hide/Flee reaction are data-driven capabilities.
- **Acceptance criteria:** Each target policy and movement mode has a state fixture; path goals cancel on unload and respect tick budget; ally protection cannot scan unbounded worlds; target changes emit reasons; no off-thread world mutation occurs; behavior remains deterministic under a fixed seed.
- **Context code cannot infer:** Current AI has useful wandering/path/return/threat fields but not the complete target policy/tactical surface or scalable scheduler.
- **Scope:** Behavior capabilities, targeting policy, path goal contracts, threat integration, lifecycle events. Owner: AI runtime. Metadata: type `feature`; priority `P1`; milestone `M3`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Performance optimization implementation beyond contracts; that is M4.
- **Dependencies and open decisions:** P3-2; P3-3 defines the faction-relationship provider contract and deterministic targeting behavior with an explicit neutral fallback for an unknown faction pair. P6-1 supplies the full data-driven relationship matrix against this contract and depends on P3-3.
- **Verification:** Movement/stance/door/water/path, target-policy, ally-defense, defeat-reaction, unload/cancel and deterministic seed fixtures.

### P3-4 — Implement inventory, equipment, drops, marks, and authoritative item tools

- **Intent:** Replace the current string inventory and one-mark model with target-equivalent item/equipment capability.
- **Expectation:** The target exposes 4 armor slots, 3 weapon positions (right hand, left hand, projectile), 9 visible drop slots in `ContainerNPCInv`, and `INPCInventory` drop indices 0–20 (21 API-addressable drop entries); it also stores drop chances/XP/loot mode, mark collections with type/color/availability, and server-authoritative equipment/tool sessions. The 9 GUI slots are drops, not a general-purpose NPC backpack.
- **Acceptance criteria:** Slot counts and item components survive YAML/state round-trip; equip/unequip/drop rejects invalid/full operations without loss; drop probability and looting fixtures use fixed seeds; marks serialize all fields and resynchronize visibility; creator tool selection is session-scoped rather than static; item effects never trust client NBT.
- **Context code cannot infer:** Current NPC inventory is `List<String>` and only one `NpcMark` is represented.
- **Scope:** Container/equipment domain, item-component serialization, mark catalog, tool session state, adapters. Owner: inventory/authoring. Metadata: type `feature/security`; priority `P1`; milestone `M3`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Arbitrary client item mutation or silent item dropping as a default recovery policy.
- **Dependencies and open decisions:** P1-2, P1-4, P2-3; use NeoForge data components where platform data requires them.
- **Verification:** Slot/drop/loot, full-container, invalid-item, restart, mark-color/availability, permission and multiplayer resync tests.

## M4 — MMO-scale simulation and measurable battles

### P4-1 — Implement archetypes, simulation tiers, and LOD

- **Intent:** Make large NPC populations affordable without changing nearby combat correctness.
- **Expectation:** Immutable shared archetypes and active/nearby/distant/dormant/unloaded tiers control sensing, pathing, animation, combat evaluation and persistence frequency.
- **Acceptance criteria:** Tier transitions are deterministic and observable; dormant NPCs retain durable state without live world references; active combat never enters dormant behavior; per-tier budgets are configurable; memory measurements separate shared archetype and per-actor incremental cost.
- **Context code cannot infer:** Current Java source has no production LOD/flyweight implementation; research claims are prototypes.
- **Scope:** Runtime scheduler, actor cache, simulation tier policy, metrics. Owner: performance/runtime. Metadata: type `performance`; priority `P1`; milestone `M4`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Dropping important nearby combat behavior to hit a population headline.
- **Dependencies and open decisions:** P3-2, P3-3; use explicit conservative per-tier defaults recorded in the simulation-tier schema. The thresholds already defined in `CUSTOMNPCS_PARITY_CERTIFICATION.md` are the initial benchmark baseline; P4-3 implements and reports the later benchmark gate and is not a prerequisite for P4-1.
- **Verification:** Tier transition, reference-retention, active-combat, memory and deterministic population tests.

### P4-2 — Implement bounded path scheduling and squad coordination

- **Intent:** Prevent N-squared scans and unbounded path work in large battles.
- **Expectation:** Path requests are queued, prioritized, cancelled, and budgeted; squad/threat coordinators share bounded information and preserve server-thread world mutation.
- **Acceptance criteria:** Queue size and worker budget are bounded; unload/revision cancels stale requests; no worker touches live world state; 25v25 combat uses squad coordination without duplicate targets or lost threat; queue overflow produces degraded-but-explicit behavior.
- **Context code cannot infer:** Current Java source lacks the claimed async path pool and squad coordinator.
- **Scope:** Navigation scheduler, path result handoff, squad/threat coordinator, telemetry. Owner: AI/performance. Metadata: type `performance`; priority `P1`; milestone `M4`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Unbounded async world access or an opaque native dependency.
- **Dependencies and open decisions:** P3-3, P4-1; default queue cap is 1,024 requests and must be visible in metrics.
- **Verification:** Queue cap/cancel, thread-safety, 25v25 correctness, unload, overflow and profiler tests.

### P4-3 — Establish the performance certification contract

- **Intent:** Replace unsupported performance claims with reproducible pass/fail evidence.
- **Expectation:** A fixed environment, seed, workload, telemetry format, and thresholds govern all performance claims.
- **Acceptance criteria:** The contract specifies Java/NeoForge version, dedicated-server hardware profile, simulation/view distance, modpack assumptions, warmup/run duration, fixed seeds, p50/p95/p99 MSPT, memory, packet, queue and correctness limits; proposed baseline is p95 ≤35ms/p99 ≤45ms for 500 NPC and 25v25, p95 ≤45ms/p99 ≤50ms for 2,500 NPC stress, ≤32KiB incremental dormant actor memory, zero correctness failures, and queue ≤1,024; results are repeatable within 10%.
- **Context code cannot infer:** Existing docs cite thresholds without a runnable production benchmark or published environment.
- **Scope:** Benchmark harness, scenario worlds, profiler/JFR or spark export, threshold report, regression gate. Owner: performance verification. Metadata: type `benchmark`; priority `P1`; milestone `M4`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Universal TPS guarantees across arbitrary modpacks/hardware.
- **Dependencies and open decisions:** P4-1, P4-2; thresholds are proposed baseline and may only change through a recorded decision.
- **Verification:** Clean-checkout repeated runs, profiler artifact review, correctness checks under load, threshold report.

## M5 — Dialogue and quest parity

### P5-1 — Complete the authoritative dialogue runtime

- **Intent:** Provide the familiar NPC conversation capability with a better directed-graph model.
- **Expectation:** Nodes/lines/options, availability conditions, actions/effects, sounds, commands, variables, entry/exit, cooldown/once behavior, localization IDs, and session lifecycle are typed and extensible.
- **Acceptance criteria:** The target dialogue manifest maps every supported field to YAML/service/player UI/admin UI/API/script/event/persistence or an explicit N/A; graph cycles/entry reachability and invalid references diagnose; dialogue command/effect permissions use P1-4; dialogue open/choice/close/reject/reload events are emitted; unsupported target behavior stays unverified.
- **Context code cannot infer:** Current graph runtime exists but editor and action coverage are narrow.
- **Scope:** Dialogue domain/runtime, conditions/actions registry, session lifecycle, events. Owner: narrative runtime. Metadata: type `feature`; priority `P1`; milestone `M5`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Reintroducing fixed six/twelve-option arrays.
- **Dependencies and open decisions:** P1-1, P1-4, P2-1; localization uses stable keys and externalized text/assets.
- **Verification:** Graph validation, condition/action, command permission, session lifecycle, localization, sound, reload and event fixtures.

### P5-2 — Make dialogue choice exactly-once and stale-safe

- **Intent:** Prevent duplicated effects and client desynchronization when players click, reconnect, or race sessions.
- **Expectation:** Server-issued opaque choice tokens bind actor/dialogue/session/revision/expiry; one accepted token produces one effect transaction and one new view.
- **Acceptance criteria:** Index-only or old-token requests fail; duplicate accepted tokens do not repeat rewards/faction/quest/command effects; concurrent choices have one deterministic winner; timeout/reconnect closes or resumes by policy; client receives authoritative revision and diagnostics.
- **Context code cannot infer:** Current packet path uses option indexes and a millisecond throttle rather than request identity.
- **Scope:** Session token, protocol integration, choice transaction, player UI recovery. Owner: narrative/networking. Metadata: type `security/protocol`; priority `P1`; milestone `M5`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Trusting client-supplied condition results or action payloads.
- **Dependencies and open decisions:** P1-3, P1-4, P5-1; default token expiry is 60 seconds and one active session per player/NPC pair.
- **Verification:** Duplicate/replay, stale revision, race, timeout, reconnect, permission and effect exactly-once fixtures.

### P5-3 — Build the full dialogue authoring and asset workflow

- **Intent:** Make graph dialogue faster and clearer to author than target linear GUI flows.
- **Expectation:** Searchable node/edge editor, reusable condition/action property panels, speaker/line/sound/localization asset fields, validation pane, preview, undo/redo, import/export, and keyboard navigation exist.
- **Acceptance criteria:** Every P5-1 field is editable without raw YAML; save/load round-trips all fields; invalid references point to node/field; large graphs pan/zoom/search without losing selection; preview uses server validation; UI behavior works at the supported minimum resolution defined by the certification contract: 854x480 physical display, GUI scale 2, and at least 427x240 logical GUI viewport.
- **Context code cannot infer:** Current editor has graph CRUD but exposes only a narrow action field.
- **Scope:** Client editor, screen model, diagnostics, assets, command/API openers. Owner: UX/content tooling. Metadata: type `ui`; priority `P1`; milestone `M5`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Replacing graph dialogue with a fixed option-array editor.
- **Dependencies and open decisions:** P5-1, P1-3; exact visual language is open but interaction requirements are fixed.
- **Verification:** UI automation, graph round trip, field coverage, undo/redo, invalid-state and minimum-resolution tests.

### P5-4 — Implement quest definitions, objectives, dependencies, and repeats

- **Intent:** Reach target quest authoring parity while allowing richer graph/dependency structures.
- **Expectation:** Item, kill, dialogue, location, manual/custom objectives; six repeat modes from the target; prerequisites/dependencies; display/progress metadata; and typed reward declarations are supported.
- **Acceptance criteria:** All five target objective families and six repeat modes (`normal`, `repeatable`, `daily`, `weekly`, `reset`, `instant`) have YAML/UI/service/player-log fixtures; dependency cycles fail; daily/weekly/reset boundaries use explicit timezone/clock policy; manual/custom objectives use a typed extension contract.
- **Context code cannot infer:** Current implementation has five objective types but only three repeat modes and no complete dependency/team model.
- **Scope:** Quest definition/state schema, validator, editor/player log. Owner: progression. Metadata: type `feature`; priority `P1`; milestone `M5`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Allowing arbitrary script to mutate quest state outside canonical operations.
- **Dependencies and open decisions:** P2-1, P5-1; default reset timezone is server timezone and must be displayed.
- **Verification:** Objective progress, dependency cycle, all repeat modes, clock boundary, reload and editor round-trip tests.

### P5-5 — Implement quest completion, team progress, mail, and rewards

- **Intent:** Make quest progression reliable under full inventory, multiplayer, repeat boundaries, and partial external effects.
- **Expectation:** Completion/turn-in is a revisioned state machine with item/XP/faction/mail/command rewards, team ownership, explicit failure/retry, and exactly-once semantics.
- **Acceptance criteria:** Rewards cannot duplicate; completion does not become final before required durable effects; mail is a recoverable overflow channel; failed command rewards report atomic/non-atomic policy; full inventory has documented output; team progress and ownership survive reconnect; quest log shows pending/rejected/recovered outcomes.
- **Context code cannot infer:** Current completion can mark state before reward fan-out and catches failures; mail/team state is absent.
- **Scope:** Quest completion service, mail store/UI, team state, reward transaction, events. Owner: progression/economy. Metadata: type `feature/reliability`; priority `P1`; milestone `M5`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Treating arbitrary server commands as magically rollbackable.
- **Dependencies and open decisions:** P2-3, P5-4; team semantics are shared-party membership with explicit owner/claim policy.
- **Verification:** Reward fault injection, duplicate turn-in, full inventory, mail recovery, team reconnect, repeat reset and command-failure fixtures.

## M6 — Factions, roles, jobs, transport, companions

### P6-1 — Implement faction matrix and progression parity

- **Intent:** Make faction reputation a complete targeting/progression system rather than points plus thresholds only.
- **Expectation:** Factions support default points, clamping, thresholds, colors, inter-faction relationship matrix, hostile/friendly policy, and changes from dialogue/quest/kill/API/script with deletion cleanup and optional team sharing.
- **Acceptance criteria:** Every field has schema/service/UI/API/event/persistence mapping; changes identify source and actor; thresholds update target policies; faction deletion repairs references or fails with diagnostics; points never overflow; team sharing is explicit and tested.
- **Context code cannot infer:** Current faction model has basic points/thresholds but lacks matrix and complete change-source semantics.
- **Scope:** Faction domain, targeting integration, progression events, editor/player views. Owner: progression/AI. Metadata: type `feature`; priority `P1`; milestone `M6`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Hardcoding one global faction relationship table without namespaced IDs.
- **Dependencies and open decisions:** P1-1, P2-1, P3-3; team-sharing default is off unless configured.
- **Verification:** Threshold/clamp, matrix, kill/dialogue/quest source, deletion cleanup, reload and targeting fixtures.

### P6-2 — Implement service and social roles

- **Intent:** Provide the target role vocabulary through composable capabilities.
- **Expectation:** Dialogue, follower, postman/mailbox, healer, and bard roles have explicit configuration, permissions, UI, events, persistence, lifecycle and tick budgets; trader/bank roles are completed by M7.
- **Acceptance criteria:** Each role has a separate schema subsection and fixture; follower lifecycle handles owner logout/unload; mail role stores/delivers messages; healer/bard effects validate targets/cooldowns; dialogue role opens P5 sessions; role removal cleans state; role actions route through P1-1.
- **Context code cannot infer:** Current follower exists partially; dialog is coupled to NPC; healer/bard/postman/mail roles are absent.
- **Scope:** Role registry, role capabilities, role UI/API/events, lifecycle cleanup. Owner: gameplay systems. Metadata: type `feature`; priority `P2`; milestone `M6`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** One monolithic role inheritance hierarchy.
- **Dependencies and open decisions:** P1-1, P2-2, P5-1, P5-5; role tick budgets use P4 policy.
- **Verification:** Per-role create/use/remove/reload, permission, owner lifecycle, cooldown, mail delivery and event tests.

### P6-3 — Implement transport locations and transporter role

- **Intent:** Add safe NPC transport and fast-travel workflows absent from StoryNPCs.
- **Expectation:** Creators define categories/locations, destination dimensions/coordinates, unlock conditions, fees, preview and failure behavior; players discover/select/confirm transport through server-authoritative UI/command/API.
- **Acceptance criteria:** Invalid/unloaded/dangerous destinations fail without charging; cross-dimension transfer has timeout/recovery; unlocks persist exactly once; fees use canonical economy operation; destination and permission checks run server-side; player UI lists only visible/unlocked destinations.
- **Context code cannot infer:** Transport is absent from current production source and target includes transporter role and player transport GUI.
- **Scope:** Transport definitions/service, destination validator, role, UI/command/API, events. Owner: gameplay/world systems. Metadata: type `feature/security`; priority `P2`; milestone `M6`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Arbitrary teleportation from scripts without capability grant.
- **Dependencies and open decisions:** P1-4, P2-3; default destination safety checks require loaded chunk and non-obstructed landing.
- **Verification:** Unlock/fee, cross-dimension, unloaded destination, disconnect, duplicate request, permission and UI fixtures.

### P6-4 — Implement the exact job capability inventory

- **Intent:** Replace the absent jobs system with bounded, composable handlers for every target job.
- **Expectation:** Artifact-resolved jobs each have a typed configuration, lifecycle, pause/resume, tick budget, persistence, events, permissions and cleanup. The manifest must resolve the 11th job name before certification.
- **Acceptance criteria:** Bard, builder, chunk loader, conversation, farmer, follower, guard, healer, item giver, puppet, and spawner each have a named acceptance subsection and fixture; every job stops on actor unload/removal; handler tick cost is observable; invalid job config fails at load; no job creates unbounded entities/chunks/tasks.
- **Context code cannot infer:** Current production source has no jobs registry; the exact artifact inventory resolves the concrete 11th job as `JobFarmer` while `JobInterface` is an interface and is not counted as a job.
- **Scope:** Job registry, handler lifecycle, scheduler integration, per-job schemas/UI/API/events. Owner: gameplay/performance. Metadata: type `feature/performance`; priority `P2`; milestone `M6`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Inventing the eleventh job name or copying target job classes before P0-1 resolves it.
- **Dependencies and open decisions:** P0-1, P4-1, P1-1; the exact artifact inventory is the authority for the concrete job list.
- **Verification:** Per-job fixture list, pause/resume/cleanup, unload, quota, tick-budget, reload and permission tests.

### P6-5 — Implement companion lifecycle, wages, stages, talents, and inventory

- **Intent:** Turn the partial follower role into a complete companion system.
- **Expectation:** Hiring, wages, stages, talents, inventory, stance, formation, jobs, owner lifecycle, dismissal, death and unloaded-time policy are data-driven.
- **Acceptance criteria:** Wage charge is exactly once per period and recoverable; insufficient funds have configured behavior; stages/talents apply bounded effects; companion inventory uses P3-4 containers; owner logout/unload follows explicit policy; companion jobs use P6-4 budgets; dismissal preserves or returns configured state.
- **Context code cannot infer:** Current follower supports owner/state/formation/days/rate but not stages/talents/inventory/job integration.
- **Scope:** Companion schema/service, progression, inventory, role/job integration, UI/player controls. Owner: gameplay/progression. Metadata: type `feature/reliability`; priority `P2`; milestone `M6`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Allowing a companion to bypass NPC combat/authority policy.
- **Dependencies and open decisions:** P2-3, P3-4, P6-4; default wage schedule is server-time daily with explicit missed-period policy.
- **Verification:** Hiring/dismissal, wage/retry, stage/talent, inventory, owner lifecycle, job, death and reload fixtures.

## M7 — Trade and bank parity

### P7-1 — Implement transactional trader parity

- **Intent:** Reach target trader capability while fixing one-input, admin-editing, restock, and concurrency holes.
- **Expectation:** Listings support up to two inputs/one output, pages, uses, restock interval, currency/item requirements, faction access, admin editing, player confirmation, and durable transaction outcome.
- **Acceptance criteria:** Both input slots and output are validated before commit; uses/restock persist across restart; concurrent purchases have one winner per use; admin and player screens round-trip identical listing data; full output inventory follows P5-5/mail policy; required faction/permission checks are server-side; failed purchase is side-effect-free.
- **Context code cannot infer:** Current trader supports one price and one offer, and current trade UI is player-facing rather than complete admin authoring.
- **Scope:** Trader schema/service, restock scheduler, admin/player UI, command/API/event adapters. Owner: economy. Metadata: type `feature/reliability`; priority `P2`; milestone `M7`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Unbounded marketplace/auction behavior.
- **Dependencies and open decisions:** P1-1, P2-3, P3-4, P6-1; currency is item-based initially, with extension capability for server-defined currency.
- **Verification:** Two-input, pages, uses/restock, concurrency, full inventory, faction/permission, restart and UI/command fixtures.

### P7-2 — Implement transactional bank parity

- **Intent:** Reach target bank capability with safe tabs, upgrades, access, and recovery.
- **Expectation:** Banks support up to six tabs, upgrade costs, private/shared access, item placement/removal, close/reconnect recovery, admin editing and player UI.
- **Acceptance criteria:** Tab count cannot exceed six; upgrade cost is charged exactly once; unauthorized access is rejected; deposit/withdraw is revisioned and atomic; disconnect during operation recovers the prior or committed state; admin and player views agree; vault content survives restart and migration.
- **Context code cannot infer:** Current banker/vault implementation is partial and lacks complete admin authoring, revision protocol, and recovery proof.
- **Scope:** Banker schema/service, `BankVault`, repository, admin/player UI, command/API/events. Owner: economy/persistence. Metadata: type `feature/reliability`; priority `P2`; milestone `M7`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Shared storage without explicit owner/access policy.
- **Dependencies and open decisions:** P1-3, P1-4, P2-2, P2-3; default banks are private per player unless configured shared.
- **Verification:** Six-tab boundary, upgrade, access, concurrent deposit/withdraw, disconnect, restart, corruption and UI fixtures.

## M8 — Creator tools and world systems

### P8-1 — Implement persistent templates, cloning, and spawners

- **Intent:** Replace the in-memory cloner with the named-template/spawner workflows creators expect.
- **Expectation:** Creators capture, name, version, search, preview, export/import, clone and spawn NPC templates; spawners enforce quotas, placement rules, cleanup and permissions.
- **Acceptance criteria:** Templates persist with schema/revision and namespace; import rejects unsafe/unknown data and reports unsupported fields; clone isolation prevents shared mutable state; spawner quota/interval/chunk/unload rules are deterministic; admin UI/commands/API use canonical operations; deleting a template reports dependent spawners.
- **Context code cannot infer:** Current cloner selection is static per-player state and there is no persistent template/spawner domain.
- **Scope:** Template store/schema, clone service, spawner capability, creator UI/tools, import/export. Owner: creator platform. Metadata: type `feature/security`; priority `P2`; milestone `M8`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Arbitrary world entity imports without validation.
- **Dependencies and open decisions:** P1-2, P2-1, P2-2, P4-1; import sources are YAML/JSON/template package, not decompiled classes.
- **Verification:** Capture/import/export, namespace isolation, version migration, quota, unload/cleanup, dependency, permission and multiplayer tests.

### P8-2 — Implement movement and creator utility tools

- **Intent:** Provide familiar path/waypoint/mount/teleport/remover/soulstone workflows with safe sessions.
- **Expectation:** Tools select actors through server-authoritative sessions, preview changes, validate permissions, persist results, and recover from unload or invalid targets.
- **Acceptance criteria:** Path/waypoint editing supports add/move/delete/loop/ping-pong/once and diagnostics; mount/passenger operations enforce legal relationships; teleporter/remover/soulstone operations require capability and confirmation; selections never use mutable static maps; tools have command/UI/API mappings and audit events.
- **Context code cannot infer:** Current path/mounter tools use static selection maps and target includes additional creator utilities.
- **Scope:** Tool session service, path/waypoint, mount, teleporter, remover, soulstone, UI/commands/events. Owner: creator/world systems. Metadata: type `feature/security`; priority `P2`; milestone `M8`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** General-purpose world edit permissions.
- **Dependencies and open decisions:** P1-2, P1-4, P3-3; destructive tools require confirmation and audit record.
- **Verification:** Tool workflow, permission, confirmation/cancel, unload, invalid target, multiplayer race and persistence tests.

### P8-3 — Implement scriptable world tools and creator blocks

- **Intent:** Close the target world-tool surface without allowing arbitrary unsafe mutation.
- **Expectation:** Scripter, scene, scripted block/door, mailbox, redstone/banner, and related world tools have typed definitions, preview, permission, event/audit, persistence and rollback policy.
- **Acceptance criteria:** Each target tool family has a separate fixture and explicit reversible/non-reversible operation list; blocks/doors validate dimension/chunk/permission; mailbox integrates P5-5; redstone/banner changes are logged; scenes have bounded size and unload cleanup; failed world mutation reports exact partial/rollback status; scriptable tools store validated, inert, typed hook bindings and do not execute user code in the world-tool layer.
- **Context code cannot infer:** Target has these GUI/tool families; StoryNPCs production source does not.
- **Scope:** World mutation service, tool schemas, block/entity hooks, creator UI/API adapters, and typed script-binding contract. Script execution is owned by P9-2 and must invoke these tools through canonical operations. Owner: creator/world systems. Metadata: type `feature/security`; priority `P2`; milestone `M8`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Unrestricted script access to arbitrary blocks or filesystem.
- **Dependencies and open decisions:** P1-4, P2-2; reversible operations are explicitly cataloged rather than described as “where possible.” P8-3 publishes inert typed hook bindings; P9-2 later provides their bounded script host and is not a prerequisite for defining world tools.
- **Verification:** Per-tool acceptance fixtures, permission, chunk unload, rollback/non-rollback reporting, size limits, restart and audit tests.

### P8-4 — Implement recipes, carpentry, registered content, and authoring hooks

- **Intent:** Cover the target recipe/carpentry and registered-content surfaces that are absent from the current issue register.
- **Expectation:** Recipe groups, carpentry benches, custom recipes, registered items/blocks, load events, and their creator screens are versioned definitions with canonical mutation operations and safe reload behavior.
- **Acceptance criteria:** Target classes `noppes.npcs.controllers.data.RecipeCarpentry`, `noppes.npcs.controllers.data.RecipesDefault`, `noppes.npcs.controllers.data.HandlerEvent$RecipesLoadedEvent`, and GUI families `GuiNpcManageRecipes`, `GuiNpcCarpentryBench`, and `GuiRecipes` have manifest rows, schemas, permissions, command/API/UI mappings, validation diagnostics, reload fixtures, and explicit unsupported-field reports; malformed recipes cannot partially register; duplicate IDs fail deterministically.
- **Context code cannot infer:** The target artifact exposes recipe/carpentry surfaces, while StoryNPCs has no registered-content parity issue or executable fixture for them.
- **Scope:** Recipe/content schema, registry lifecycle, canonical service operations, creator UI/API/command adapters, events, persistence and fixtures. Owner: content/world systems. Metadata: type `feature/data`; priority `P2`; milestone `M8`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Copying target implementation or permitting arbitrary runtime class registration.
- **Dependencies and open decisions:** P1-1, P1-4, P2-1; NeoForge registry ownership and reload timing must be explicit.
- **Verification:** Schema round trip, duplicate/unknown reference, reload/rollback, permission, client refresh, restart, and manifest-to-fixture checks.

### P8-5 — Implement linked NPCs, scenes, transformations, timers, and natural spawning

- **Intent:** Cover target world/story orchestration surfaces that are separate from ordinary NPC creation and combat.
- **Expectation:** Linked actors, bounded scenes, data-driven transformations, persistent timer events, and natural-spawn rules are stable logical definitions with unload-safe runtime sessions.
- **Acceptance criteria:** Target `SPacketLinked*`, `GuiNPCManageLinkedNpc`, `DataTransform`, `SPacketScene*`, `GuiNPCScenes`, `NpcEvent$TimerEvent`, `SPacketNaturalSpawn*`, and `GuiNpcNaturalSpawns` are mapped to schemas and canonical operations; links reject cycles or missing actors; scenes have size/time/entity budgets and cancellation recovery; transformations preserve or explicitly replace actor identity; timers survive restart without duplicate firing; spawn rules enforce quotas/chunk/permission constraints.
- **Context code cannot infer:** These surfaces were found by independent inventory review and are not represented by the previous P8 issues.
- **Scope:** Link/scene/transform/timer/spawn domains, service operations, persistence, events, network/UI/API/command adapters and fixtures. Owner: world/runtime systems. Metadata: type `feature/reliability`; priority `P2`; milestone `M8`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Unbounded cutscene scripting or uncontrolled natural-spawn amplification.
- **Dependencies and open decisions:** P1-1, P2-1, P2-2, P4-1; define scene execution budget and timer clock semantics before implementation.
- **Verification:** Cycle/missing-reference, scene cancellation/unload, transform rollback, timer restart/exactly-once, spawn quota, multiplayer isolation and client synchronization tests.

### P8-6 — Implement custom GUI, HUD/overlay, model presets, and visual asset authoring

- **Intent:** Close the target creator-facing visual tooling and the UI foundation needed for faster authoring.
- **Expectation:** Custom GUI layouts, HUD/overlay elements, model/color presets, visual layers, asset references and preview state are versioned, bounded, server-authoritative definitions.
- **Acceptance criteria:** Target `CustomGuiEvent`, `GuiCustom*`, `PacketOverlay*`, `GuiCreation*`, `GuiPresetSave`, and `GuiModelColor` surfaces have a field-level mapping; layouts reject oversized/deep/invalid trees; overlays are scoped to player/session and expire on logout; asset references are namespaced and validated; preview differs from commit; stale revisions and permission failures are side-effect-free; keyboard navigation, localization, high-DPI scaling, minimum-resolution, and discoverability fixtures exist for the improved UI.
- **Context code cannot infer:** The target inventory contains custom GUI/overlay/preset surfaces, while the current issue register treated UI parity as one broad future issue.
- **Scope:** UI schema, renderer/preview, overlay session service, assets, packets, creator screens, API/events and fixtures. Owner: UX/client platform. Metadata: type `ui/feature`; priority `P2`; milestone `M8`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Executing arbitrary client code or assuming target visual limitations are requirements.
- **Dependencies and open decisions:** P1-3, P1-4, P2-1, P3-1; choose the supported layout/rendering component model. This visual-authoring subsystem is independently usable; P10-1 integrates it after M8 and does not block its implementation.
- **Verification:** Bounds/fuzz, session cleanup, stale revision, preview/commit, asset fallback, localization, keyboard, high-DPI, minimum-resolution and multiplayer tests.

## M9 — Public API, scripting, and commands

### P9-1 — Deliver the typed public extension API

- **Intent:** Give mod/plugin authors a stable way to integrate without binding them to internal entity classes.
- **Expectation:** Public APIs expose stable IDs, immutable definitions/views, canonical operations, typed events, registries, diagnostics, version negotiation and capability grants.
- **Acceptance criteria:** NPC/dialogue/quest/faction/role/job/trade/bank/transport/template/tool operations and events have documented interfaces; API calls cannot bypass P1-1/P1-4; version incompatibility fails clearly; listeners cannot mutate shared state directly; examples compile in CI.
- **Context code cannot infer:** README claims a public API, but no production extension package equivalent was found.
- **Scope:** Public API packages, event contracts, registry interfaces, examples and compatibility tests. Owner: extensibility. Metadata: type `api`; priority `P2`; milestone `M9`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Promising binary compatibility with CustomNPCs’ internal classes.
- **Dependencies and open decisions:** P1-1, P1-4, M3, M5, M6, M7, M8; API versioning follows semantic version and schema versions. These milestone references expand to the issue IDs listed in the milestone map.
- **Verification:** Compile examples, permission, version, event isolation, reload, operation routing and API compatibility tests.

### P9-2 — Deliver a bounded scripting host and target hook matrix

- **Intent:** Provide CustomNPCs-style scripting power while preserving server authority and MMO budgets.
- **Expectation:** Typed hooks cover `init`, `tick`, `interact`, `damaged`, `killed`, `target`, `dialog`, `quest`, and `timer`; wrappers expose only granted capabilities; execution has time/memory/recursion/command quotas and failure isolation.
- **Acceptance criteria:** Every target hook is mapped to a StoryNPCs event/context and evidence state; scripts mutate only through canonical operations; initial per-hook budgets are 1ms for `tick` and 5ms for other hooks, with a 4ms aggregate script scheduler budget per server tick; each script is limited to 1MiB live memory, recursion depth 16, 20,000 instructions per `tick` invocation or 100,000 per other hook, and 32 canonical operation calls per hook; over-budget work is stopped/quarantined without crashing the server; reload/version/permission behavior is explicit; no unrestricted reflection/filesystem/OP command access exists; script editor/storage is versioned. Instruction counters are the deterministic test oracle; benchmark reports also record observed wall time and scheduler deferrals.
- **Context code cannot infer:** No production scripting package exists; target hook/API equivalence is unverified.
- **Scope:** Host runtime, hook adapters, sandbox/quota policy, script storage/editor, API bindings. Owner: extensibility/security. Metadata: type `security/api`; priority `P2`; milestone `M9`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Embedded arbitrary Java execution or silent privileged command execution.
- **Dependencies and open decisions:** P9-1, P1-4, P2-1; default is typed host API first, optional KubeJS-style external adapter second.
- **Verification:** Hook matrix, quota/timeout, permission escape, failure isolation, reload, migration, event ordering and performance tests.

### P9-3 — Reach command and suggestion parity

- **Intent:** Make command-driven authoring and administration complete enough for expert creators and automation.
- **Expectation:** The 70 target command leaves (61 mutations and 9 queries) are mapped to StoryNPCs command/API operations, with selectors, suggestions, diagnostics, permissions and explicit deviations.
- **Acceptance criteria:** P0-1 command manifest has an issue/fixture for every leaf; supported commands have YAML/service/UI/API equivalence; unsupported commands report intentional/unverified status; tab completion rejects invalid IDs/fields; commands never mutate definitions directly; help lists required permissions and examples.
- **Context code cannot infer:** Current commands cover core NPC/dialogue/quest/faction/rule/trade/bank/follower operations, not the target command surface.
- **Scope:** Command tree, argument types, suggestions, help/localization, adapters, command fixtures. Owner: command/tooling. Metadata: type `feature`; priority `P2`; milestone `M9`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Reproducing undocumented command bugs or internal syntax where a safer equivalent is approved.
- **Dependencies and open decisions:** P0-1, P1-1, P9-1; P9-3 records command deviations as draft outcomes here; P11-2 consolidates them in the final compatibility report and is not a prerequisite for command implementation.
- **Verification:** Command manifest, parse/suggestion/permission, mutation/query, malformed input, localization and parity fixtures.

### P9-4 — Implement remote, player-data, global administration, and configuration parity

- **Intent:** Make server/operator administration explicit and safe instead of leaving remote and global target controls outside the parity plan.
- **Expectation:** Remote editors, player-data administration, global menus, achievements/configuration and server-wide settings use authenticated capabilities, typed diagnostics, revision checks and audit records.
- **Acceptance criteria:** Target `SPacketRemote*`, `GuiNpcRemoteEditor`, `SPacketPlayerData*`, `GuiNpcManagePlayerData`, `PacketConfigFont`, `GuiAchievement`, and `SPacketMenu*` receive manifest rows and StoryNPCs mappings; remote requests require session/permission proof; player-data operations distinguish self/admin scope; config changes validate, persist, reload and roll back atomically; global actions cannot mutate an unloaded or wrong-world target; every operation has command/API/UI/event/fixture coverage or an explicit deviation.
- **Context code cannot infer:** Independent review found administration/player/global-data and configuration surfaces absent from the register.
- **Scope:** Admin schemas and operations, permissions, network/UI/command/API adapters, config store, audit and recovery tests. Owner: administration/platform. Metadata: type `security/admin`; priority `P1`; milestone `M9`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Client-authoritative operator privileges or unrestricted remote filesystem access.
- **Dependencies and open decisions:** P1-1, P1-3, P1-4, P2-2, P9-3; define operator capability names and config reload boundary.
- **Verification:** Permission matrix, session expiry, player-scope isolation, malformed remote payload, config rollback/restart, audit, command/API/UI parity and wrong-world tests.

### P9-5 — Define optional-mod integrations and intentional-deviation contracts

- **Intent:** Prevent target integrations and improvements from becoming undocumented compatibility holes.
- **Expectation:** Optional integrations (including target-detected Pixelmon/Cobblemon-style helpers and other mod bridges) are capability-detected, versioned, isolated, and either implemented or explicitly documented as deviations.
- **Acceptance criteria:** Every integration helper found in the manifest has an issue mapping, supported-version range, load-order behavior, absent-mod behavior, permission boundary, test fixture or `UNVERIFIED_TARGET_RUNTIME` state; no optional class load crashes a server without the dependency; intentional deviations name the user-visible behavior and migration path; integration data cannot bypass canonical operations or leak mutable static state.
- **Context code cannot infer:** Static artifact evidence shows optional integration helpers, but static presence cannot establish runtime compatibility and the previous register did not require an explicit integration contract.
- **Scope:** Integration registry, capability detection, adapters, compatibility reports, fixtures and release documentation. Owner: compatibility/platform. Metadata: type `integration/reliability`; priority `P2`; milestone `M9`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Bundling or requiring every optional mod, or claiming compatibility from class names alone.
- **Dependencies and open decisions:** P0-1, P0-3, P1-1, P1-4; P9-5 records each integration’s version and evidence state; P11-2 aggregates those records in the final compatibility report and is not a prerequisite for implementing integrations.
- **Verification:** Present/absent mod startup, version mismatch, load order, permission, adapter failure isolation, operation routing, reload and documentation scans.

## M10 — Familiar creator UI and AI authoring

### P10-1 — Build the unified CustomNPCs-style authoring hub

- **Intent:** Make StoryNPCs faster and easier for creators already familiar with CustomNPCs while fixing its linear, fragmented tooling holes.
- **Expectation:** A domain hub opens NPC identity/display/AI/combat/equipment/dialogue/quest/faction/role/job/trade/bank/transport/template/tool/script panels with search, pagination, previews, diagnostics, undo/redo, keyboard navigation and server-authoritative save.
- **Acceptance criteria:** Every traceability row marked UI-applicable has an editor/view; 149 target GUI families are mapped to StoryNPCs screens or explicit improved equivalents; a creator can build one complete NPC workflow without raw YAML; screen state is revisioned; UI automation passes at 854x480 physical resolution with GUI scale 2 and verifies scroll/pagination rather than clipping; error fields identify exact schema path and repair action.
- **Context code cannot infer:** Existing screens cover only a subset of NPC, graph, quest, faction, rules, trade and bank fields.
- **Scope:** Client navigation/components, screen models, editor workflows, preview/diagnostics, UI automation. Owner: UX/content tooling. Metadata: type `ui`; priority `P2`; milestone `M10`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Copying target GUI limitations or embedding an unbounded LLM in the server.
- **Dependencies and open decisions:** P1-1, P1-3, P1-4, P2-1, M3, P5-1, P5-3, P5-4, M6, M7, M8, P9-1, P9-2; exact visual styling is open, workflow/coverage requirements are fixed. `M3`, `M6`, `M7`, and `M8` expand to all issues in those milestones; P10-1 integrates the completed authoring and visual-tool surfaces.
- **Verification:** Field coverage matrix, UI automation, minimum-resolution, keyboard, stale revision, undo/redo, preview and multiplayer tests.

### P10-2 — Define safe AI-generated content and patch plans

- **Intent:** Let an AI read the docs and generate NPCs exactly within the supported contract without raw unrestricted mutation.
- **Expectation:** A versioned schema/reference bundle, examples, deterministic patch-plan format, dry-run validator, diagnostics, allowlist, permission context and rollback path let an external AI produce validated content.
- **Acceptance criteria:** Bundle includes every supported definition schema and operation; patch plans are deterministic/idempotent and reference source locations; dry-run reports errors/warnings/dependencies without writing; apply uses P1-1/P1-4; unsupported target fields are explicit; examples generate an NPC with display/combat/dialogue/quest/role data; failed apply leaves prior revision intact.
- **Context code cannot infer:** Current project vision includes AI authoring but no schema bundle, patch format, validator, or safe apply contract.
- **Scope:** Schema export, docs/reference, patch plan DTO, validator/dry-run/apply adapters, examples. Owner: content tooling/extensibility. Metadata: type `tooling/api`; priority `P2`; milestone `M10`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** An in-game autonomous LLM with unrestricted authority or relying on model memory instead of schema validation.
- **Dependencies and open decisions:** P1-1, P1-4, P2-1, M3, M5, M6, M7, M8, P9-1; default delivery is a CLI/API patch-plan bundle. P10-3 documents and examples this output after P10-2; human documentation is not a prerequisite for defining the machine-readable contract.
- **Verification:** Golden generated plans, idempotency, dry-run/no-write, invalid field, permission, rollback, schema version and example application tests.

### P10-3 — Publish creator documentation, examples, and migration guidance

- **Intent:** Reduce the learning cost for CustomNPCs creators and make the engine AI-readable.
- **Expectation:** Documentation explains every supported target-parity domain, UI path, YAML schema, command/API/script operation, error, migration rule, performance budget, and intentional deviation with runnable examples.
- **Acceptance criteria:** Every issue’s public behavior has one example; docs link to traceability/evidence state; quickstart creates a multi-role NPC; migration guide states accepted input types and unsupported fields; examples are validated in CI; no document claims unverified parity.
- **Context code cannot infer:** Existing README/Business/Decision materials overstate several delivered capabilities.
- **Scope:** README, reference site/docs, examples, migration guide, generated schema/reference bundle. Owner: documentation/content tooling. Metadata: type `documentation`; priority `P2`; milestone `M10`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Replacing executable fixtures with prose.
- **Dependencies and open decisions:** P0-2, P0-4, P10-2; docs generation format may be Markdown/JSON/YAML, but generated output must be deterministic.
- **Verification:** Link/schema/example validation, generated diff, quickstart test, truth-gate scan and fresh-reader walkthrough.

## M11 — Import, evidence closure, release certification

### P11-1 — Define and implement the CustomNPCs import contract

- **Intent:** Provide a real migration path without pretending the supplied engine JAR contains creator-authored world data.
- **Expectation:** Importer accepts explicitly supported sources—YAML/JSON/template packages and, if implemented, target world NBT/export data—reports field-level mappings, unsupported fields, scripts/assets, conflicts, and rollback/quarantine outcome.
- **Acceptance criteria:** Input types and versions are documented; import is deterministic and dry-runnable; namespace/conflict policy is explicit; unsupported target fields are not silently dropped; imported definitions validate/migrate; failed import leaves source and prior StoryNPCs state unchanged; report links each field to P0-1 and an evidence state.
- **Context code cannot infer:** The supplied JAR is an engine artifact, not a creator world. World NBT/export formats require separate evidence.
- **Scope:** Import readers, mapping registry, dry-run/report, quarantine/rollback, CLI/UI adapter. Owner: migration/release. Metadata: type `migration`; priority `P2`; milestone `M11`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Importing decompiled code, arbitrary Java scripts, or unsupported binary formats silently.
- **Dependencies and open decisions:** P0-1, P2-1, P10-3; supported target data source remains `needs-input` until an actual world/export sample exists.
- **Verification:** Golden inputs, conflict/unsupported field, malformed input, dry-run, rollback, migration, asset/script report and idempotency tests.

### P11-2 — Close target command/API/event/network evidence

- **Intent:** Convert static target inventory into an honest compatibility report for every public surface.
- **Expectation:** Every target command leaf, API interface/event, GUI family, packet, role/job class and persistence store has a StoryNPCs mapping, intentional deviation, or unverified/unknown status with evidence required.
- **Acceptance criteria:** P0-1 rows all have terminal mapping states; no target row is unmapped; every `VERIFIED_PARITY` row has target and StoryNPCs runtime evidence; unavailable target probes remain unverified and block the affected certification claim; intentional deviations have user-facing rationale and migration/documentation impact.
- **Context code cannot infer:** Research static coverage is complete enough to inventory, but runtime equivalence is not; target GUI/packet probes may remain unavailable.
- **Scope:** Compatibility report, evidence aggregation, deviation records, release documentation. Owner: compatibility/release. Metadata: type `verification`; priority `P0`; milestone `M11`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Treating class-name or packet-count similarity as behavior parity.
- **Dependencies and open decisions:** P0-1, P0-2, P0-3, P0-4, M1-M10, P11-1; `M1-M10` expands to every issue listed in those milestones. Target runtime access determines which rows can advance beyond static evidence, but unavailable probes remain explicitly unverified.
- **Verification:** Manifest completeness, evidence-state validator, contradiction/unknown report, target/StoryNPCs fixture comparison.

### P11-3 — Run final adversarial certification and release gate

- **Intent:** Prove the complete CustomNPCs-parity goal, pothole fixes, UI workflow, persistence, and MMO performance before release claims are restored.
- **Expectation:** Release certification is a checklist over all milestones, issues, fixtures, migration, permissions, client/server behavior, performance, docs, and adversarial risks.
- **Acceptance criteria:** Issue IDs are unique; every milestone dependency shorthand expands to registered issue IDs; the expanded dependency graph has no dangling edges or cycles; all issues are `done` or explicitly accepted `blocked` with user decision; all 15 operations have evidence-backed fixtures; all 22 domains have no unmapped rows; all three certification benchmark scenarios have evidence artifacts recording each numeric threshold result; full `./gradlew test` passes; importer/docs/AI workflows pass; adversarial review finds no unresolved high-risk issue; unsupported/blocked target probes remain visible in release notes.
- **Context code cannot infer:** Passing unit tests alone cannot prove target parity, packet/UI behavior, or performance.
- **Scope:** Release checklist, benchmark artifacts, full test run, adversarial review, release notes, final roadmap status. Owner: release engineering. Metadata: type `release/audit`; priority `P0`; milestone `M11`; assignee/project/horizon `unassigned/unknown/none`.
- **Non-goals:** Declaring completion when evidence is merely static, indirect, or missing.
- **Dependencies and open decisions:** M0-M10, P11-1, P11-2; `M0-M10` expands to every issue listed in those milestones. No unresolved acceptance ambiguity is allowed at the gate.
- **Verification:** Flux close-issue receipts, `./gradlew test`, live/server/client fixtures, benchmark reports, docs truth scan, adversarial review and clean-checkout reproduction.

## Flow order

The implementation flow must process issues serially in dependency order. The first implementation sequence is:

`P0-1 -> P0-2 -> P0-3 -> P0-4 -> P1-1 -> P2-1 -> P2-2 -> P1-2 -> P1-3 -> P1-4 -> P2-3`, then proceed through M3–M11 in dependency order, including the newly explicit P8-4/P8-5/P8-6 and P9-4/P9-5 issues. P1-2 deliberately follows P2-2 because its acceptance requires durable logical actor state before projection replacement/restart tests.

No issue is considered complete solely because source compiles. The issue’s acceptance evidence, tests, migration/recovery behavior, and independent adversarial review are part of completion.
