# StoryNPCs CustomNPCs-Parity Roadmap

Status: `IMPLEMENTATION-FLOW-IN-PROGRESS` — three independent readiness reviews produced three corrective fixes. P0-4, P1-1, and P1-2 are reopened to `IN-REVIEW` where their acceptance evidence is incomplete; final parity certification remains gated by runtime evidence and P11-3.

This is the active product-direction document. `Milestones.md` records historical implementation work and should not be used as the current parity plan.

The canonical implementation handoff is now split into [CUSTOMNPCS_PARITY_TRACEABILITY.md](CUSTOMNPCS_PARITY_TRACEABILITY.md), [CUSTOMNPCS_PARITY_ISSUE_REGISTER.md](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md), and [CUSTOMNPCS_PARITY_CERTIFICATION.md](CUSTOMNPCS_PARITY_CERTIFICATION.md). The high-level issue drafts later in this file are historical rationale only, are superseded by the granular register, and are not actionable or handoff-ready. Use the issue register for all IDs, acceptance criteria, dependencies, and execution order.

## 1. Product contract

### Goal

Make StoryNPCs a clean-room, modern CustomNPCs-compatible engine for Minecraft 1.21.1:

1. Reach user-facing feature parity with the supplied CustomNPCs Unofficial build.
2. Remove the old engine's holes: brittle persistence, coupled state, fixed dialogue limits, weak authoring, silent failures, unmanaged scheduling, and desynchronization.
3. Scale to MMO-style worlds and large battles through explicit runtime budgets, level of detail, bounded path work, and measurable battle fixtures.
4. Make the same content authorable through YAML, commands, a modern UI, typed API, and a documented AI-assisted generation workflow.

“Parity” means observable behavior and authoring capability, not copying CustomNPCs' internal classes, file formats, or implementation.

**Citizens is not the product target.** It is only a comparator for selected architecture patterns. A Citizens-like feature set, API, editor, or workflow does not satisfy this roadmap unless it also closes the corresponding CustomNPCs parity requirement.

### Constraints

- Definitions remain YAML-first, namespaced, versioned, and read-only at runtime.
- Every mutation has one canonical `StoryNpcsApplicationService` operation path. Commands, packets, UI, API, and scripts are adapters.
- Dialogues remain directed graphs; the old fixed-option model is not reintroduced.
- Runtime state is separate from content definitions and uses versioned, recoverable persistence.
- No mutable static singleton or player/NPC selection cache is used for runtime state.
- No performance claim is accepted without a reproducible benchmark and a profiler/telemetry result.
- No decompiled or copied target implementation is used; the JAR is behavioral and inventory evidence only.
- Every production domain operation receives JUnit 5 coverage; `./gradlew test` is required before declaring an implementation milestone complete.

### Definition of done for the roadmap

The roadmap is complete only when all 15 target operation families have a passing parity fixture and each applicable surface is proven:

`YAML -> service operation -> command / UI / API / script adapter -> event -> persistence -> reload/recovery`.

The final certification must include target-specific fixtures, client/server interaction tests, migration tests, and production-style benchmarks for 500 NPCs, a 25v25 battle, and a 2,500-NPC stress population.

## 2. Instructions versus evidence

| Source | Classification | How it is used |
|---|---|---|
| The user request | Product intent and authority | Defines the direction: CustomNPCs foundation, parity first, pothole removal, MMO scale, better UI, data-driven and AI-friendly authoring. |
| `AGENTS.md` in this repository | Binding engineering constraints | Governs architecture, testing, YAML-first definitions, canonical operations, graph dialogue, and persistence separation. |
| `dwurdys-storynpcs-research/AGENTS.md` and `REQUIREMENTS.md` | Research-workspace instructions | Govern how the comparison evidence was gathered; they do not authorize production implementation in this repository. |
| Supplied CustomNPCs JAR | Evidence artifact, not instructions | Defines the target's classes, GUI, packets, commands, roles, jobs, events, and observable feature surface. |
| Research reports and generated inventories | Evidence and analysis | Provide hashes, inventories, static findings, comparator research, and explicit uncertainty labels. |

## 3. Evidence baseline

### Target artifact

The supplied file is the exact research target:

- File: `CustomNPCs-Unofficial-NeoForge-1.21.1.20251230.jar`
- SHA-256: `6c28d87b215fc1191488194188ec8a39dd908ae7d2d887b7c9d7d463be0a162c`
- SHA-1: `e2f3b58ceb5aac4021d7bfd130e320925581471b`
- Archive inventory: 2,467 entries, 1,230 classes, 972 assets, 49 data entries.
- Decompilation: 1,036 Java files using Vineflower 1.12.0.
- Target surface found in the artifact: 149 GUI source files, 155 network payloads, 70 executable command leaves, 21 concrete role/job/companion classes (7 roles, 11 jobs, 3 companion jobs), and 97 API event classes.

The research phase is explicitly incomplete for graphical runtime probing and semantic command/API/script equivalence. Static inventory is strong evidence of a feature surface; it is not proof that every target behavior is bug-free or desirable.

### StoryNPCs baseline

- The repository has a real YAML/graph/service foundation, not an empty prototype.
- Current implementation has 146 main Java files, approximately 15,530 Java lines, 41 test files, and 11 resource files.
- `./gradlew.bat test --console=plain` currently passes with `BUILD SUCCESSFUL`.
- Existing implementation is partial: NPCs, graph dialogue, quests, factions, rules, trading, banking, and a follower role exist in some form.
- Current implementation has no scripting package, no transport system, no jobs system, no equipment/drop system, no target-equivalent world tools, and no production LOD/async-path/squad runtime.
- Existing README/research performance claims are not acceptance evidence until the corresponding Java implementation and benchmark fixtures exist.
- The worktree contains user changes. This roadmap does not overwrite or normalize them.

