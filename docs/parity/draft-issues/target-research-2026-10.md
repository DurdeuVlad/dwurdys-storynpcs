# Draft issues — target-JAR research synthesis (2026-10)

Produced by `flux-research` + `flux-milestone` against the exact target JAR
`CustomNPCs-Unofficial-NeoForge-1.21.1.20251230.jar` (SHA-256
`6c28d87b…0a162c`), its Vineflower 1.12.0 decompile (1,036 files), and the
research repo's recorded `VERIFIED_TARGET_RUNTIME` probes. Source synthesis:
`dwurdys-storynpcs-research/research/125-production-evidence-bridge-and-gap-synthesis.md`.

**Handed off to GitHub** (owner authorized issue creation after review):

| Draft | GitHub issue | Milestone | Labels |
|---|---|---|---|
| T-01 | #121 | CustomNPCs parity M11 | — |
| T-02 | #122 | CustomNPCs parity M3 | needs-input |
| T-03 | #123 | CustomNPCs parity M3 | needs-input |
| T-04 | #124 | CustomNPCs parity M11 | — |
| T-05 | #125 | CustomNPCs parity M9 | — |
| T-06 | #126 | CustomNPCs parity M4 | needs-input |
| T-07 | #127 | M0 | — |

A comment carrying the F1 evidence summary was also posted to #118, which T-02
is intended to supersede once its product decision lands. The draft text below
remains the source-of-record detail for each issue.

## Contract

- **Goal:** Convert verified target evidence into executable parity work;
  close the structural gap where recorded target-runtime observations never
  reach the release gate; surface features hidden by mechanism-level mapping.
- **System:** `docs/parity/` evidence pipeline (`fixture-catalog.json`,
  `storynpcs-probe-report.json`, `compatibility-report.json`,
  `evidence_gate.py`), issue register M0–M11, GitHub issues #51–#95, #118.
- **Constraints:** YAML-first definitions; canonical
  `StoryNpcsApplicationService` mutations; no mutable static singletons;
  fail-closed evidence states; no decompiled code in production; no parity
  claims beyond evidence.
- **Evaluation:** Each issue carries observable acceptance criteria and a
  verification surface; honest states used (`ready-for-handoff`,
  `needs-input`, `blocked`).

## State separation

- **Observed facts:** target stats/immune/resistance source semantics;
  runtime probe corpus in the research repo; probe report lacking
  `target_probe`; markovnames feature absent from code and register;
  Nashorn engine arriving via NeoForge libraries; command quirks.
- **User decisions required:** immune-type API inversion (replicate vs.
  intentional deviation); whether random-name generation is in scope or an
  intentional deviation; whether to create these as GitHub issues.
- **Assumptions:** the research repo remains the evidence source of record;
  `MAPPED_STORYNPCS_OBSERVED` means "mechanism exists", not "content ships".
- **Unknowns:** full count of mechanism-mapped rows masking unimplemented
  features (T-04 exists to measure it); GUI/client behaviors remain
  `NOT_RUN (BLOCKED_NO_CLIENT_HARNESS)`.

## Issue drafts

---

### T-01 — Ingest recorded VERIFIED_TARGET_RUNTIME probes into the parity evidence pipeline

**Placement:** new issue under M11 (depends on P11-2; precedes P11-3).
**Type:** verification/infrastructure · **Suggested priority:** P0
(gate-blocking) · **Owner:** unassigned · **State:** ready-for-handoff

**Intent.** The release gate reports `UNVERIFIED_TARGET_RUNTIME` for all
1,606 parity rows, but a target-runtime evidence corpus already exists in
`dwurdys-storynpcs-research` (research/05 §6–§8). Today there is no ingestion
path, so the gate's "unavailable" claim is structural, not factual. Closing
this gap is required for P11-2/P11-3 to ever report `VERIFIED_PARITY` on any
row.

**Expectation.** `storynpcs-probe-report.json` emits a populated
`target_probe` object (per `evidence-schema.json`: `status`, `result`,
`reason`/`next_evidence`) for every fixture, sourced from a provenance-tracked
import of research-repo observations. Fixtures whose behavior was genuinely
probed show `target_probe.status=OBSERVED` with the recorded result and a
pointer to the source log/doc; the rest remain `UNAVAILABLE`/`NOT_RUN` with
explicit reasons. `parity_state` advances to `VERIFIED_PARITY` only where the
schema's rule (target OBSERVED + storynpcs OBSERVED + comparison MATCH) is
fully satisfied.

