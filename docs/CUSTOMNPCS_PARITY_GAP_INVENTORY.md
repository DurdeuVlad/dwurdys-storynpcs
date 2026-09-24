# CustomNPCs parity gap inventory

Status: `EVIDENCE-REFRESHED / IMPLEMENTATION-IN-PROGRESS` — this inventory classifies the canonical 47-item plan, captures exact target/source anchors and a cheapest first verification for each item, and records the GitHub tracker mapping. The full priority, blocking dependencies, acceptance criteria, non-goals, and verification plan for every ID remain canonical in [CUSTOMNPCS_PARITY_ISSUE_REGISTER.md](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md); this document adds evidence classification and implementation-status clarity rather than a second competing plan.

## Exact evidence baseline

- Target artifact: `CustomNPCs-Unofficial-NeoForge-1.21.1.20251230.jar`, SHA-256 `6c28d87b215fc1191488194188ec8a39dd908ae7d2d887b7c9d7d463be0a162c`, SHA-1 `e2f3b58ceb5aac4021d7bfd130e320925581471b`.
- The sibling research repository referenced by the project documentation is present and clean at commit `9f7a921a5c3fbc66199b0d7719bdc0c56081745d`. It contains the nine pinned inventory inputs and `.local/decompiled/vineflower`. The Vineflower 1.12.0 tool JAR SHA-256 is `1dfcfe974395734fa467ce620661c7623d05ba83670de0529b1fbd63ff548b9d`.
- `tools/parity/generate_target_surface_manifest.py` emits manifest v4 and was run against the exact supplied JAR, research commit, and Vineflower tree after adding six quest revision/event/threshold regression selectors and their P1-1/P2-1/P2-3 owners. The checked-in generated manifest SHA-256 is `7a6e3337e19be0dd3c9ac6fd356c563ae9d9ff82ca82518f35a6f076ab27769c`.
- The regenerated manifest contains 2,467 archive entries, 1,230 classes, 972 assets, 49 data entries, 149 GUI records, 155 packets, 70 command leaves, 97 event classes, 7 roles, 11 jobs, 3 companion jobs, 60 persistence participants, 18 persistence stores, and 15 operation rows. Decompiled-source rows are bound to their exact pinned inventory record, checked against the exact JAR class entry, and hashed; all 82 persistence-source references have per-file hashes. Research-inventory rows are checked against their pinned source hash.
- A live exact-source combined run returned `validation_status=PASS`, `source_provenance_status=VERIFIED`, `ci_validation_status=PASS`, and `parity_status=BLOCKED`. The 25-fixture catalog has 83 mapped selectors across 20 observed fixtures; combat, inventory identifiers, single-mark serialization, and follower owner/formation have explicitly partial coverage, while 5 fixtures still have no selector. No target-runtime probe has been run.
- The earlier closeout statement that the inventories and decompiled sources were absent was wrong; the sibling research repository was overlooked. P0-1 and P0-4 are `DONE-LOCAL` as static inventory and test-infrastructure deliverables after independent review. Five feature fixtures still have no selector; partial scopes remain disclosed; all 25 target-runtime observations are unverified, so parity certification remains blocked.

These are source/inventory facts, not proof of target behavior. A file/class match, decompiler output, passing StoryNPCs test, or static API inventory never establishes `VERIFIED_PARITY` by itself.

## Gap classes

| Code | Meaning | Current disposition |
|---|---|---|
| `CF` | User-visible CustomNPCs capability is missing or materially narrower. | Open in the issue register unless explicitly marked implemented locally. |
| `BUG` | A concrete behavior or architecture defect is evidenced in StoryNPCs source/tests. | Fix through the canonical application service and add a regression test. |
| `TG` | Missing/weak automated coverage, evidence plumbing, or certification. | Keep claims blocked until the specific test/evidence exists. |
| `EB` | External artifact, runtime, workload, or sample-data evidence is not available. | Name the missing evidence precisely; do not label it a product feature. |
| `DOC` | Documentation/claim accuracy only. | P0-2 is complete locally; keep the truth gate enabled. |

### Confirmed defects and fail-open paths