## 4. Current parity verdict

Status meanings: `Partial` means useful behavior exists but target capability or one or more authoring surfaces are missing; `Missing` means no production implementation was found; `Unverified` means the claim needs a runtime fixture.

| Target operation/domain | Current StoryNPCs verdict | Specific hole to close |
|---|---|---|
| NPC identity/lifecycle | Partial | Stable logical actor identity, spawn/despawn lifecycle reasons, variants, and lifecycle events are not complete. |
| Display/model | Partial | Basic name/title/skin/scale exists; cloak, glow, boss bar, tint, model variants, animation and renderer extension are missing. |
| Stats/combat | Partial | Basic melee health/damage/speed/aggro exists; regen, knockback, attack speed/range, resistances, ranged attacks, projectile effects, defeat modes and drops are missing. |
| AI/movement/targeting | Partial | Standing, wandering, pathing, return, door/water flags and tactical fields exist; target policy matrix, tactical behaviors, path cache, LOD and bounded scheduling are missing. |
| Factions/reputation | Partial | Points and thresholds exist; faction relationship matrix, hostile/friendly semantics, shared/team progression and complete mutation coverage are missing. |
| Inventory/equipment/drops | Missing | The NPC inventory field is not a target-equivalent equipment/container/drop system. |
| Dialogue definition/player choice | Partial | Graph runtime is stronger than the target's fixed slots; editor/runtime coverage for general conditions, actions, localization, voice/assets and authoritative choice tokens is incomplete. |
| Quests | Partial | Five objective types exist; target repeat modes, mail rewards, dependencies, team progress, transactional turn-in and complete editor coverage are missing. |
| Roles | Partial | Trader, banker and follower exist; transporter, dialogue/postman/mail, healer and bard capabilities are missing. |
| Jobs | Missing | Guard, spawner, item giver, follower, bard, conversation, healer, puppet, builder and chunk-loader job capabilities are missing. |
| Transport | Missing | Locations, categories, unlocks, cross-dimension teleport and player transport UI are missing. |
| Trade | Partial | One-input barter and player UI exist; two-input offers, pages, admin editing, restock correctness and transaction recovery are missing. |
| Banking | Partial | Player vault and bank role exist; admin bank definition/editor, durable revisions, rollback and search/sort UX are missing. |
| Companion/follower | Partial | Follow/stay/guard and formation exist; wages, stages, talents, companion inventory/stats and job integration are missing. |
| Spawn/clone/templates | Partial | In-memory per-player cloning exists; named persistent templates, spawner blocks, quotas and safe imports are missing. |
| Marks and visual tools | Partial | One NPC mark exists; collections, availability, visibility and the remaining creator tools are missing. |
| Scripting/API/integrations | Missing | No production scripting package or public extension API equivalent was found. |
| Commands | Partial | Core commands exist; the target's full command surface and parity fixtures do not. |
| Networking | Partial | Current packets route some operations; the current surface now has versioned/bounded codecs, request-correlated mutation results, and server-issued dialogue/trade/bank sessions. Full target packet mapping, fuzz coverage, and all stale-screen refresh paths remain open. |
| Persistence/migrations | Partial | YAML plus temporary replacement exists; schema versions, migrations, explicit durability, recovery semantics and stores for missing domains are incomplete. |
| UI/authoring | Partial | Graph, NPC, quest, faction, rules, trade and bank screens exist; target-domain coverage, search/navigation, reusable property editing, diagnostics, preview, undo and responsive authoring are incomplete. |
| MMO performance | Unverified / effectively Missing | No production LOD, flyweight archetypes, squad coordinator, bounded async path scheduler or reproducible battle benchmark exists in Java. |

## 5. How other systems solve the hard parts

These are design references, not implementation dependencies or product targets. CustomNPCs remains the compatibility baseline; the comparator projects below are used only to avoid reinventing proven solutions to individual problems.

