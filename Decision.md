# Architecture Decision Records (ADRs) — Dwurdy's StoryNPCs

## ADR-001: YAML as Authoring Definition Format
- **Context**: Legacy CustomNPCs serializes NPC definitions into binary NBT world chunks and complex server-side data files. This makes version control, collaborative editing, and automated validation impossible.
- **Decision**: All authorable content (NPCs, Dialogues, Quests, Factions, Roles, Jobs) will be defined in versioned YAML files with namespaced identifiers (e.g. `storynpcs:blacksmith_dialogue`).
- **Consequences**:
  - Content can be edited in text editors, reviewed in PRs, and validated in CI.
  - Requires a schema validation engine that reports human-readable diagnostics with file path, line, and column numbers.
  - Definitions are strictly read-only at runtime.

## ADR-002: Canonical Application Service Layer for Surface Parity
- **Context**: Phase 1 technical archaeology revealed 0/15 cross-surface parity in CustomNPCs; GUI packets, server commands, public API methods, and script wrappers all duplicated business logic, had disparate authorization checks, and diverged in side effects.
- **Decision**: All mutations must route through a single `StoryNpcsApplicationService` defining 15 canonical operations. GUI packets, command executors, API methods, and script bindings become thin adapters that translate inputs and call the canonical operation.
- **Consequences**:
  - Defines the intended convergence point for all entry surfaces; it does not by itself prove functional parity.
  - Prevents authorization bypass and validation drift once each adapter is routed through it.
  - Enables end-to-end integration testing against the application layer without requiring a graphical client. Current target equivalence is `0/15` proven; P1-1 closes the implementation gap.

## ADR-003: Directed-Graph Dialogue Architecture
- **Context**: CustomNPCs restricted dialogue options to a fixed 12-slot array, severely limiting complex branching narratives, loops, and conditional dialogues.
- **Decision**: Implement a directed-graph model where Dialogues consist of arbitrary `DialogueNode` instances connected by `DialogueEdge` options.
- **Consequences**:
  - Supports arbitrary branching, deliberate cycles (e.g. returning to a hub topic), and dynamic condition gating.
  - The server authoritatively evaluates available edges and advances nodes upon player selection.
  - Future UI can render and edit dialogues as node-graphs rather than flat slot lists.

## ADR-004: Strict Separation between Content Definitions and Progression State
- **Context**: Storing live player progress (completed quests, faction standing, NPC health) directly in the same structures as definitions risks content corruption and breaks data-pack immutability.
- **Decision**: Definition files remain completely immutable at runtime. Player progression (quest stages, faction scores, dialogue visit history) is stored in a dedicated player progression repository using atomic `.tmp` -> flush -> rename writes.
- **Consequences**:
  - Zero risk of user actions corrupting authored content.
  - The desired crash-recovery behavior is not yet certified; P2-2/P2-3 define the required implementation and recovery evidence.
  - Simplifies world resets and pack updates.

## ADR-005: Event-Driven Extensibility
- **Context**: Third-party mod developers need to hook into NPC interactions, dialogue transitions, and quest completions without fragile bytecode manipulation.
- **Decision**: Expose clean, strongly-typed NeoForge events on the standard event bus (`StoryNpcInteractEvent`, `DialogueOptionSelectEvent`, `QuestCompleteEvent`, `FactionReputationChangeEvent`).
- **Consequences**:
  - Other mods can cleanly intercept, cancel, or react to storytelling events.
  - Safe, backward-compatible API boundary.

## ADR-006: File-First Data — No SQL Store; Categories and Bundled Schematic Decisions
- **Context**: CustomNPCs ships a `DatabaseController` SQL-backed player-data store, organizes dialogs/quests into named folders, and bundles 28 `.schematic` structures for the Builder job. StoryNPCs needed owner decisions on each.
- **Decision** (owner: Vlad, 2026-10-05):
  1. **No SQL store — intentional deviation.** Everything admins may edit must be human-editable YAML/TOML/files. Only concurrency-sensitive runtime state (player progression, trade/bank, operation journals, mail) uses managed durable stores, and admins must not hand-edit those files.
  2. **Categories — parity.** Dialogue and quest schemas gain a `category` (folder) field; editors group by it; the P11-1 importer maps target folder trees onto it.
  3. **Bundled schematics — port all 28** target structures as mod assets consumed by the Builder job and schema commands.
- **Consequences**:
  - `persistence_stores.database` / `noppes.npcs.db` is a recorded deviation; CustomNPCs SQL users migrate via export-to-files through the importer.
  - G-A1/G-A2 decision issues not needed; scope folded into #65, #68, #86 and gap issues G-B3.
  - Schematic redistribution terms resolved 2026-10-05: the pinned target jar declares `license="CC BY-NC"` (`META-INF/neoforge.mods.toml`), so the 28 `.schematic` files ship in `data/storynpcs/schematics/` for non-commercial use with the bundled `ATTRIBUTION.txt` crediting Noppes. Commercial redistribution is not permitted under CC BY-NC. The jar-level `license` field declares the split (`mod_license="MIT (code); bundled schematics CC BY-NC"`) so the shipped artifact does not claim MIT over NC-restricted assets.