**Acceptance criteria.**

- A versioned evidence-import artifact (JSON) maps each research-repo
  `VERIFIED_TARGET_RUNTIME` observation to fixture IDs it can substantiate:
  minimum set — `P0-4.commands` (11-subcommand discovery, boolean validation,
  config toggles, reloaders, clone list, schema list), `P0-4.marks` (mark
  persistence across restart + login/logout cycles), `P0-4.core-entity`
  (live NBT dump: `ModRev: 18`, `cnpcmarkdata`, `FactionPoints`,
  `NpcInteractLines`, `VisibleAvailability`, `NpcModelData`, `RespawnTime:20`,
  entity-ID non-persistence), `P0-4.spawner` (clone list), `P0-4.persistence`
  (save-all/stop).
- Every imported record carries provenance: source doc section, runtime
  config (NeoForge 21.1.230, port 25566), date, and exact observed output.
- `evidence_gate.py` fails a fixture whose `target_probe.status=OBSERVED`
  lacks `result` or provenance.
- Rows not covered by an actual probe keep `UNVERIFIED_TARGET_RUNTIME` with
  `reason`/`next_evidence`; the report distinguishes "not probed" from
  "evidence exists but not ingested".
- `release-gate-report.json` still reports `BLOCKED` for fixtures where
  StoryNPCs-side evidence or `comparison=MATCH` is missing — no inflation.

**Context that code cannot infer.** Research repo probes were authorized
under Option A+C scope; graphical client paths are
`NOT_RUN (BLOCKED_NO_CLIENT_HARNESS)` and must not be imported as observed.
The `noppes mark` second-add probe is `PARTIAL`/ambiguous — exclude or mark
explicitly.

**Scope.** `tools/parity/` ingestion + validation, fixture catalog/report
schema use, generated reports. Does not modify research-repo files.

**Non-goals.** Running new probes; certifying parity; upgrading GUI/client
rows (blocked); changing gate pass criteria.

**Dependencies and open decisions.** P0-3, P0-4, P11-2. Open: canonical
location/format of the cross-repo evidence import (repo path is hardcoded —
needs config).

**Verification.** Re-run `tools/parity` report generation + `evidence_gate`;
fixture diff shows only genuinely-probed rows changing state; injected
provenance-less `OBSERVED` row must fail the gate.

---

### T-02 — Adopt the canonical target stats/resistance/immunity contract (resolves #118)

**Placement:** amends/resolves GitHub #118 and PR #116; registers under
P3-2 (M3). **Type:** feature/decision · **Suggested priority:** P1 ·
**Owner:** unassigned · **State:** needs-input (one product decision)

**Intent.** Issue #118 asks which resistance/immunity model survives —
PR #116's or #117's. The exact target source now settles the factual half:
the target uses a **flat** `Resistances {knockback, arrow, melee, explosion}`
(defaults 1.0) plus **six boolean immunity fields on stats**, with
`damage *= 2.0 - resistance` semantics (1.0 neutral, 0.0 = double damage,
2.0 = immune), knockback applied in `EntityNPCInterface.knockback`, API clamp
[0,2] on write but **no clamp on NBT read**, and creature type int 0–4 ↔
UNDEAD/ARTHROPOD/ILLAGER/AQUATIC.

**Expectation.** One canonical `NpcStats` model remains after #118. Its YAML
schema, NBT/serde round-trip, and service operations express the target's
contract (field set, defaults, `2-r` semantics, per-channel dispatch:
projectile → arrow, player/mob/npc → melee, explosion → explosion, knockback
separate). NBT key names (`Knockback`, `Arrow`, `Melee`, `Explosion`,
`AttackStrenght` [target typo, verbatim], `MaxHealth`, `AggroRange`, …) are
documented in the import contract (P11-1) where NBT compatibility is claimed.

**Acceptance criteria.**

- Single surviving model matches the target field set and `2-r` semantics
  with per-channel dispatch verified by unit tests (arrow/melee/explosion/
  knockback + neutral/vulnerable/immune boundary values).
- NBT read path: decide and record — replicate unclamped passthrough
  (target-faithful) or clamp with a declared deviation; test covers an
  out-of-range persisted value.
- **Decision (needs input):** target API `setImmune` types 1 (fall) and 4
  (drowning) are *inverted* (`setImmune(1,true)` ⇒ `noFallDamage=false`).
  StoryNPCs either replicates this in its compat adapter with a warning or
  declares `INTENTIONAL_DEVIATION` with rationale; whichever survives is
  recorded in the compatibility report.