- P0-4 tooling had concrete fail-open paths: one JUnit selector could be reused across fixtures; the full manifest/artifact/provenance validator was bypassed; role ownership was not pinned; JUnit totals could disagree with imported cases; and nested result/testcase XML could be ignored. The independent CI-gate follow-up approved the scoped corrections. The subsequent P0-1 v4 source-provenance audit also returned `SUPPORTED`; see [the P0-1 review](parity/reviews/2026-09-23-p0-1-source-provenance.md).
- P1-1 remains `IN-REVIEW`: adapter-side mutations and legacy boolean/untyped boundaries still exist outside the typed `StoryNpcsApplicationService` operations.
- P1-2 remains `IN-REVIEW`: runtime registries now own many transient maps, but the architecture audit still identified mutable `StoryNpcs.instance`/runtime dependencies requiring a repository-wide lifecycle scan.
- P2-1 is partly delivered, but `StoryNpcsCommands` still contains Java-built quickstart definitions; this conflicts with YAML-first, read-only runtime definitions until moved to versioned bundled YAML and covered by a no-write test.
- P2-2 and P2-3 remain reliability gaps: the full 18-store crash matrix and all multi-step economy/progression recovery paths are not demonstrated. Quest completion/reward fan-out, held inventory changes, trade outputs, paid unlocks, and bank transitions need explicit commit/replay evidence.
- P5-5 records the known quest state/reward ordering problem and absent mail/team state; it is not resolved by the P2-3 preflight work.

### Evidence blockers (not feature holes)

- Target runtime access: all 24 CustomNPCs runtime outcomes remain `UNVERIFIED_TARGET_RUNTIME`; this blocks parity certification but not StoryNPCs implementation.
- StoryNPCs fixture coverage: five fixture IDs have no selector: `P0-4.jobs`, `P0-4.transport`, `P0-4.spawner`, `P0-4.creator-tools`, and `P0-4.scripting`. Combat and inventory cover only authored JSON round-trip fields; marks cover the current single-mark value; companions cover only follower owner/state/formation. These partial observations do not cover their full target contracts.
- Performance: P4-3 has no production benchmark artifact for the proposed workloads and numeric thresholds in [the certification plan](CUSTOMNPCS_PARITY_CERTIFICATION.md). Research prototypes are not production evidence.
- Import: P11-1 has no creator-authored CustomNPCs world/export sample. The supplied JAR is the engine, not a user's world data.
- Cross-version target mapping: P1-3's implementation work is locally complete, but the target inventory's 115 server-bound and 40 client-bound payloads are not fully mapped to equivalent StoryNPCs fixtures.

## How comparable tools solved adjacent problems