## ADR-007: Stats/Resistance Contract, Markov Names, Scripting Engine Direction, and Live Benchmark Evidence
- **Context**: Four `needs-input` issues gated downstream parity work: #122 (canonical stats/resistance contract vs PR #116), #123 (markov name-generation scope + CC BY-NC dictionary license), #125 (scripting engine pin for P9-2), #126 (live-runtime benchmark evidence for P4-3 certification).
- **Decision** (owner: Vlad, 2026-10-05; #122 resolved via flux-decide delegation):
  1. **Stats/resistance — full target-faithful replication** (#122): flat 4-channel resistances (knockback/arrow/melee/explosion, default 1.0, `damage *= 2.0 - resistance` semantics), **unclamped NBT read passthrough** (target-faithful; clamping would silently alter imported persisted data), **inverted `setImmune` types 1/4 replicated** in the compat adapter with a documented warning, `ModRev` version marker on NPC persistence. PR #116 closed as superseded; resolves #118; implements under #59 P3-2.
  2. **Markov names — implement with clean-room dictionaries** (#123): seeded deterministic generator exposed through canonical ops to creator UI/API/commands; ≥11 culture dictionaries authored fresh — no target dictionary text copied (CC BY-NC). Sequenced under M3.
  3. **Scripting engine — non-Nashorn direction** (#125): explicit artifact on the classpath (no reliance on loader-provided libraries); specific engine (Rhino vs GraalJS vs DSL) pinned in #125's decision record with research evidence; dialect deviation from the target's ECMAScript recorded either way.
  4. **Live benchmarks — build the harness** (#126): repeatable harness provisions a real NeoForge dedicated server + storynpcs jar, runs the three benchmark scenarios, artifacts under `docs/parity/reports/`; `HEADLESS_PASS` vs `LIVE_RUNTIME_PASS` distinguished; release gate requires live-runtime state (or explicit accepted deviation) for certification; port parameterized (25566+, dev machine owns 25565); CI wiring decided after the local harness works.
- **Consequences**:
  - #122 unblocks #59's resistance/immunity completion; #123 unblocks bundled name-content work; #125 unblocks #84 pending the engine pin; #126 unblocks #64 certification and defines the #95-adjacent evidence tier.
  - Target-quirk replication is confined to the compat/NBT boundary — internal models stay clean; quirks are recorded, not hidden.

## ADR-008: Script Engine Pin — Rhino 1.9.1 via JSR-223 (#125)
- **Context**: ADR-007 fixed the *direction* for P9-2's scripting host: a non-Nashorn engine as an explicit classpath artifact (never a loader-provided library). #125 requires the specific engine pinned with research evidence. Target facts (research/125 F3, `VERIFIED_TARGET_SOURCE` + `VERIFIED_TARGET_RUNTIME`): the target runs JSR-223 ECMAScript (`.js`), enumerates `ScriptEngineManager` factories, and resolves Nashorn — `org.openjdk.nashorn:nashorn-core:15.4` arrives via NeoForge's library set (not shaded, not a `mods.toml` dep); runtime log confirms `ECMAScript: .js`. How-others-did-it: KubeJS → Rhino, Denizen → own DSL, target → Nashorn.
- **Decision** (recorded 2026-10-06, per ADR-007 delegation to the P9-2 decision record):
  1. **Engine: Rhino, pinned at `org.mozilla:rhino:1.9.1` + `org.mozilla:rhino-engine:1.9.1`** in `build.gradle` (`implementation` + `additionalRuntimeClasspath` + `jarJar` so dev, run, and shipped-jar classpaths all carry it). Rationale: dialect-nearest maintained JSR-223 ECMAScript engine to the target's Nashorn surface (same `.js` authoring contract); pure-Java; and uniquely among candidates, Rhino's interpreted-mode `ContextFactory.observeInstructionCount` supplies the **deterministic instruction counters** P9-2's quota oracle requires (20,000/tick, 100,000/other-hook). GraalJS deviates too far in dialect and deployment weight; a custom DSL forfeits script-parity claims entirely.
  2. **Dialect deviation recorded**: Rhino ECMAScript ≈ ES2019-era subset vs Nashorn ES5.1+extensions — `.js` scripts load through the same JSR-223 surface; no claim of `jdk.nashorn`-specific extension parity (`Java.type` semantics differ: Rhino uses `Packages.*`/`JavaImporter`; documented in P9-2, not emulated).
  3. **Shadow defense**: Nashorn 15.4 *is on the classpath* via NeoForge and wins `getEngineByExtension("js")`/`getEngineByName` non-deterministically — `ScriptEnginePin.pinnedEngine()` enumerates factories and selects Rhino explicitly; boot `assertAvailable()` fails loudly when the artifact is absent. Proven live: the pin check caught the Nashorn shadow during verification.
  4. **Sandbox contract**: P9-2's quotas (1ms tick / 5ms standard, 4ms aggregate, 1MiB memory, depth 16, 20k/100k instructions, 32 canonical ops) wrap the engine — isolation never relies on engine internals; Rhino `ClassShutter` gates Java access, instruction observers bound execution, `ScriptScheduler` owns wall-clock budgets. Nashorn's unmaintained status is irrelevant to this design since engine internals are not a trust boundary.
- **Consequences**: #125 resolved; #84 (P9-2) implements the host on the pinned Rhino contract; no script-API parity claimed beyond the `VERIFIED_TARGET_SOURCE` hook inventory.