- Citizens separates stable NPC identity from the Minecraft entity projection and exposes traits, navigator state, persistent location, and lifecycle-oriented APIs. StoryNPCs should use the same separation for logical actors, entity instances, and capabilities. See [Citizens NPC API](https://github.com/CitizensDev/CitizensAPI/blob/master/src/main/java/net/citizensnpcs/api/npc/NPC.java).
- Easy NPC demonstrates a modular core/UI split, UUID-keyed instance state, dirty-state persistence, and per-instance isolation tests. StoryNPCs should adopt the isolation and test shape, while keeping YAML as the content source.
- Taterzens uses explicit movement/follow/sound/permission data and reusable editing selectors. This is a useful model for capability-based authoring rather than one giant editor screen.
- MineColonies uses bounded citizen handlers/jobs and treats the entity as a projection of durable citizen state. MCA likewise keeps social state separate from entity lifetimes. This supports jobs, companions, and MMO-scale scheduling.
- BetonQuest and FTB Quests use typed registries, dependency-aware progression, repeat rules, rewards, team state, events, and migrations. StoryNPCs needs the same explicit progression vocabulary and recovery behavior.
- Ink and Yarn Spinner model dialogue as nodes, lines, options, variables, commands, localization keys, diagnostics, and a host runtime. StoryNPCs should preserve its graph model while adopting these explicit data contracts.
- Denizen separates platform adapters from a core scripting model. KubeJS uses typed event groups, registries, wrappers, and capability filters. StoryNPCs should expose a bounded host API instead of unrestricted reflection or arbitrary server authority.
- NeoForge's screen, SavedData, and Data Components APIs show the platform-native patterns for typed client UI, world-scoped durable state, and versioned item/entity data: [Screens](https://docs.neoforged.net/docs/1.21.3/gui/screens/), [SavedData](https://docs.neoforged.net/docs/1.21.5/datastorage/saveddata/), and [Data Components](https://docs.neoforged.net/docs/1.21.3/items/datacomponents/).

## 6. Architecture decisions to carry through every milestone

1. **Stable actor projection.** A namespaced `NpcId` and durable actor record own content, progression and capabilities. A Minecraft entity UUID is a live projection and can be recreated without changing the actor identity.
2. **Typed canonical operations.** Introduce operation request/result types carrying actor identity, player/server actor context, capability checks, definition revision, idempotency key, diagnostics, and event outcome. Commands, packets, UI, API and scripts only translate into these operations.
3. **Versioned content.** Every YAML document has a schema version, deterministic migration chain, forward-version refusal, source location diagnostics, and stable IDs. Runtime receives immutable compiled definitions.
4. **Capability registry.** Roles, jobs, conditions, actions, objectives, rewards, render features and script bindings are registered capabilities with explicit ownership and validation.
5. **Transactional state.** Trade, bank, quest rewards, faction changes, companion wages and teleport unlocks use revision checks, bounded transactions, durable commit records, and a recoverable failure result.
6. **Budgeted simulation.** Active, nearby, distant, dormant and unloaded NPC tiers control sensing, navigation, animation, combat evaluation and persistence frequency. Async work is bounded and never mutates world state off-thread.
7. **Authoring as a product.** Use a searchable domain hub, reusable property panels, preview/diagnostic panes, pagination, undo/redo, import/export, server-authoritative validation, keyboard navigation, and an AI-readable schema/reference bundle.
8. **Scripting as a capability boundary.** Scripts receive typed event snapshots and explicitly granted operations. Time, memory, recursion, command count, and world mutation are bounded and observable.

## 7. Executable milestones and issue drafts

The following superseded drafts are retained for rationale only. They are not implementation tickets and must not be copied into a tracker. Priority labels and issue IDs below may conflict with the canonical issue register. Owners are intentionally unassigned.

### M0 — Truth baseline and certification fixtures

Outcome: the project can distinguish delivered, partial, missing, and unverified behavior without stale claims.

#### P0-1 — Freeze the target-specific parity matrix

- **Intent:** Convert the exact JAR inventory and research findings into a machine-readable parity matrix owned by this repository.
- **Expectation:** Each target capability has an ID, source evidence, StoryNPCs status, applicable surfaces, and a fixture reference.
- **Acceptance criteria:** 15 operation families and 22 target domains are represented; every current “complete” claim has evidence; unsupported performance claims are marked unverified; matrix diff is reviewable.
- **Context code cannot infer:** Target artifact SHA-256 above; source research is in `E:/Github2/dwurdys-storynpcs-research`; this repository's existing `Milestones.md` is historical.
- **Scope:** `docs/`, parity metadata, report links. Owner: tooling/documentation. Metadata: `research`, `parity`, priority P0.
- **Non-goals:** No runtime implementation and no copying target code.
- **Dependencies/open decisions:** None; compatibility means user-facing behavior unless later narrowed.
- **Verification:** Validate artifact hash, inventory counts, matrix completeness, and links in CI or a deterministic Gradle task.

#### P0-2 — Build the black-box parity fixture harness

- **Intent:** Make target behavior and StoryNPCs behavior comparable through named scenarios.
- **Expectation:** Fixtures cover NPC creation/display/combat, dialogue choice, quest completion, faction changes, trade, bank, follower, clone, and reload/recovery; unavailable graphical probes are explicitly marked.
- **Acceptance criteria:** A fixture can run against StoryNPCs; expected results are structured; target observations have provenance; a missing probe produces `UNVERIFIED`, not a pass.
- **Context code cannot infer:** Research Phase 1 has limited runtime evidence and no proven semantic equivalence.
- **Scope:** test fixtures, scenario schema, evidence reporter. Owner: test infrastructure. Metadata: `testing`, `parity`, priority P0.
- **Non-goals:** Full target reimplementation or automated GUI scraping.
- **Dependencies/open decisions:** P0-1; target runtime access for any new black-box observation.
- **Verification:** Run fixture suite and inspect generated summary for false-positive passes.

### M1 — Stable actors and canonical operations

Outcome: every mutation is authoritative, typed, replay-safe, and independent of GUI/command implementation details.

#### P1-1 — Introduce the typed operation dispatcher

Status: `IN-REVIEW`. A typed dispatcher exists, but the architecture review found direct adapter mutations and incomplete typed migration for progression/economy operations. See [P1-1 closeout](parity/P1-1-CLOSEOUT.md); this does not certify full target parity.

- **Intent:** Replace direct field mutation plus broad `save*` calls with typed application operations.
- **Expectation:** Requests include actor/capability context, target ID, expected revision, idempotency key, and validated payload; results return diagnostics and domain events.
- **Acceptance criteria:** NPC, dialogue, quest, faction, trade, bank, follower and rule mutations route through the dispatcher; command and packet adapters contain no business mutation; duplicate requests are safe; stale revisions fail explicitly.
- **Context code cannot infer:** Existing `StoryNpcsApplicationService` is large and partially canonical but does not yet enforce this contract.
- **Scope:** service layer, request/result DTOs, command/network adapters, tests. Owner: application architecture. Metadata: `architecture`, priority P0.
- **Non-goals:** New target features beyond the operations needed to migrate current behavior.
- **Dependencies/open decisions:** P0-1; exact actor permission model can start with server/player/admin capabilities.
- **Verification:** JUnit per operation, duplicate/replay tests, stale-revision tests, adapter inspection, full Gradle test.

#### P1-2 — Separate stable actors and replace mutable static runtime state with managed lifecycles

Status: `IN-REVIEW`. Stable actor/projection coverage exists, but mutable static lifecycle dependencies remain and require repository-wide scan/isolation evidence. See [P1-2 closeout](parity/P1-2-CLOSEOUT.md).

- **Intent:** Remove static player selections, throttles, clone templates, follower groups, and event-owned mutable maps while giving NPCs stable logical identities independent of entity UUID projections.
- **Expectation:** State is scoped to server/world/player/actor lifecycle and is cleaned on logout/unload/reload; actor lifecycle transitions are explicit and observable.
- **Acceptance criteria:** No mutable static runtime collections remain; logical actor identity survives entity replacement and restart; projection failures are retryable and reported; reload and two-server-instance isolation tests pass.
- **Context code cannot infer:** Current static state exists in `StoryNpcs`, `StoryNpcsNetwork`, `NpcClonerItem`, `NpcMounterItem`, and `NpcPathItem`, among other event state.
- **Scope:** actor registry, lifecycle service, entity projection, atomic actor-state persistence, packet context, tool sessions, cleanup hooks, tests. Owner: runtime foundation. Metadata: `architecture`, `correctness`, priority P0.
- **Non-goals:** Removing harmless static registries required by NeoForge registration.
- **Dependencies/open decisions:** P1-1.
- **Verification:** static scan plus isolation, logout, reload, and unload tests.

#### P1-3 — Version and harden the network protocol

Status: `DONE-LOCAL`. Current StoryNPCs payload hardening is implemented and verified locally; target packet mapping remains open. See [P1-3 closeout](parity/P1-3-CLOSEOUT.md).

- **Intent:** Make GUI and interaction packets bounded, explicitly versioned, replay-safe, and authoritative without preserving undocumented target wire formats.
- **Expectation:** Every registered payload rejects unknown schema versions and oversized frames; JSON, IDs, messages, and lists have explicit limits; mutation results carry a code, request ID, and revision; dialogue and role interactions require server-issued session tokens.
- **Acceptance criteria:** Current codecs round-trip; malformed/oversized/unknown-version frames fail before business handlers; duplicate request IDs are rejected within a bounded per-player replay window; dialogue/trade/bank tokens are invalid after replacement or logout; mutation results correlate to the originating request; handlers remain thin adapters over canonical service operations. The target's 115/40 packet mapping is still a separate `UNVERIFIED` gate.
- **Context code cannot infer:** NeoForge's registrar version negotiates a connection, but it does not by itself version each DTO, bound aggregate payload size, or provide application-level request/session identity.
- **Scope:** `MutationProtocolCodecs`, network payload DTOs, `RuntimeSessionRegistry`, interaction screens, handlers, and tests. Owner: networking. Metadata: `security/protocol`, priority `P0`.
- **Non-goals:** Copying target packet internals or treating client-supplied indexes/JSON as authority.
- **Dependencies/open decisions:** P1-1, P1-2; current limits are 64 KiB ordinary payloads, 1 MiB editor documents, 4 MiB registry snapshots, and 2 MiB role snapshots.
- **Verification:** Focused codec/replay tests, independent adversarial review, full `./gradlew test --console=plain --rerun-tasks`, truth gate, and diff/static scans.

#### P1-4 — Enforce actor capability and authorization policy

Status: `IN-REVIEW`. Canonical definition mutations now evaluate a fail-closed actor/capability policy; broader command/script/economy/world-tool integration remains open. See [P1-4 closeout](parity/P1-4-CLOSEOUT.md).

- **Intent:** Prevent clients, scripts, commands, and future creator tools from bypassing server authority or invoking unregistered capabilities.
- **Expectation:** Canonical mutation requests identify actor type, capability, target, revision, request ID, and—when a player is the actor—fresh server-checked permission proof.
- **Acceptance criteria:** Unknown actors/capabilities fail without operation execution; unproven player definition mutations fail with an audit event and structured diagnostics; script escalation is denied until an explicit capability grant exists; accepted network editor requests carry level-2 proof; replay behavior remains governed by P1-1.
- **Context code cannot infer:** Existing code used scattered `hasPermissions(2)` checks and request metadata without a single policy evaluator.
- **Scope:** `MutationRequest`, `AuthorizationPolicy`, canonical service boundary, network editor adapters, tests. Owner: security/runtime. Metadata: `security`, priority `P0`.
- **Non-goals:** Granting arbitrary OP authority to scripts or making a client-supplied integer authoritative.
- **Dependencies/open decisions:** P1-1 and P1-3; NeoForge permission-node resolution remains in adapters, with the service consuming the verified result.
- **Verification:** Allow/deny matrix, no-side-effect denial, audit event, replay, permission-change, and full Gradle test.

### M2 — Versioned YAML and durable state

Outcome: definitions migrate deterministically and progression/economy state survives crashes without silent loss.

#### P2-1 — Version and migrate every definition schema

Status: `IN-REVIEW` — the shared version envelope is implemented for the current NPC/dialogue/faction/quest loader and generic writer; role/job/template/tool/world schema-family fixtures remain open. See [P2-1 closeout](parity/P2-1-CLOSEOUT.md).

- **Intent:** Make NPC, dialogue, quest, faction, role, job, rule, template and world-tool YAML forward-safe.
- **Expectation:** Schemas have versions, migrations, stable IDs, strict diagnostics, and a refusal path for unknown future versions.
- **Acceptance criteria:** Current files load unchanged; at least one fixture migration exists for each schema family; unknown versions fail with file/field diagnostics; cross-reference errors are actionable.
- **Context code cannot infer:** Current YAML loader/writer and validator exist, but versioned migration coverage is incomplete.
- **Scope:** schema records, migrations, loader/writer diagnostics, fixtures. Owner: content platform. Metadata: `data-model`, priority P0.
- **Non-goals:** Changing YAML into a binary-only format.
- **Dependencies/open decisions:** P1-1; decide whether generated YAML is canonical or normalized on write.
- **Verification:** migration round trips, malformed input tests, deterministic serialization, full Gradle test.

#### P2-2 — Make progression and economy persistence recoverable

Status: `IN-REVIEW` — a shared versioned, forced-write, three-generation backup/recovery store now backs progression, bank, and actor state; the full 18-store mapping and injected crash matrix remain open. See [P2-2 closeout](parity/P2-2-CLOSEOUT.md).

- **Intent:** Complete atomic, durable, revisioned persistence for all mutable state.
- **Expectation:** Temp write, flush, atomic replace, backup/recovery, corruption diagnostics, and schema migration are one reusable store policy.
- **Acceptance criteria:** Crash-at-each-commit-stage tests recover the last valid state; bank/trade/quest/faction/companion state has revisions; corrupt files are quarantined and reported; reload does not duplicate rewards or charges.
- **Context code cannot infer:** Existing stores use temporary replacement but do not establish full flush/recovery/transaction semantics.
- **Scope:** repository abstraction, world/player state, transaction journal, recovery tests. Owner: persistence. Metadata: `persistence`, `reliability`, priority P0.
- **Non-goals:** Supporting arbitrary third-party save formats without an importer milestone.
- **Dependencies/open decisions:** P2-1; choose NeoForge SavedData versus repository files per state domain.
- **Verification:** fault injection, duplicate commit, corruption, migration, restart tests.

#### P2-3 — Add operation-specific transaction and recovery fixtures

Status: `IN-REVIEW` — bank mutations use a snapshot/commit/restore boundary, the live held-item deposit owns inventory removal with a vault-side marker and login replay, trade request IDs now use a durable journal with subject binding, listing-use state now uses a durable reserve/rollback store kept out of YAML, quest reward preflight prevents known invalid/non-atomic fan-out, and live trade uses owned reservations with payment compensation; inventory-side crash proof and remaining operation-specific restart/idempotency work remain open. See [P2-3 closeout](parity/P2-3-CLOSEOUT.md).

- **Intent:** Prove that trade, bank, quest rewards, faction changes, companion wages, equipment, and teleport unlocks cannot duplicate or lose value.
- **Expectation:** Each multi-step operation has a commit boundary, compensation/recovery path, idempotency behavior, and user-visible failure result.
- **Acceptance criteria:** Trade never charges without output; bank never removes without durable deposit; quest completion never duplicates rewards; equipment rejects without loss; wages and teleport unlocks are exactly-once; crash/retry/stale-revision/full-inventory/failed-command cases each produce a deterministic result.
- **Context code cannot infer:** Quest completion now preflights known invalid/non-atomic rewards and persists completion after supported fan-out, but crash recovery between external rewards and progression persistence is still open; trade and paid bank unlock still cross multiple mutable systems without an operation journal.
- **Scope:** Transaction fixtures, journal hooks, service integration, failure diagnostics. Owner: persistence/economy/progression. Metadata: `testing/reliability`, priority `P0`.
- **Non-goals:** Making arbitrary third-party commands transactional without a defined adapter contract.
- **Dependencies/open decisions:** P1-1, P1-3, P2-2; command rewards are either compensatable or reported as non-atomic before commit.
- **Verification:** Fault injection at each side effect, duplicate request, restart, full-container, permission and migration tests.

### M3 — Core NPC parity

Outcome: an NPC can be authored and run with the core display, combat, inventory, targeting, equipment, marks, and lifecycle behaviors CustomNPCs users expect.

#### P3-1 — Implement core display, stats, combat, and defeat behavior

Status: `IN-PROGRESS` — the display-contract slice is now implemented with bounded YAML fields and renderer name modes; combat, full render projection, and the remaining P3-1 surfaces remain open. See [P3-1 progress](parity/P3-1-PROGRESS.md).

- **Intent:** Close the NPC and combat gap without coupling content to entity classes.
- **Expectation:** Display/model features, health/regen, melee and ranged attacks, resistances, projectile effects, aggro/retaliation, defeat modes, respawn, and lifecycle events are data-driven capabilities.
- **Acceptance criteria:** YAML, command, UI, service, event, reload, and parity fixtures exist for each supported field; server authority is preserved; entity projection can respawn from the same logical actor.
- **Context code cannot infer:** Current `NpcDisplay`, `NpcStats`, and `StoryNpcEntity` cover only a subset of target fields.
- **Scope:** domain schemas, entity projection, combat capabilities, render data, events, tests. Owner: NPC runtime. Metadata: `parity`, `combat`, priority P1.
- **Non-goals:** Reproducing every target model wrapper one-for-one before the render capability contract is stable.
- **Dependencies/open decisions:** M1, M2; model asset strategy and GeckoLib/vanilla renderer boundary are `needs-input`.
- **Verification:** target-specific combat fixtures, client render smoke tests, damage/respawn tests, JUnit and live server test.

#### P3-2 — Add equipment, inventory, drops, marks, and creator-item contracts

- **Intent:** Replace the current string inventory and limited mark/tool support with authoritative containers and creator capabilities.
- **Expectation:** Paperdoll/equipment, inventory slots, drop chances/XP/looting, mark collections, availability, and server-authoritative tool sessions are reusable capabilities.
- **Acceptance criteria:** Equip/unequip/drop and mark operations are revisioned service operations; full/invalid containers fail without item loss; tool selections survive packet boundaries without static state; creator items have permission and diagnostics.
- **Context code cannot infer:** Current NPC inventory is a list of strings; target has equipment, drop, mark, tool and container surfaces not represented here.
- **Scope:** container model, equipment projection, marks, tools, UI/API adapters, tests. Owner: domain/runtime. Metadata: `parity`, `authoring`, priority P1.
- **Non-goals:** Arbitrary client-authoritative item manipulation.
- **Dependencies/open decisions:** P1-2, P2-2; item serialization policy must use NeoForge data components where appropriate.
- **Verification:** item identity/quantity tests, restart/recovery tests, permission tests, packet fuzz tests.

### M4 — MMO-scale simulation and battle runtime

Outcome: scale is an implemented, observable subsystem rather than a README claim.

#### P4-1 — Implement budgeted LOD, archetypes, path scheduling, and squads

- **Intent:** Support dense NPC populations while preserving important nearby combat behavior.
- **Expectation:** NPC simulation tiers control sensing/path/animation/persistence cost; immutable archetypes reduce per-instance memory; path work is bounded and world mutation remains on the server thread; squad coordination avoids N-squared scans.
- **Acceptance criteria:** Tier transitions are deterministic; per-tick budgets are configurable and observable; path queues have limits/cancellation; 25v25 combat uses squad/threat coordination; unloaded entities do not retain player/world references.
- **Context code cannot infer:** Current Java source contains no production LOD, async path worker, squad coordinator, or flyweight implementation; research performance numbers are prototypes, not delivered behavior.
- **Scope:** runtime scheduler, navigation, combat coordination, metrics, tests. Owner: performance/runtime. Metadata: `performance`, priority P1.
- **Non-goals:** Unbounded asynchronous world access or sacrificing nearby combat correctness for a headline entity count.
- **Dependencies/open decisions:** P3-1; define default simulation budgets and acceptable combat degradation.
- **Verification:** profiler traces, tick-budget assertions, heap measurements, path cancellation tests, 25v25 fixture.

#### P4-2 — Add reproducible density and siege benchmarks

- **Intent:** Turn the MMO target into a regression gate.
- **Expectation:** Headless and live-server fixtures measure tick time, heap/entity memory, path queue depth, packet volume, and correctness under 500 NPCs, 25v25 combat, and 2,500 NPCs.
- **Acceptance criteria:** Benchmarks run from a clean checkout; environment/configuration is recorded; p50/p95/p99 and failure thresholds are reported; every optimization claim links to a before/after result.
- **Context code cannot infer:** Existing research contains directional claims, but no production benchmark harness was found in Java.
- **Scope:** benchmark module, fixtures, metrics export, CI/manual gate. Owner: performance verification. Metadata: `benchmark`, `acceptance`, priority P1.
- **Non-goals:** Claiming a universal TPS guarantee across arbitrary modpacks.
- **Dependencies/open decisions:** P4-1; choose supported hardware and test seed.
- **Verification:** repeated runs, profiler artifact review, correctness checks during load.

### M5 — Dialogue and quest parity

Outcome: graph dialogue and quest progression cover the target feature surface while retaining better data model and UX.

#### P5-1 — Complete authoritative dialogue runtime parity

- **Intent:** Make graph dialogue cover availability, lines, options, sounds, commands, variables, effects, sessions, and lifecycle events.
- **Expectation:** Choices use server-issued opaque tokens or revisions, not trusted client indexes; conditions/actions are typed extensible capabilities; localization and assets have stable IDs.
- **Acceptance criteria:** All supported target dialogue conditions/actions have YAML, service, UI, command/API/script adapter where applicable, event, and reload fixtures; invalid/stale choice requests are safe; session timeout and reconnect behavior is explicit.
- **Context code cannot infer:** Current runtime has graph sessions and several condition/action types, but the editor exposes only a narrow action subset and packets use option indexes.
- **Scope:** dialogue domain, session protocol, localization/assets, events, tests. Owner: narrative runtime. Metadata: `parity`, `dialogue`, priority P1.
- **Non-goals:** Reverting to fixed six/twelve-option arrays.
- **Dependencies/open decisions:** M1, M2; choose localization source format and voice asset policy.
- **Verification:** graph traversal, availability, stale token, reconnect, sound/localization, command-effect fixtures.

#### P5-2 — Complete quest objectives, repeats, rewards, dependencies, and authoring

- **Intent:** Match target quest capability while adding safe transactional completion and graph-friendly dependencies.
- **Expectation:** Item/kill/dialog/location/manual objectives, all supported repeat modes, dependencies, team/shared progress, item/XP/faction/mail/command rewards, and explicit turn-in state are typed and recoverable.
- **Acceptance criteria:** No reward duplication or partial silent completion; full inventory and failed command effects return actionable results; quest log/editor shows dependencies and progress; YAML and UI round-trip all fields.
- **Context code cannot infer:** Current implementation has five objectives and three repeat modes, no mail reward or transactional reward fan-out.
- **Scope:** quest state machine, reward transaction, team progress, mail capability, editor/player UI, tests. Owner: progression. Metadata: `parity`, `quest`, priority P1.
- **Non-goals:** Unbounded arbitrary quest scripting in the core state machine.
- **Dependencies/open decisions:** P2-2, P5-1; define team ownership semantics.
- **Verification:** repeat/reset/reload tests, reward fault injection, multi-player progress fixtures, editor round trip.

### M6 — Factions, roles, jobs, companions, and transport

Outcome: target social and service NPC capabilities are first-class, composable capabilities rather than special cases.

#### P6-1 — Implement capability-based factions, roles, jobs, companions, and transport

- **Intent:** Close the broadest missing domain cluster with one consistent capability registry.
- **Expectation:** Faction relation matrices; trader/bank/follower/transporter/dialogue/postman/healer/bard roles; guard/spawner/item-giver/follower/bard/conversation/healer/puppet/builder/chunk-loader jobs; companion wages/stages/talents; and transport locations/unlocks/cross-dimension travel are data-driven.
- **Acceptance criteria:** Each capability has schema, validator, service operation, event, persistence, command/API/UI adapter, permission checks, and fixture; jobs have explicit tick budgets and lifecycle cleanup; transport validates destination and failure recovery.
- **Context code cannot infer:** Current roles are limited to trader/banker/follower; jobs and transport are absent; current follower has no wages/stages/talents.
- **Scope:** capability registry, domain models, schedulers, role/job UI, player UI, tests. Owner: gameplay systems. Metadata: `parity`, `roles`, priority P2.
- **Non-goals:** Copying target role inheritance or hardcoding one class per future job.
- **Dependencies/open decisions:** M1, M2, M4; define job tick budgets and cross-dimension failure policy.
- **Verification:** capability isolation, lifecycle cleanup, wage/repeat/reload tests, teleport safety tests, role/job parity fixtures.

### M7 — Economy and server-authoritative containers

Outcome: trade, bank, companion inventory, and equipment are safe under concurrency, failure, and reconnect.

#### P7-1 — Build transactional trade, bank, and container services

- **Intent:** Remove item-loss, duplication, stale-screen, and admin-authoring holes.
- **Expectation:** Two-input barter, pages, uses/restock, bank tabs/upgrades, equipment/container revisioning, admin editors, player UI, and durable commit/recovery share one transaction policy.
- **Acceptance criteria:** Concurrent requests serialize or fail with revision diagnostics; no charge without output; no output without charge; full inventory has a defined fallback; restock survives restart; admin/player surfaces round-trip the same data.
- **Context code cannot infer:** Current trader supports one input and one output, current bank is player-facing, and transaction/reward rollback is incomplete.
- **Scope:** economy domain, transaction engine, container protocol, admin/player screens, tests. Owner: economy. Metadata: `parity`, `reliability`, priority P2.
- **Non-goals:** Introducing an unbounded generic economy before item transactions are correct.
- **Dependencies/open decisions:** P2-2, P3-2, M6 companion inventory.
- **Verification:** concurrency tests, crash injection, duplicate packet tests, full/invalid input tests, player/admin UI fixtures.

### M8 — Creator tools and world systems

Outcome: creators can build, clone, place, route, and manage content at the speed and breadth expected from CustomNPCs.

#### P8-1 — Implement persistent templates, spawners, path/world tools, and safe creator workflows

- **Intent:** Replace in-memory cloning and missing tools with persistent, permissioned creator capabilities.
- **Expectation:** Named templates, versioned imports/exports, spawner configuration/quota, path/waypoint editing, teleporter, soulstone/remover equivalents, scenes, scripted blocks/doors, mailbox/redstone/banner tools, and diagnostics exist as capabilities.
- **Acceptance criteria:** Tools use server-authoritative sessions; templates survive restart and schema migration; spawners enforce quotas and cleanup; world mutations are validated, logged, reversible where possible, and never run from untrusted client coordinates alone.
- **Context code cannot infer:** Current cloner and path/mounter selections use static maps; target includes substantially more tools and world integrations.
- **Scope:** tools, template store, world mutation service, creator UI, commands, tests. Owner: creator platform. Metadata: `authoring`, `world-tools`, priority P2.
- **Non-goals:** Arbitrary world-edit permissions for ordinary NPC scripts.
- **Dependencies/open decisions:** M1, M2, M6; decide which target tools are intentionally improved or omitted.
- **Verification:** restart/import/export, permissions, quota, chunk unload, rollback/logging, multiplayer race tests.

### M9 — Scripting, public API, and integrations

Outcome: advanced creators can extend StoryNPCs without bypassing authority, persistence, or performance budgets.

#### P9-1 — Deliver a bounded typed scripting host and public extension API

- **Intent:** Replace the current absent scripting surface with a safe, documented extension boundary.
- **Expectation:** Typed events, NPC/dialogue/quest/faction/role/job APIs, explicit capability grants, command registration, registries, diagnostics, quotas, and platform adapters are available.
- **Acceptance criteria:** Scripts cannot mutate state except through canonical operations; execution has time/memory/recursion/command limits; failures are isolated and observable; API/version compatibility is tested; KubeJS/Denizen-style adapters can be added without core coupling.
- **Context code cannot infer:** No production scripting package or public extension API equivalent was found.
- **Scope:** API packages, host/runtime, sandbox policy, bindings, docs, tests. Owner: extensibility. Metadata: `api`, `scripting`, priority P2.
- **Non-goals:** Unrestricted Java reflection, arbitrary filesystem access, or silent OP command execution.
- **Dependencies/open decisions:** P1-1, M5, M6; `needs-input`: choose embedded script runtime versus external KubeJS-first integration. Recommendation: typed host API first, external adapter second.
- **Verification:** permission/escape tests, quota tests, reload/version tests, script failure isolation, API examples compiled in CI.

### M10 — Modern authoring and AI-readable workflow

Outcome: the engine is easier and faster to use than CustomNPCs while preserving expert power.

#### P10-1 — Build the unified authoring hub and generation contract

- **Intent:** Replace fragmented, subpar tooling with a searchable, diagnosable, reusable authoring workflow.
- **Expectation:** Domain hub, search/filter/pagination, reusable property panels, dependency navigation, preview, undo/redo, keyboard support, inline diagnostics, import/export, responsive screens, and one canonical machine-readable schema/reference bundle exist.
- **Acceptance criteria:** A creator can create an NPC with display/combat/dialogue/quest/role data without editing raw YAML; every UI field has a YAML and canonical-operation mapping; AI-generation docs include schemas, examples, validation errors, and safe patch instructions; incomplete definitions explain exactly what is missing.
- **Context code cannot infer:** Existing editors cover only a subset of fields and the dialogue editor exposes only a narrow action surface.
- **Scope:** client UI framework, navigation, reusable components, schema docs, generator/validator CLI or API. Owner: UX/content tooling. Metadata: `ui`, `ai-authoring`, priority P2.
- **Non-goals:** Embedding an autonomous LLM with unrestricted server mutation.
- **Dependencies/open decisions:** M2, M3, M5, M6, M8, M9; `needs-input`: exact visual language and whether AI generation is CLI, external service, or in-game assistant. Recommendation: documented schema + validated patch plan first.
- **Verification:** UI automation at minimum supported resolution, keyboard/navigation tests, round-trip fixtures, invalid-input diagnostics, generated-content validation.

### M11 — Import, compatibility, and release certification

Outcome: existing CustomNPCs creators have a credible migration path and parity is demonstrated rather than asserted.

#### P11-1 — Import target content and certify the parity surface

- **Intent:** Provide a safe migration path from the supplied CustomNPCs build and close the evidence loop.
- **Expectation:** Importers translate supported NPC/display/combat/dialogue/quest/faction/role/job/trade/bank/transport/tool/script data into versioned StoryNPCs definitions, report unsupported fields, and never silently discard content.
- **Acceptance criteria:** Import report is deterministic and field-level; unsupported target capabilities are enumerated; migrated fixtures pass target/story behavioral comparison where target runtime is available; all 15 operations have green parity fixtures; 500/25v25/2,500 benchmarks meet published thresholds; adversarial review has no unresolved high-risk finding.
- **Context code cannot infer:** Exact target static inventory is strong, but runtime equivalence is not yet proven; compatibility must not be claimed from class-name matching.
- **Scope:** importer, compatibility report, release checklist, benchmarks, docs, final audit. Owner: release engineering. Metadata: `compatibility`, `release`, priority P0 at release gate.
- **Non-goals:** Importing decompiled code or promising perfect conversion for unsupported third-party scripts/assets.
- **Dependencies/open decisions:** All prior milestones; final decision on intentional deviations, model assets, scripting host, and performance thresholds.
- **Verification:** clean-checkout import, fixture comparison, migration/reload, performance certification, `./gradlew test`, adversarial read-only audit.

## 8. Open decisions that should not be silently guessed

These do not block M0-M2 or the operation-layer refactor, but they should be resolved before the affected milestone begins:

1. **Compatibility boundary:** default recommendation is user-facing behavior and authoring parity with the supplied build, not internal packet/file/class compatibility.
2. **Model strategy:** default recommendation is a stable render-feature contract with vanilla/GeckoLib-backed implementations, not 60 hardcoded wrapper classes.
3. **Scripting strategy:** default recommendation is a typed bounded host API and external KubeJS-style adapter before choosing an embedded runtime.
4. **Persistence strategy:** default recommendation is SavedData for world-scoped indexes plus dedicated versioned stores for large/player/economy state, all behind one repository policy.
5. **AI authoring:** default recommendation is schema/reference/validator/generator tooling first; an in-game LLM assistant is optional and must only submit validated operation plans.
6. **Performance thresholds:** define supported hardware, view distance, modpack assumptions, and acceptable active/dormant behavior before M4 certification.

## 9. Primary references

- Local target specification: `E:/Github2/dwurdys-storynpcs-research/research/120-customnpcs-feature-parity-master-specification.md`
- Local research state: `E:/Github2/dwurdys-storynpcs-research/RESEARCH_STATE.md`
- Local target artifact record: `E:/Github2/dwurdys-storynpcs-research/generated/jar/artifact.json`
- Local comparator review: `E:/Github2/dwurdys-storynpcs-research/research/30-claim-level-comparator-review.md`
- Local performance audit: `E:/Github2/dwurdys-storynpcs-research/research/121-runtime-memory-and-tick-optimization-audit.md`
- Local density design: `E:/Github2/dwurdys-storynpcs-research/research/122-high-density-npc-simulation-and-optimization.md`
- Local adversarial performance study: `E:/Github2/dwurdys-storynpcs-research/research/123-adversarial-performance-and-heaphammer-study.md`
- Local current authoring report: `E:/Github2/dwurdys-storynpcs/docs/UX_PARITY_REPORT.md`
- [Citizens API](https://github.com/CitizensDev/CitizensAPI/blob/master/src/main/java/net/citizensnpcs/api/npc/NPC.java)
- [NeoForge Screens](https://docs.neoforged.net/docs/1.21.3/gui/screens/)
- [NeoForge SavedData](https://docs.neoforged.net/docs/1.21.5/datastorage/saveddata/)
- [NeoForge Data Components](https://docs.neoforged.net/docs/1.21.3/items/datacomponents/)
- [BetonQuest reference](https://betonquest.org/1.11/05-Reference/)
- [Yarn Spinner dialogue runner](https://docs.yarnspinner.dev/components/dialogue-runner)
- [KubeJS events](https://kubejs.com/wiki/events)
- [FTB Quests API documentation](https://github.com/FTBTeam/docs/blob/main/docs/marketplace/Addons/quests/api.md)