- CustomNPCs is the compatibility target for familiar authoring breadth, but not the quality ceiling: the existing UX audit records visual defects in 15 of 18 audited screens and search/filter in only 4 of 149 GUI classes. The specific report is [UX_PARITY_REPORT.md](UX_PARITY_REPORT.md); its measurements describe the audited artifact and should be rechecked against runtime before making behavioral claims.
- Citizens separates specialized capability into composable Traits, exposes extensive commands/help, and uses focused editors for workflows that are awkward as commands (for example text and waypoints). That suggests a StoryNPCs design with capability panels and searchable command/API entry points sharing one service, not a single giant editor: [Citizens command reference](https://wiki.citizensnpcs.co/Commands), [Citizens editors](https://wiki.citizensnpcs.co/Editors), [Traits](https://wiki.citizensnpcs.co/Multiple_Traits).
- The CustomNPCs Unofficial repository documents a VS Code/TypeScript setup for scripts and warns that client/server scripts can access packets, reflection, and files. Preserve the power creators expect, but give it a versioned API, explicit capabilities, deterministic validation, and a dry-run/apply boundary instead of copying ambient script authority: [CustomNPCs Unofficial README](https://github.com/BetaZavr/CustomNPCs-Unofficial).
- Recommended synthesis (inference): keep YAML/schema as the durable source of truth; let the in-game UI and command/API surfaces produce the same typed patch plan; let external AI read a versioned schema/reference bundle and emit that patch plan; validate, show a field-level diff, authorize, then apply atomically through `StoryNpcsApplicationService`. This is the intended direction of P1-1, P1-4, P2-1, P9-1/P9-2, and P10-1/P10-2.

## Issue-by-issue inventory

For each row, `Priority`, blocking dependencies, and complete acceptance are the canonical values in the linked issue-register entry. `First proof` is the cheapest useful check before expanding to the full acceptance suite; it is not a substitute for that suite.

### M0 — Truth baseline and executable evidence

| Issue | Class / status | Verified gap and affected code/surface | First proof |
|---|---|---|---|
| [P0-1](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p0-1--generate-the-exact-target-surface-manifest) | `EB/TG` — `DONE-LOCAL`; independent provenance audit supported. | v4 manifest binds each source-backed row to the pinned inventory, hashes every store source file, and verifies target-JAR class membership. Regeneration still needs the exact JAR and pinned sibling Vineflower tree, which are not part of a clean primary-repository checkout. Affected: `tools/parity/generate_target_surface_manifest.py`, `docs/parity/target-surface-manifest.json`. | Run generator twice with the pinned JAR/research commit; compare output SHA-256 and all 13 counts. |
| [P0-2](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p0-2--remove-unsupported-completion-claims) | `DOC` — `DONE-LOCAL`. | Truth-gate exception file and authored claims in `README.md`, `Business.md`, `Decision.md`, `Milestones.md`, `docs/UX_PARITY_REPORT.md`. | `python tools/parity/check_truth_gate.py`; existing five truth-gate regression tests. |
| [P0-3](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p0-3--define-the-target-runtime-evidence-gate) | `TG/EB` — `DONE-LOCAL`; runtime probes still absent. | `tools/parity/evidence_gate.py`, `docs/parity/evidence-schema.json`; all 24 target comparisons stay blocked without target observations. | `python -m unittest tools.parity.test_evidence_gate`, including missing/contradictory/forged target reports. |
| [P0-4](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p0-4--build-the-15-operation22-domain-fixture-harness) · [GitHub #48](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/48) | `TG/BUG` — infrastructure `DONE-LOCAL`; five owner-linked feature blockers and target runtime evidence remain. | `tools/parity/fixture_harness.py`, `tools/parity/run_storynpcs_fixtures.py`, `fixture-catalog.json`; fail-open paths have regression coverage; strict certification remains blocked, and CI-only success does not promote parity. | Run all parity-tool tests; verify 25 fixtures, 15 operation IDs, 22 domains, exact selector outcomes, and fail-closed XML/source negative cases. |

### M1 — Canonical operations, stable actors, authorization, protocol

| Issue | Class / status | Verified gap and affected code/surface | First proof |
|---|---|---|---|
| [P1-1](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p1-1--introduce-typed-canonical-operations-inside-storynpcsapplicationservice) | `BUG/CF` — `IN-REVIEW`. | `StoryNpcsApplicationService` has typed operations for only part of the domain; command, packet, item, and GUI adapters still have mutation boundaries or untyped results. | Static call-path audit of one NPC edit, one quest/faction delete, and one trade/bank mutation, then focused service replay/stale-revision tests. |
| [P1-2](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p1-2--separate-stable-npc-actors-from-entity-projections) | `BUG/CF` — `IN-REVIEW`. | `StoryNpcs.java`, `StoryNpcEntity`, `WorldLifecycleHandler`, entity projection registry and old runtime selections must prove instance/world ownership through reload/replacement; mutable static lifecycle context remains an audit finding. | Two-world actor-isolation + entity replacement test; repository-wide mutable-static scan. |
| [P1-3](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p1-3--version-and-harden-the-network-protocol) | `TG/BUG` — `DONE-LOCAL`; target mapping open. | StoryNPCs codecs/payloads in `src/main/java/com/storynpcs/network/` have schema/size/session/revision hardening; mapping all 115 target server-bound and 40 client-bound payloads is not complete. | `NetworkPayloadsTest` codec/bounds/replay tests; count mapped target payload IDs against 115/40. |
| [P1-4](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p1-4--enforce-actor-capability-and-authorization-policy) | `BUG/CF` — `IN-REVIEW`. | `AuthorizationPolicy` protects canonical definition mutations; complete command, scripting, economy, world-tool, persistence-read coverage remains open. | Allow/deny matrix at the service plus one denied command, trade, and world-tool mutation with zero side effects. |

### M2 — Versioned content and recoverable state

| Issue | Class / status | Verified gap and affected code/surface | First proof |
|---|---|---|---|
| [P2-1](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p2-1--version-and-migrate-every-yaml-definition-family) | `BUG/CF` — `IN-REVIEW`. | `YamlDefinitionLoader`, `YamlDefinitionWriter`, `DefinitionSchema`, `StoryNpcsCommands`: version envelope and strict boundary exist, but not every role/job/template/tool/world family is covered; Java-built quickstart definitions still violate YAML-first runtime content. | Add one no-write quickstart test and one round-trip/migration fixture per uncovered document family. |
| [P2-2](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p2-2--implement-durable-worldplayereconomy-stores) | `BUG/TG` — `IN-REVIEW`. | `DurableJsonStore`, `ProgressionRepository`, `BankRepository`, `TradeStateRepository`, `ActorStateRepository`: forced atomic writes/recovery exist for selected stores, not the target's full 18-store ownership/crash matrix. | Inject failures at write/force/rename/index stages for each repository and reload the last committed generation. |
| [P2-3](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p2-3--add-operation-specific-transaction-and-recovery-fixtures) | `BUG/TG` — `IN-REVIEW`. | Trade, bank, quest rewards, item inventory, and unlocks span external state and durable state; existing journals cover only selected operations. See `StoryNpcsApplicationService`, `TradeOperationIntent`, `BankOperationIntent`, `DurableOperationJournal`. | For one selected trade, crash between charge/output, retry the same request, and assert exact inventory/value conservation. |

### M3 — Core NPC capability

| Issue | Class / status | Verified gap and affected code/surface | First proof |
|---|---|---|---|
| [P3-1](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p3-1--implement-stable-display-variants-model-hitbox-and-render-features) | `CF` — `IN-PROGRESS`. | `NpcDisplay`, `StoryNpcRenderer`, `NpcEditorScreen`, display payloads: schema carries more fields now, but skin/model/boss-bar/hitbox projection and editor/network coverage remain incomplete. | Field-by-field YAML round-trip, then a client render smoke test for each newly projected field. |
| [P3-2](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p3-2--implement-stats-melee-ranged-resistances-and-defeat-behavior) | `CF` — open. | `NpcStats`, `StoryNpcEntity`, `NpcMeleeAttackGoal`: current stats cover basic health/damage/speed/respawn/aggro, not complete ranged/resistance/defeat behavior. | One deterministic damage matrix covering melee, projectile, resistance, defeat, and respawn. |
| [P3-3](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p3-3--implement-ai-movement-targeting-and-tactical-behavior) | `CF` — open. | `NpcAi`, `NpcFollowFormationGoal`, `NpcPatrolGoal`, `StoryNpcPathNavigator`, `ThreatManager`: useful basics exist; full target/stance/faction/tactical policy and bounded scheduling do not. | Seeded target-priority matrix and unload/cancel path test. |
| [P3-4](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p3-4--implement-inventory-equipment-drops-marks-and-authoritative-item-tools) | `CF/BUG` — open. | `NpcDefinition` inventory is string-list based; `NpcMark` represents one mark; `NpcClonerItem`, `NpcPathItem`, `NpcMounterItem`, `NpcWandItem` need authoritative session/data-component and permission parity. | Item-stack round-trip plus two-player tool-session isolation and mark persistence test. |

### M4 — MMO-scale simulation and measured battles

| Issue | Class / status | Verified gap and affected code/surface | First proof |
|---|---|---|---|
| [P4-1](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p4-1--implement-archetypes-simulation-tiers-and-lod) | `CF` — open. | No production simulation-tier/LOD implementation was found; current entity AI ticks are not tiered by player distance or actor role. | A deterministic tier-transition test proving paused/reduced/full policy and cleanup. |
| [P4-2](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p4-2--implement-bounded-path-scheduling-and-squad-coordination) | `CF` — open. | Path navigator exists, but no bounded request scheduler/squad coordinator with the specified cap and cancellation evidence. | Queue-cap/overflow/cancel test; assert worker never touches live world state. |
| [P4-3](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p4-3--establish-the-performance-certification-contract) | `EB/TG` — open. | `docs/CUSTOMNPCS_PARITY_CERTIFICATION.md` gives proposed thresholds; there is no production benchmark harness/artifact. | Fixed-seed small benchmark smoke report with MSPT, memory, queue, and correctness fields before the full-scale run. |

### M5 — Dialogue and quest parity

| Issue | Class / status | Verified gap and affected code/surface | First proof |
|---|---|---|---|
| [P5-1](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p5-1--complete-the-authoritative-dialogue-runtime) | `CF` — open. | `DialogueGraph`, `DialogueAction`, `DialogueCondition`, `DialogueSession`, `DialogueScreen`: graph runtime exists, but target fields/actions/session lifecycle are not fully represented. | Table-driven graph condition/action test through the application service. |
| [P5-2](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p5-2--make-dialogue-choice-exactly-once-and-stale-safe) | `BUG/CF` — open. | `ServerboundDialogueChoosePayload`, `DialogueSession`: option-index choice remains susceptible to stale/replay semantics without a complete server-issued token/transaction contract. | Replay identical choice twice and race two valid choices; assert one winner and one effect. |
| [P5-3](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p5-3--build-the-full-dialogue-authoring-and-asset-workflow) | `CF` — open. | `DialogueEditorScreen`, `DialogueEditorScreenModel`, graph serializer: current editor exposes only a subset of graph actions/conditions/assets. | YAML-to-editor-to-YAML round-trip of every currently supported node/edge/action field. |
| [P5-4](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p5-4--implement-quest-definitions-objectives-dependencies-and-repeats) | `CF` — open. | `Quest`, `QuestObjective`, `QuestSerde`, `QuestEditorScreen`: five objective kinds and three repeat modes do not cover the full dependency/team/repeat contract. | One fixture for each objective and repeat mode plus dependency-cycle rejection. |
| [P5-5](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p5-5--implement-quest-completion-team-progress-mail-and-rewards) | `BUG/CF` — open. | `StoryNpcsApplicationService` quest completion/reward fan-out and `PlayerProgression`: mail/team state absent and multi-effect completion is not yet crash-atomic. | Inject failure after each reward and retry completion; assert no lost/duplicated reward and stable quest state. |

### M6 — Factions, roles, jobs, transport, companions

| Issue | Class / status | Verified gap and affected code/surface | First proof |
|---|---|---|---|
| [P6-1](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p6-1--implement-faction-matrix-and-progression-parity) | `CF` — open. | `Faction`, `FactionStanding`, faction target policy: points/thresholds exist, full faction-to-faction relation matrix and change-source semantics do not. | Matrix tests for every relation including missing-pair neutral fallback and progression event. |
| [P6-2](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p6-2--implement-service-and-social-roles) | `CF` — open. | `RoleDialog` is coupled to NPC; `FollowerRole` is partial; healer, bard, postman/mail capabilities absent. | Capability registry test for absent/present role and lifecycle cleanup. |
| [P6-3](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p6-3--implement-transport-locations-and-transporter-role) | `CF` — open. | Target `RoleTransporter` and transport GUI exist in the exact inventory; no StoryNPCs transporter/transport service exists. | Destination validation for loaded chunk, safe landing, cost, permission, and cross-dimension refusal. |
| [P6-4](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p6-4--implement-the-exact-job-capability-inventory) | `CF` — open. | Target inventory has 11 concrete jobs; StoryNPCs has no jobs registry. Exact target list is pinned in the research inventory (including `JobFarmer`). | Register every manifest job ID and verify pause/resume/cleanup/tick-budget hooks. |
| [P6-5](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p6-5--implement-companion-lifecycle-wages-stages-talents-and-inventory) | `CF` — open. | `FollowerGroup`, `FollowerRole`, `CompanionFarmer` target: owner/formation/wage basics exist, but stages, talents, inventory, and job integration are absent. | Wage retry + dismissal + owner logout/death + restart lifecycle fixture. |

### M7 — Trade and bank parity

| Issue | Class / status | Verified gap and affected code/surface | First proof |
|---|---|---|---|
| [P7-1](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p7-1--implement-transactional-trader-parity) · [GitHub #44](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/44) | `CF/BUG` — open. | Target `RoleTrader`; `TradeListing`, `TraderRole`, `NpcTradeScreen`: current listing is one input/one output and admin GUI authoring is absent; current player UI is transaction-only. | Two-input item conservation and listing add/edit/remove through the service-backed authoring path. |
| [P7-2](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p7-2--implement-transactional-bank-parity) · admin-editor slice in [GitHub #44](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/44) | `CF/BUG` — open. | Target `RoleBank`; `BankVault`, `BankRepository`, `NpcBankScreen`: transaction/recovery foundation exists, but six-tab upgrade, full access/revision/editor behavior and crash proof remain incomplete. `BankerRole` exposes `bankName`, `maxTabs` (default 4), and `tabUpgradeCost` (default 1000), but no dedicated authoring screen was evidenced. | Boundary test for six tabs plus concurrent deposit/withdraw and restart after each commit phase; admin editor round-trips all three banker fields. |

### M8 — Creator tools and world systems

| Issue | Class / status | Verified gap and affected code/surface | First proof |
|---|---|---|---|
| [P8-1](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p8-1--implement-persistent-templates-cloning-and-spawners) | `CF/BUG` — open. | `NpcClonerItem` has transient selection; no persistent template/spawner domain, quota, or import lifecycle. | Save/reload/clone a namespaced template in two player sessions. |
| [P8-2](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p8-2--implement-movement-and-creator-utility-tools) | `CF/BUG` — open. | `NpcPathItem`/`NpcMounterItem` use transient selections; target has more creator utilities and explicit confirmation/audit behaviors. | Two-player tool session isolation + cancel/confirm test. |
| [P8-3](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p8-3--implement-scriptable-world-tools-and-creator-blocks) | `CF` — open. | Target has custom block/item/world-tool families; StoryNPCs source has no corresponding scriptable tool/content registry. | One inert typed hook binding with permission, chunk-unload, and rollback test. |
| [P8-4](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p8-4--implement-recipes-carpentry-registered-content-and-authoring-hooks) | `CF` — open. | Target recipe/carpentry and registered-content surfaces have no StoryNPCs issue implementation or executable fixture. | Schema round-trip plus unknown registry ID/reload rollback test. |
| [P8-5](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p8-5--implement-linked-npcs-scenes-transformations-timers-and-natural-spawning) | `CF` — open. | Target linked-NPC, scene, transform, timer, and spawn surfaces are absent from current domain/runtime source. | Scene cycle/cancel and timer restart/exactly-once fixture. |
| [P8-6](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p8-6--implement-custom-gui-hudoverlay-model-presets-and-visual-asset-authoring) | `CF` — open. | Target GUI/overlay/model-preset assets are inventoried; current screens have no general visual-layout/rendering component authoring contract. | Bound/fuzz a preview layout, then keyboard/minimum-resolution smoke test. |

### M9 — API, scripting, commands, administration, integrations

| Issue | Class / status | Verified gap and affected code/surface | First proof |
|---|---|---|---|
| [P9-1](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p9-1--deliver-the-typed-public-extension-api) | `CF` — open. | Internal event classes exist under `com.storynpcs.api.event`, but no stable public extension API equivalent/version contract is published. | Compile an external sample against the public API artifact and run one event/operation. |
| [P9-2](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p9-2--deliver-a-bounded-scripting-host-and-target-hook-matrix) | `CF/BUG` — open. | Target `SCRIPT` surface is inventoried; no production scripting package/host exists in StoryNPCs. | Hook-map golden test and one script quota/timeout/permission denial fixture. |
| [P9-3](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p9-3--reach-command-and-suggestion-parity) · [GitHub #45](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/45) | `CF/TG` — open; quest/faction deletes implemented locally, not merged. | Target manifest has 70 command leaves; `StoryNpcsCommands` covers only a subset. Quest/faction delete handlers now call canonical service methods with dangling-reference warnings; broader command parity remains open. | Run command-tree manifest comparison; keep deletion tests for failure-before-registry-mutation. |
| [P9-4](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p9-4--implement-remote-player-data-global-administration-and-configuration-parity) | `CF/SEC` — open. | Target admin/player/global-data/configuration surfaces are not represented by the current adapter/API set. | Operator/player/console permission matrix with wrong-world and expired-session attempts. |
| [P9-5](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p9-5--define-optional-mod-integrations-and-intentional-deviation-contracts) | `TG/EB` — open. | Target contains optional integration helpers; static classes do not establish mod-present/absent/version/load-order runtime compatibility. | Startup matrix with dependency present, absent, and incompatible version. |

### M10 — Familiar creator UI and AI authoring

| Issue | Class / status | Verified gap and affected code/surface | First proof |
|---|---|---|---|
| [P10-1](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p10-1--build-the-unified-customnpcs-style-authoring-hub) · [GitHub #44](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/44) | `CF/UX` — open. | Target has 149 GUI records; current `NpcEditorScreen`, `DialogueEditorScreen`, `QuestEditorScreen`, `FactionEditorScreen`, `NpcRulesScreen`, and trade/bank screens cover subsets only. | Build one complete NPC workflow without raw YAML and automate 854x480 physical resolution/GUI scale 2. |
| [P10-2](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p10-2--define-safe-ai-generated-content-and-patch-plans) | `CF/TOOLING` — open. | No generated schema/reference bundle, deterministic patch plan, dry-run validator, or permission-scoped AI apply contract exists. | Golden AI-generated patch: deterministic, dry-run no-write, authorized apply, failed apply rollback. |
| [P10-3](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p10-3--publish-creator-documentation-examples-and-migration-guidance) | `DOC` — open beyond P0-2. | Truthful project-level claims exist, but a full creator reference, runnable examples, migration guide, and generated schema documentation are absent. | Validate one multi-role NPC example and generated links/schema in CI. |

### M11 — Import and release certification

| Issue | Class / status | Verified gap and affected code/surface | First proof |
|---|---|---|---|
| [P11-1](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p11-1--define-and-implement-the-customnpcs-import-contract) | `CF/EB` — open. | No importer exists; the supplied JAR does not contain user-authored world/NBT export data. | Accept one real creator export sample in dry-run and report unsupported fields without writing. |
| [P11-2](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p11-2--close-target-commandapieventnetwork-evidence) | `TG/EB` — open. | Inventory is now source-backed, but no terminal map/deviation state exists for every target GUI/packet/command/event/role/job/persistence row; target runtime unavailable. | Require every manifest row to resolve to mapping/deviation/unverified and reject unowned rows. |
| [P11-3](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md#p11-3--run-final-adversarial-certification-and-release-gate) | `TG` — open. | Final evidence, performance artifacts, import/AI/UI proofs, dependency expansion, and adversarial release review are not complete. | Run dependency-graph validator and require no unaccepted open issue before the full release suite. |

## GitHub tracker reconciliation

The earlier GitHub milestones M1–M8 are preserved as history; all currently have zero open issues. There were no open PRs. The separate parity work is now tracked by one new milestone, `#9 M0: Exact-source parity foundation`, without repurposing the old milestone numbers.

| GitHub item | Parity mapping | Current state |
|---|---|---|
| [Milestone #9](https://github.com/DurdeuVlad/dwurdys-storynpcs/milestone/9) | Local M0: P0-1 through P0-4 | New; separate from legacy M1–M8. |
| [Issue #47](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/47) | P0-1 | New; v4 generation and read-only provenance audit complete locally; issue remains open pending user-directed tracker closure. Exact regeneration depends on the pinned external JAR/research inputs. |
| [Issue #48](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/48) | P0-4 | New; CI gate and independent scoped review complete; five feature blockers, partial behavior coverage, and runtime evidence remain. Issue remains open. |
| [Issue #44](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/44) | Narrow trader listing and banker configuration editor slice of P7-1/P7-2; unified hub P10-1 | Existing issue updated; still open. |
| [Issue #45](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/45) | Quest/faction-delete subtask of P9-3 | Existing issue updated; implementation is in the local worktree, not merged; still open. |

Other P1–P11 records remain in the local canonical register; no duplicate GitHub issues were created for them during this foundation sync. No branch push, pull request, or merge was performed.