- `ModRev`-equivalent version marker written on NPC persistence (target
  writes `ModRev: 18`).
- PR #116 is either rebased to the surviving model or closed; #118 closed
  with the decision recorded.

**Context that code cannot infer.** The `2.0 - r` formula means `0.0` is a
*vulnerability* value, not "no resistance" — a modeling trap that a
percentage-style model would silently get wrong. Full evidence table:
research/125 F1.

**Scope.** `domain/npc` stats model + serde + canonical ops; compat report
rows for stats/resistances/immunities.

**Non-goals.** Copying `DataStats` code; GUI stats editor (P10-1);
melee/ranged full field sets beyond what the acceptance criteria name
(those belong to P3-2's main body).

**Dependencies and open decisions.** #118 decision; P1-1 canonical ops; P2-1
schema versioning; P11-1 for NBT-key documentation. Unassigned.

**Verification.** JUnit cases per channel + boundary values + NBT
round-trip incl. out-of-range and typo-key reads; `./gradlew test`; compat
report rows updated.

---

### T-03 — Decide and scope random-name-generation parity (markovnames)

**Placement:** new issue under M3 or M10 (register gap — no existing issue).
**Type:** feature/decision · **Suggested priority:** P3 ·
**Owner:** unassigned · **State:** needs-input (scope decision)

**Intent.** The target ships a bundled Markov name generator
(`nikedemos.markovnames`, 15 classes, 11 cultures) + 19
`data/customnpcs/markovnames/*.txt` dictionaries feeding the creator GUI's
name randomize button. The production register has no issue for this feature;
19 compat rows claim `MAPPED_STORYNPCS_OBSERVED` via generic P2-1 while the
15 classes are `INVENTORY_ONLY` — a real feature invisible to the program.

**Expectation.** Either (a) a StoryNPCs name-generation feature exists —
YAML/loaded name dictionaries, deterministic seeded generator, exposed
through canonical ops to the creator UI/API/commands — or (b) an explicit
`INTENTIONAL_DEVIATION` is recorded with user-facing rationale and the compat
rows corrected.

**Acceptance criteria.**

- Decision recorded: implement vs. intentional deviation.
- If implemented: ≥ the target's 11 culture dictionaries represented as
  versioned YAML/data content; generator is deterministic under a seed;
  available to creator UI and API via canonical operations; no target code
  or dictionary text copied wholesale without license review
  (`nikedemos`/`markovnames` provenance + CC BY-NC target license noted —
  dictionary data reuse requires an explicit call).
- Compat rows for the 15 classes + 19 data files get honest terminal states
  either way.

**Context that code cannot infer.** Target license is `CC BY-NC` —
dictionary *content* reuse is a licensing question, not just an engineering
one; clean-room generation or user-supplied dictionaries may be required.

**Scope.** Domain + service + content packs + creator UI button + compat rows.

**Non-goals.** GUI polish beyond a working randomize path; copying target
dictionary files without a license decision.

**Dependencies and open decisions.** Owner decision on scope and license;
P2-1 (content families), P1-1 (canonical ops), P10-1 (UI surface).

**Verification.** Seeded determinism test, dictionary-load/validation test,
service-op test, compat-report row state check.

---

### T-04 — Mapping-integrity validator: MAPPED_STORYNPCS_OBSERVED must name a real artifact

**Placement:** new issue under M11 (supports P11-2). **Type:**
verification/tooling · **Suggested priority:** P1 · **Owner:** unassigned ·
**State:** ready-for-handoff

**Intent.** The markovnames case (T-03) shows `MAPPED_STORYNPCS_OBSERVED`
can assert "a mechanism exists that could represent this row" while the
consuming feature is entirely `INVENTORY_ONLY`. With 972 asset rows and 49
data rows sharing the pattern, the mapping state alone overclaims.

**Expectation.** The mapping validator distinguishes *capability-mapped*
("StoryNPCs could represent this") from *feature-mapped* ("a concrete
StoryNPCs artifact/behavior exists for this row"), or requires every
`MAPPED_STORYNPCS_OBSERVED` row to reference a verifiable artifact
(file/class/operation/test), failing rows that name only a milestone label.

**Acceptance criteria.**

- Validator rule (or split mapping state) implemented in `tools/parity/`.
- Re-classification sweep produces a delta report; rows failing the rule go
  to `INVENTORY_ONLY` or a feature-mapping state with a real `storynpcs_ref`.
- The markovnames data rows are among the corrected set (consumed by T-03).
- Report summary counts remain terminal (`0 unknown`) after reclassification.

**Context that code cannot infer.** The current label semantics were an
accepted design choice ("mechanism exists"); this issue changes the claim's
meaning, so the semantic change must be documented in
`docs/CUSTOMNPCS_PARITY_TRACEABILITY.md`, not silently re-labeled.

**Scope.** `tools/parity/` validator, compatibility report regeneration,
traceability docs.

**Non-goals.** Implementing any feature a row describes; changing gate
thresholds.

**Dependencies and open decisions.** P0-1/P0-2 tooling; T-03 consumes the
markovnames delta. Decision: new mapping state vs. artifact-ref requirement —
either satisfies intent.

**Verification.** Unit test for the new rule; fixture row set; regenerated
report diff reviewed.

---

### T-05 — Record target scripting engine contract and pin engine choice for P9-2

**Placement:** sub-issue/amendment of P9-2 (M9). **Type:** decision/api ·
**Suggested priority:** P2 · **Owner:** unassigned · **State:**
ready-for-handoff (decision recorded inside P9-2, not blocking)

**Intent.** P9-2's bounded scripting host needs an engine decision grounded
in fact: the target runs JSR-223 ECMAScript (`.js`), enumerates
`ScriptEngineManager` factories, and falls back to Nashorn
(`org.openjdk.nashorn:nashorn-core:15.4` arrives via NeoForge's library set —
not shaded, not a mods.toml dep). Runtime log confirms `ECMAScript: .js`.

**Expectation.** P9-2 (or a decision record) states the chosen engine and
the script-dialect compatibility claim. If Nashorn 15.4: declare the
dependency explicitly (don't rely on loader-provided libraries) and define
the sandbox wrapper over `javax.script`. If another engine: record the
dialect deviation explicitly.

**Acceptance criteria.**

- Decision record names engine + version + reason, referencing research/125
  F3 and how-others-did-it (KubeJS→Rhino, Denizen→own DSL, target→Nashorn).
- If Nashorn: `org.openjdk.nashorn:nashorn-core` pinned in `build.gradle`
  (15.4 is years old — satisfies supply-chain age policy); a boot smoke test
  asserts engine availability and fails loudly when absent.
- Sandbox contract (quotas from P9-2) is stated as wrapping whichever engine;
  no claim of script-API parity beyond `VERIFIED_TARGET_SOURCE` hook
  inventory.

**Context that code cannot infer.** Nashorn 15.4 is JDK-15-removed API on
Java 21 — reflection-free usage requires the artifact on the classpath.
Target wrapper classes live under `noppes.npcs.api.wrapper.*` and
`ScriptContainer` (source-verified inventory; hook *semantics* are
static-only).

**Scope.** `build.gradle` dependency (if Nashorn), engine-availability check,
decision record, P9-2 amendment.

**Non-goals.** Implementing the script host, sandbox, or hook dispatch
(P9-2 body); copying target wrapper classes.

**Dependencies and open decisions.** P9-2. Decision: Nashorn parity vs.
modern engine — recommend Nashorn for dialect parity unless security-review
objects (Nashorn is unmaintained upstream; sandbox must not rely on engine
for isolation regardless).

**Verification.** Boot smoke test for engine presence; dependency
resolution in clean checkout; decision record reviewed.

---

### T-06 — Produce live-NeoForge-runtime benchmark evidence (beyond headless GameTest)

**Placement:** extends P4-3 (M4) and unblocks #95-adjacent work. **Type:**
verification/infrastructure · **Suggested priority:** P1 · **Owner:**
unassigned · **State:** blocked (needs harness wiring decision)

**Intent.** All three certification benchmarks are
`HEADLESS_PASS_LIVE_RUNTIME_UNVERIFIED`; #95's GameTest harness isn't wired
into CI. Meanwhile the research repo ran the *target* on a real NeoForge
server with `heaphammer-1.1.0.jar` adversarial load (research/121–124). The
production project has no equivalent live-runtime evidence path.

**Expectation.** StoryNPCs runs the three benchmark scenarios (500-NPC
population, 25v25 siege, 2,500-NPC stress) inside a real NeoForge dedicated
server — not only headless GameTest — with artifacts recording numeric
thresholds, and (optionally) a target-side heaphammer baseline for
comparison.

**Acceptance criteria.**

- A repeatable harness provisions the NeoForge runtime + storynpcs jar and
  executes each scenario; artifacts land under `docs/parity/reports/` with
  the evidence schema's runtime fields.
- Benchmark rows distinguish `HEADLESS_PASS` vs `LIVE_RUNTIME_PASS`; the
  release gate requires the live-runtime state (or an explicit accepted
  deviation) for certification.
- Reuse of the research harness methodology (`tooling/`, heaphammer) is
  documented; no proprietary target artifacts enter the production repo.

**Context that code cannot infer.** Port 25565 may be owned by Docker on the
dev machine (research used 25566); harness must parameterize the port.
Client-side render cost is out of scope (no graphical harness).

**Scope.** `tools/` or `sim/` harness, CI wiring decision, benchmark reports,
gate report update.

**Non-goals.** Graphical-client benchmarking; changing numeric thresholds
(set by P4-3); claiming target-relative performance without a same-method
target baseline.

**Dependencies and open decisions.** #95 harness-in-CI decision; P4-3
thresholds; whether live-runtime benchmarks run in CI or on-demand
(resource cost) — needs owner input. **Blocked on:** harness provisioning
decision and CI capacity.

**Verification.** Artifact per scenario with pass/fail vs P4-3 thresholds;
reproducible from clean checkout docs; gate report reflects live state.

---

### T-07 — Repository hygiene: .gitattributes and deterministic report ordering

**Placement:** chore; attaches to M0/tooling. **Type:** chore ·
**Suggested priority:** P3 · **Owner:** unassigned · **State:**
ready-for-handoff

**Intent.** PR #119's adversarial review left two known nits: no
`.gitattributes` (the fingerprint fix handles EOL variance, but checkout-time
line endings still differ by platform) and nondeterministic
`forward_references` ordering in `release-gate-report.json`.

**Expectation.** `.gitattributes` pins text/EOL policy for source and
generated reports; `forward_references` (and any other list-valued report
fields feeding fingerprints) serialize in sorted order so identical inputs
yield byte-identical reports across platforms.

**Acceptance criteria.**

- `.gitattributes` added; `git check-attr` verifies policy for `*.java`,
  `*.json`, `*.yaml`, `*.md`.
- Report generation sorts `forward_references` deterministically; a
  regression test or check asserts stable output ordering.
- Existing fingerprint verification still passes on LF and CRLF checkouts.

**Context that code cannot infer.** Nits originate in PR #119 review; the
EOL fingerprint fix (#120) stays — this is defense in depth, not a revert.

**Scope.** `.gitattributes`, report generator ordering, one regression check.

**Non-goals.** Reformatting existing files; changing fingerprint algorithm.

**Dependencies and open decisions.** None blocking. Unassigned.

**Verification.** Fresh clone on LF and CRLF settings produces identical
report bytes; gate check green.

---

## Handoff audit

| Draft | Executable without inventing criteria? | Missing authority/input |
|---|---|---|
| T-01 | Yes — fixture set, schema, gate behavior specified | None blocking |
| T-02 | Yes for model facts; **needs owner decision** on immune inversion + NBT clamp policy | Product decision (flagged) |
| T-03 | Criteria exist for both branches; **needs scope + license decision** | Owner scope call; CC BY-NC dictionary question |
| T-04 | Yes — validator rule + delta report specified | Semantic-change sign-off noted in criteria |
| T-05 | Yes — decision record + optional dependency pin specified | Engine choice is the deliverable itself |
| T-06 | Partially — **blocked**: harness/CI provisioning decision and resource capacity unknown | Owner decision; CI capacity |
| T-07 | Yes — self-contained | None |

## Not turned into issues (deliberately)

- `noppes mark` type-index ambiguity (`PARTIAL`, research/05 §7): needs a
  client-assisted session to resolve; folded into T-01's provenance rules
  (excluded from OBSERVED import) rather than a standalone issue.
- GUI/client parity surfaces: remain `NOT_RUN (BLOCKED_NO_CLIENT_HARNESS)` —
  already tracked by fixture `next_evidence` fields; no new issue adds
  evidence.
- `AttackStrenght` typo and full melee/ranged NBT key lists: recorded in
  research/125 F1 and required by T-02/P11-1 documentation — they are
  evidence details, not standalone work items.
