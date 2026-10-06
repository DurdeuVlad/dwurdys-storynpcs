# CustomNPCs parity — gap milestone plan

Status: `active` — MG-A–MG-D issues filed on GitHub (#147–#151) and implemented on the open PR stack #152–#157. Post-stack milestone set MG-E–MG-L added 2026-10-05 (same-day refresh).
Compiled: 2026-10-05. Refreshed: 2026-10-05 (post-implementation stack).
Basis: [CUSTOMNPCS_FEATURE_COMPARISON.md](CUSTOMNPCS_FEATURE_COMPARISON.md) Part 4 + verified `target-surface-manifest.json` + `tools/parity/check_feature_status.py` floor evidence.

## Contract

- **Goal:** cover every CustomNPCs target surface that has no owning issue, and sequence the work that turns declared-only StoryNPCs types into runtime-wired features.
- **System:** additive to the canonical [issue register](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md). No existing P-issue is rewritten; new items use placeholder IDs `G-*` until tracked.
- **Constraints:** product decisions that only the owner can make are marked `needs-input` — they are not guessed. No invented dates, assignees, or estimates.
- **Evaluation:** every milestone names an observable outcome and proof; every issue is consumable by `/flux-close-issue` without invented acceptance criteria.

## Repository facts used

- `src/main/java` has 311 files; ~15 domain areas are type-only (no entity tick, screen, packet, or command consumes them): scenes, linked NPCs, transforms, timers, natural spawns, scripted hooks, custom GUI, overlays, carpentry recipes, authoring hub, sim tiers/squad/benchmark, script host.
- `NpcJobRuntime` dispatches 9 of 11 `JobType`s (BUILDER, FOLLOWER fail validation). Zero block registrations exist. No script interpreter dependency exists.
- `NpcDefinition` already binds: display, stats, ai, dialogueId, factionId, marks, inventory, rules, trader, banker, job, companion, bard, healer, postman, transporter.
- Open `needs-input` issues already exist: #122 (resistance contract), #123 (markov names), #126 (live benchmark evidence).
- **Refresh facts (2026-10-05):** G-B1–G-B4 + G-D1 are implemented on the open PR stack #152–#157 (unmerged). Feature-status checker floor on the stack tip: `missing=8, declared=10, wired=13, tested=32` across 63 rows. Every missing/declared/wired row has an open owning issue except `integrations` — #87 closed COMPLETED as a documented no-code deviation, so its map claim was corrected `planned → deviation` in this refresh. Genuinely unowned residual: schematic lifecycle/placement hardening → new issue G-E1 below.

---

## Milestone MG-A — Decision gate

**Outcome:** every `needs-input` product decision that blocks an implementation issue is resolved and recorded as a decision note.
**Status:** the two new decisions below were **resolved by the owner on 2026-10-05** — no GitHub issues needed; they are recorded here and in `Decision.md` (ADR-006).

### Decision D-A1 — SQL player-data store: recorded deviation (resolved)

- **Decision:** No SQL store. All admin-authorable content and configuration lives in human-editable YAML/TOML/files. The only exceptions are concurrency-sensitive runtime stores (player progression, economy/trade/bank state, operation journals, mail) which remain managed durable stores that admins must not hand-edit.
- **Consequence:** `noppes.npcs.db.DatabaseController` / `persistence_stores.database` is an **intentional deviation**; migration path for CustomNPCs database users is "export to files via importer" (P11-1 scope). Recorded in `Decision.md` ADR-006 and folded into #86 P9-4 scope.
- **Resolved by:** Vlad, 2026-10-05.

### Decision D-A2 — Dialog/quest categories: parity (resolved)

- **Decision:** Parity — dialogue and quest schemas gain a `category` (folder) field; editors group by it; P11-1 import maps target folder trees onto it.
- **Consequence:** folded into #65 P5-1 (dialogue) and #68 P5-4 (quests) scope; register expectations updated.
- **Resolved by:** Vlad, 2026-10-05.

### Existing decision issues (still open, already tracked)

| Issue | Decision | Blocks |
|---|---|---|
| #123 | Markov name-generation parity scope | bundled content G-B3 (names only, not schematics) |
| #125 | Scripting engine choice | #84 P9-2 |
| #122 | Canonical stats/resistance contract | #59 P3-2 |
| #126 | Live-runtime benchmark evidence source | #64 P4-3 |

---

## Milestone MG-B — Uncovered target surfaces

**Outcome:** every manifest surface with zero issue coverage has an owning issue with executable acceptance criteria.
**Scope:** new feature issues only; no overlap with existing P-issue scope (each draft names its boundary against neighbors).
**Depends on:** MG-A where noted.
**Proof:** comparison doc Part 4 has zero `❌`/`❓` rows.

### Issue G-B1 — Implement NPC combat abilities (block, pull, push, smash, snare, teleport)

- **Intent:** The target ships six passive NPC combat abilities (`noppes.npcs.ability`: `AbilityBlock`, `AbilityPull`, `AbilityPush`, `AbilitySmash`, `AbilitySnare`, `AbilityTeleport`, plus `DataAbilities` + `EntityAIAbilities`). No StoryNPCs code or issue references them — this is the largest single uncovered combat surface.
- **Expectation:** NPC definitions carry an `abilities` list (type + trigger + parameters); abilities fire on authored triggers (attack/damaged/update per `IAbility*` contract) through canonical evaluation; editor + commands expose them; YAML round-trips.
- **Acceptance criteria:** All six ability types have schema, runtime behavior, and a fixture each; triggers and parameters are bounded (no unbounded teleport/pull distances); ability effects route through canonical operations where they mutate other actors; `DataAbilities` fields map to YAML with diagnostics; abilities respect P4 tick budgets.
- **Context code cannot infer:** Target abilities are target-only vocabulary; the exact trigger matrix (`IAbilityAttack/Damaged/Update`) is defined by the decompiled target, not by our rule engine — reuse `domain/rule` conditions where equivalent rather than building a parallel engine.
- **Scope:** `domain/ability/*`, entity integration, `NpcDefinition` field, editor section, serde, fixtures. Type: feature; priority P1; milestone: CustomNPCs parity M3; owner: combat runtime; unassigned.
- **Non-goals:** new ability types beyond the six; script-driven abilities (P9-2 territory).
- **Dependencies:** #59 P3-2, #60 P3-3, #51 P1-1.
- **Verification:** per-ability behavior fixture, bounded-parameter rejection, reload round-trip, unload cleanup.
- **Status:** implemented in open PR [#154](https://github.com/DurdeuVlad/dwurdys-storynpcs/pull/154) (unmerged) — all six abilities authored + runtime-wired; resistance-scaled i-frame comparison and additive bonus-damage via `invulnerableTime` reset verified in review.

### Issue G-B2 — Implement NBT book and support entities (chair mount, fake living)

- **Intent:** P8-2 (#78) already names path/mount/teleporter/remover/soulstone — this issue covers only what it does not: `ItemNbtBook`/`GuiNbtBook` and the utility entities `EntityChairMount`/`EntityFakeLiving` that the mounter/sit and scripted display features need.
- **Expectation:** NBT book opens a safe viewer/editor for permitted targets (viewer for normal users; writes restricted per D-A1's file-first rule — no arbitrary NBT writes that would bypass canonical operations); chair-mount/fake-living entities exist behind the same projection rules as `StoryNpcEntity` (P1-2) and clean up on unload.
- **Acceptance criteria:** NBT book is permission-gated server-side; write operations are allowlisted or explicitly read-only with a recorded deviation; support entities never outlive their owner session and survive reload correctly; session-scoped selection (no static maps — the P3-4/P8-2 requirement).
- **Context code cannot infer:** the target NBT book writes arbitrary entity/block NBT — that conflicts with YAML-first/canonical-operations rules, so the StoryNPCs write scope is deliberately narrower (read + allowlisted fields); document the deviation in the PR.
- **Scope:** item, support entities, GUI, payloads, fixtures. Type: feature; priority P2; milestone: CustomNPCs parity M8; unassigned.
- **Non-goals:** generic world-edit NBT access; soulstone/teleporter/remover (owned by #78 P8-2); scripted item execution (P9-2).
- **Dependencies:** #61 P3-4, #78 P8-2, #54 P1-4.
- **Verification:** permission matrix, session isolation, unload cleanup, allowlist rejection fixtures.
- **Status:** implemented in open PR [#155](https://github.com/DurdeuVlad/dwurdys-storynpcs/pull/155) (unmerged) — 5-key allowlist via typed setters (deviation from arbitrary-write parity, per the recorded context), perm-2 + session + range + throttle gates; chair/fake-living entities registered and cleaned up.

### Issue G-B3 — Ship the bundled schematic content pipeline

- **Intent:** The target bundles 27 `.schematic` structures (bakery, church, guard tower, ship, stalls, tier houses, walls…) consumed by the Builder job and `/noppes schema` commands. StoryNPCs has no schematic reader or bundled content — the builder job issue (#73) covers the *job* but not the *content format/pipeline*.
- **Expectation:** A `Schematic`/`SpongeSchem`-compatible reader loads bundled structures; builder job (P6-4) consumes them; `/storynpcs schema` parity commands (P9-3 scope) can list/build/stop them. **Decision (owner, 2026-10-05): port all 27** target bundled structures — they ship as mod assets.
- **Acceptance criteria:** All 27 target schematics (archery_range, bakery, barn, building_site, chapel, church, gate, glassworks, guard_tower, guild_house, house, house_small, inn, library, lighthouse, mill, observatory, ship, shop, stall, stall2, stall3, tier_house1-3, tower, wall, wall_corner) load and build; legacy `.schematic` → internal representation path recorded; malformed/oversized schematics fail with diagnostics; builds are bounded and chunk-safe.
- **Context code cannot infer:** "port all" includes the original files — implementer extracts them from the target jar `data/customnpcs/schematics/` (licensing note: they are CustomNPCs assets — confirm redistribution terms or re-author equivalents if the license blocks porting; flag in PR if uncertain).
- **Scope:** schematic domain/reader, 27 bundled assets, builder-job + command integration. Type: feature; priority P2; milestone: CustomNPCs parity M8; unassigned.
- **Non-goals:** world-edit-class tooling; runtime schematic authoring UI.
- **Dependencies:** #73 P6-4, #85 P9-3.
- **Verification:** per-structure load/build fixture, malformed/oversize rejection, bounded build fixture, command parity entries.
- **Status:** delivered — the pinned jar actually ships **28** `.schematic` files (the "27" figures here and in the filed issue were a miscount); all 28 are bundled at `data/storynpcs/schematics/` and covered by `BundledSchematicTest` (per-structure load + 4-rotation build). Redistribution terms resolved: the jar declares `license="CC BY-NC"` — assets ship with `ATTRIBUTION.txt` crediting Noppes (non-commercial use only). Remaining honest gap: no live `ServerLevel` chunk-placement fixture.

### Issue G-B4 — Implement player-facing screen suite

- **Intent:** P10-1 (#88) is the *creator* authoring hub; the target also has ~20 *player-facing* screens with no dedicated owner: quest log (`GuiQuestLog`), faction panel (`InventoryTabFactions`), mail read/write (`GuiMailbox`, `GuiMailmanWrite`, `SubGuiMailmanSendSetup`), transport selection (`GuiTransportSelection`), follower hire/setup (`GuiNpcFollowerHire/Setup`), companion stats/talents/inventory (`GuiNpcCompanion*`), achievements (`GuiAchievement`), carpentry bench (`GuiNpcCarpentryBench`), custom scripted GUIs (`GuiCustom`).
- **Expectation:** Every player-facing interaction reachable in the target has a StoryNPCs screen or an explicitly recorded deviation; screens read only through server-issued views (no client-side mutation); stale revisions are handled per P1-3.
- **Acceptance criteria:** Per-screen open/data/commit path exists for: quest log + turn-in status, faction standing panel, mail list/read/write, transport destination picker (unlocked-only), follower hire flow, companion inv/stats/talents, achievement view, carpentry bench, custom GUI runtime renderer; each fails closed on stale session/revision.
- **Context code cannot infer:** several depend on unfinished backends (P5-5 quest log needs recovered-outcome surfacing; transport needs P6-3 unlock persistence) — this issue may split per screen if it grows past one PR; keep one issue until a sub-screen independently blocks.
- **Scope:** `client/gui/player/*`, screen models, open/data payloads, fixtures. Type: ui/feature; priority P2; milestone: new `CustomNPCs parity M12: player-facing surfaces` (or M10 — decide at creation); unassigned.
- **Non-goals:** creator/authoring screens (P10-1); scripted-GUI *authoring* (P8-6 — this issue only renders the runtime side).
- **Dependencies:** #69 P5-5, #70 P6-1, #71 P6-2, #72 P6-3, #74 P6-5, #75/#76, #79 (mailbox block), #80 (carpentry), #82 (custom GUI runtime).
- **Verification:** UI automation per screen, stale-revision rejection, permission denial, minimum-resolution (854×480, GUI scale 2) fixtures.
- **Status:** wave 1 delivered quest log, faction standing, mail list/read/write, transport picker; wave 2 (MG-J) delivered follower hire (`PlayerFollowerHireScreen` → canonical `follower.owner.set`), companions view (`PlayerCompanionScreen`), achievements (`PlayerAchievementsScreen`), carpentry bench (`PlayerCarpentryScreen` → `CarpentryBench` two-phase craft), and the authored-GUI runtime renderer (`CustomGuiScreen` + session-bound action/close events). All commits are session-bound server-issued views; deviations (per-slot companion inventory, follower setup/job GUIs, texture binding) are recorded in `docs/parity/M12-PROGRESS.md`. Live GUI automation remains an environment gap — headless evidence only.

### G-B5 — Cosmetic parts / emote animations scope: resolved without a new issue

- **Resolution (2026-10-05):** `MpmPart*` cosmetic parts and the 10 `Ani*` emotes sit inside #58 P3-1's boundary ("model, render features") but were unnamed in its expectation. Fixed by amending the P3-1 expectation text in the issue register (names parts + emotes explicitly) and a scope comment on GitHub #58 — no separate issue needed.

---

## Milestone MG-C — Declared-to-wired closure

> **Refresh note (2026-10-05):** MG-C's ordering is absorbed into milestones MG-G–MG-L below; its per-issue "what wired means" column remains the exit-evidence definition for those milestones. Keep both — this table is still the sharpest wiring contract.

**Outcome:** zero 🧩 "declared" rows remain in the comparison doc — every domain package is consumed by an entity tick, screen, packet, command, or service path.
**Scope:** execution ordering of **existing** issues only — no new issues. This milestone exists because the recurring failure mode is "domain types land, runtime wiring and acceptance stay open".
**Depends on:** MG-A decisions; M1–M2 foundations.

| Order | Issue | What "wired" means (exit evidence) |
|---|---|---|
| 1 | #72 P6-3 transport | `requestTransport` executes a real safe teleport incl. cross-dimension timeout/recovery; unlock persists exactly-once; transport selection reachable in-game |
| 2 | #73 P6-4 jobs | BUILDER + FOLLOWER validate and run; all 11 have bounded tick, stop on unload |
| 3 | #74 P6-5 companion | stages/talents apply effects; companion inventory works; dismissal policy returns state |
| 4 | #71 P6-2 postman/mail | player can write→send→receive mail via mailbox + postman role end-to-end |
| 5 | #81 P8-5 world orchestration | linked NPCs resolve at runtime; scenes play/cancel; transforms fire; timers survive restart; natural spawns produce entities under quotas |
| 6 | #79 P8-3 blocks | all 11 target block functions exist (or recorded deviation); scripted block/door hold inert hook bindings |
| 7 | #80 P8-4 recipes | carpentry recipe lifecycle load→register→craft→reload |
| 8 | #82 P8-6 custom GUI/overlay | `CustomGuiLayout` renders runtime custom GUIs; `OverlaySession` drives HUD with expiry |
| 9 | #84 P9-2 scripting | an actual interpreter executes bounded scripts on the hook matrix (blocked by #125) |
| 10 | #86 P9-4 admin | remote/player-data/global/config surfaces live (pending G-A1) |
| 11 | #88 P10-1 hub | `AuthoringHub` wired into screens |
| 12 | #89 P10-2 patch plans | apply path with deterministic ordering + rollback |
| 13 | #91 P11-1 import | real CustomNPCs export imports into YAML families |

**Proof:** per-issue acceptance in the register + a milestone-level regression run (`./gradlew test` + fixture harness) where every manifest row mapped to these issues reports at least `MAPPED_STORYNPCS_OBSERVED`.

**Risk:** this ordering assumes M3 combat/display lands first (it is the hard dependency for most of these). If M3 stalls, reorder 1–4 ahead — they depend on entity/services already present, not on full combat parity.

---

## Milestone MG-D — Comparison-doc convergence

**Outcome:** the feature comparison is regenerated from evidence (manifest + src scan), not hand-maintained, so it cannot drift again.
**Scope:** small tooling issue; the comparison doc's statuses become generated/test-asserted.

### Issue G-D1 — Generate the feature comparison from manifest + source evidence

- **Intent:** today's doc is hand-compiled; it will drift. The parity tools already generate manifests and catalogs — add a generator that emits the Part-2 table (feature → files → issue → status) and fails CI when a row's claimed status contradicts code evidence.
- **Acceptance criteria:** `tools/parity/` gains a comparator that produces `docs/CUSTOMNPCS_FEATURE_COMPARISON.md` (or a generated section of it) deterministically; CI fails on stale rows; statuses derive from observable signals (registrations, tick wiring, payload handlers, test names), not prose.
- **Scope:** tooling only. Type: tooling; priority P2; milestone: M11; unassigned.
- **Non-goals:** replacing the engineering register/traceability docs.
- **Dependencies:** existing `tools/parity/` harness. **Verification:** regeneration is byte-stable; a planted drift is caught.
- **Status:** implemented in open PR [#153](https://github.com/DurdeuVlad/dwurdys-storynpcs/pull/153) (unmerged) — `check_feature_status.py` derives per-row floor (missing/declared/wired/tested) from file/wired/registered/test signals, regenerates the doc's marked section (`--write`), enforces 2-hop declared-isolation, and runs in CI.

---

# Refresh — 2026-10-05: post-stack milestone set

MG-A–MG-D covered issue *coverage*. With the stack delivered, the remaining gap is pure **execution**: 6 unowned missing rows (all inside existing P-issue scope), 10 declared rows awaiting wiring, 13 wired rows awaiting test evidence, ~40 partial rows inside open P-issue scope, 4 open decisions, and 1 uncovered residual (G-E1).

## Floor snapshot (stack tip, `check_feature_status.py`)

| Floor | Count | Meaning | Rows |
|---|---|---|---|
| missing | 8 | no code evidence | entity-variants, cosmetic-parts, emote-animations (#58) · companion-jobs (#74) · creator-blocks (#79/#80) · markov-names (#123, needs-input) · **sql-store**, **integrations** — intentional deviations, not work |
| declared | 10 | types exist, unconsumed | advanced-surfaces (#81/#86), spawner-blocks (#77), scripted-block-door (#79/#84), mailbox-carpentry (#79/#80), scenes (#81), linked-transform-timer-spawn (#81), recipes-carpentry (#80), custom-gui (#82), overlays-presets (#82), authoring-hub (#88) |
| wired | 13 | consumed, untested | availability-gates, marks, melee, ranged, resistances-immunities, dialog-categories, quest-deps-team, moving-path-tool, mount-teleport-soulstone, scripting, config-toggles, api-events, public-api |
| tested | 32 | test evidence present | — |

## Milestone MG-E — Land the delivery stack (gate)

**Outcome:** PRs #152–#157 merged to `main`; issues #147–#151 closed; the milestone baseline moves to merged code.
**Scope:** process gate — no new implementation issues. Merge order (stack dependency): #152 docs → #153 checker → #154 abilities → #155 NBT book → #156 schematics → #157 screens. Each `chore(stack)` reconcile commit already carries the merged-forward state.
**Proof:** `gh pr list --state open` shows none of #152–#157; CI green on `main` post-merge; `check_feature_status --check` + `check_truth_gate` pass on `main`.
**Blocks:** all milestones below — new work should branch from merged `main`, not extend the stack.

## Milestone MG-F — Decision gate (existing `needs-input` issues)

**Outcome:** all four open decision issues carry recorded owner decisions; no implementation issue stays blocked on an unmade decision.
**Scope:** decisions only — already filed; nothing new to implement.

| Issue | Decision needed | Unblocks |
|---|---|---|
| #122 | canonical stats/resistance/immunity contract | #59 P3-2; reconciles open PR #116 vs merged #117 |
| #123 | markov name-generation parity scope | bundled name content (G-B3 shipped schematics; names still pending) |
| #125 | scripting engine pin | #84 P9-2 |
| #126 | live-runtime benchmark evidence source | #64 P4-3 certification |

**Proof:** each issue gets a decision comment + `Decision.md`/register entry; dependent issues' blocked notes removed.

## Milestone MG-G — Core NPC capability (GitHub M3–M4)

**Outcome:** every combat/display/AI/sim row reaches ≥ `wired` floor; the 3 missing rows under #58 land; sim certification evidence is published.
**Scope:** #58 (display, entity-variants, cosmetic-parts, emote-animations), #59 (stats/melee/ranged/resistances/defeat — gated on #122), #60 (AI/movement/targeting), #61 (inventory/equipment/marks), #62–#64 (sim tiers, path/squad, certification — #64 gated on #126), #118 (resistance overlap — resolve via #122).
**Proof:** Part-2 combat/display/sim rows at ≥ wired floor; `./gradlew test` green; #64 certification report published; #116 closed or rebased per #122 outcome.

## Milestone MG-H — Social & economy runtime (GitHub M5–M7)

**Outcome:** dialogue/quest/faction/role/job/transport/companion/trade/bank rows reach ≥ `wired` floor; companion-jobs missing row lands.
**Scope:** #65–#67 (dialogue runtime/UI/editor incl. D-A2 `category` field), #68/#69 (quests: deps, team, repeats, completion), #70 (factions), #71 (service/social roles + postman/mail e2e), #72 (transport), #73 (all 11 JobTypes dispatch — BUILDER/FOLLOWER included), #74 (companion lifecycle + companion-jobs), #75/#76 (trader/banker).
**Proof:** per-issue acceptance; transport executes real safe teleports incl. cross-dimension; mail write→send→receive e2e; `NpcJobRuntime` dispatches all 11 types.

## Milestone MG-I — World systems & creator tools (GitHub M8)

**Outcome:** zero `declared` floor rows — every declared package is runtime-consumed or carries a recorded deviation; creator blocks exist; schematic residuals closed.
**Scope:** #77 (clone library + spawner-blocks), #78 (moving-path tool, mount/teleport/soulstone — 2 wired rows awaiting tests), #79 (creator-blocks incl. scripted door + mailbox), #80 (recipes/carpentry), #81 (linked NPCs, scenes, transforms, timers, natural spawn + advanced-surfaces shared with #86), #82 (custom GUI + overlays + presets), **G-E1** (new issue below).
**Ordering:** #79 block registrations before #84 scripted-door hooks; #80 carpentry before the MG-J bench screen; #81 is the riskiest wiring (scenes/transforms/timers all share it).
**Proof:** `declared=0` in checker output; per-surface wiring evidence per MG-C's exit-evidence table (absorbed into this milestone set).

## Milestone MG-J — Player surfaces wave 2 (GitHub M12)

**Outcome:** every player-facing target screen has a StoryNPCs screen or a recorded deviation.
**Scope:** continuation under #150 (still open) — split into per-screen issues only when one independently blocks.

| Wave | Screens | Status |
|---|---|---|
| 1 — delivered, PR #157 | quest log, faction standing, mail read/write, transport picker | done |
| 2a | follower hire (`PlayerFollowerHireScreen`, canonical `follower.owner.set`) | done |
| 2b | companion stats/talents/capacity (`PlayerCompanionScreen`); per-slot inventory → deviation | done (partial → recorded deviation) |
| 2c | achievement view (`PlayerAchievementsScreen`, earned-progress rows) | done |
| 2d | carpentry bench (`PlayerCarpentryScreen` + `CarpentryBench` craft) | done |
| 2e | custom GUI runtime renderer (`CustomGuiScreen`/`CustomGuiScreenModel`, session-bound events) | done |

**Proof:** headless per-screen view/payload/session fixtures; stale-session and replay rejection pinned in `RuntimeSessionRegistryTest`/`PlayerPanelPayloadsTest`; `CarpentryBench` two-phase craft math unit-tested. 854×480 @ GUI-scale-2 live-render fixtures remain an environment gap (no live Minecraft harness) — disclosed in `docs/parity/M12-PROGRESS.md`.

## Milestone MG-K — Extension & admin surfaces (GitHub M9)

**Outcome:** public API, scripting host, command parity, and admin/config surfaces reach ≥ `wired` floor.
**Scope:** #83 (public API + api-events), #84 (scripting host — gated on #125; scripted-door hooks depend on #79), #85 (command parity vs the target's 70 commands), #86 (admin/remote/player-data/global/config + advanced-surfaces shared with #81).
**Proof:** API surface compiles with event fixtures; bounded-script execution fixtures on the hook matrix; command parity matrix; permission matrix per P1-4 capability rules.

## Milestone MG-L — Authoring, import & certification (GitHub M10–M11)

**Outcome:** authoring hub consumes the M3–M8 surfaces; AI patch plans apply; creator docs ship; real CustomNPCs exports import; evidence closes; release gate passes.
**Scope:** #88 (authoring hub — currently `declared`, depends on MG-G–MG-I), #89 (patch plans), #90 (creator docs), #91 (import contract — real export → YAML families), #92 (evidence closure), #93 (release certification), #95 (headless GameTest harness — unblocks G-E1 criterion 1).
**Proof:** release-gate report green; import round-trip fixture against a real CustomNPCs export; GameTest run in CI.

## New issue — G-E1: Schematic lifecycle hardening + live placement fixture

- **Intent:** close the honest residuals left by #149 — the pipeline is verified against files/domain but not a live world, and two lifecycle details remain open.
- **Expectation:** (1) a schematic build places blocks in a real `ServerLevel` via the headless GameTest harness (#95) — at minimum one bundled structure builds in-world with correct placed-block count, applied rotation, and unloaded-chunk cells skipped; (2) `SchematicBuildService.stopAll()` is invoked on server stop so active builds cannot hold stale `ServerLevel` references across save reloads; (3) `*.schematic binary` is declared in `.gitattributes` (all 28 assets are gzip so `text=auto` sniffs correctly today — this makes the classification explicit).
- **Acceptance criteria:** live-level fixture asserts placed-block parity for ≥1 bundled schematic incl. a non-zero rotation; the server-stop path calls `stopAll` (test or registered hook evidence); `.gitattributes` contains `*.schematic binary`; `BundledSchematicTest`'s `file:`-protocol assumption is relaxed or documented as Gradle-only.
- **Context code cannot infer:** none — all surfaces are internal.
- **Scope:** `SchematicBuildService`, `StoryNpcs` lifecycle wiring, `.gitattributes`, GameTest/live fixture. Type: hardening; priority P3; milestone M8; unassigned.
- **Non-goals:** new schematic formats, world-edit tooling, runtime authoring UI.
- **Dependencies:** #95 for criterion 1; none for criteria 2–3 (can land independently).
- **Verification:** fixture report shows in-world placement; `./gradlew test` + GameTest green.

---

## Handoff audit

- Each G-issue carries intent, expectation, acceptance criteria, non-code context, scope/non-goals, dependencies, and verification — consumable by `/flux-close-issue`.
- **Filed on GitHub (2026-10-05):** G-B1 → [#147](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/147), G-B2 → [#148](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/148), G-B3 → [#149](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/149), G-B4 → [#150](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/150) (new milestone `CustomNPCs parity M12: Player-facing surfaces`, #21), G-D1 → [#151](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/151). Scope/decision comments posted on #58, #65, #68, #86.
- **Filed on GitHub (2026-10-05, refresh):** G-E1 → [#158](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/158) (milestone M8).
- **Resolved without new issues:** D-A1 (SQL → deviation, ADR-006), D-A2 (categories → parity, folded into #65/#68), G-B5 (parts/emotes → #58 scope amendment). Refresh: `integrations` map claim corrected to `deviation` (#87 closeout is the contract record); wave-2 player screens stay under open #150 per MG-J.
- **Still needs owner input:** existing #122/#123/#125/#126 (MG-F).
- **PR contract:** every PR for these issues must repeat intent, expectation, acceptance criteria, non-code context, scope/non-goals, affected areas/ownership, dependencies, and verification evidence — `Not applicable`/`Unknown—blocked` where honest. A link to the issue alone does not satisfy review.
- **Milestone placement:** G-B1 → M3; G-B2, G-B3 → M8; G-B4 → new milestone `CustomNPCs parity M12: Player-facing surfaces`; G-D1 → M11; G-E1 → M8. MG-C/MG-E–MG-L are ordering overlays on existing issues.

## Residual risks

- G-B4 is large; expect to split per screen during implementation rather than up-front. Wave-2 screens are each backend-blocked (MG-J table) — a screen that blocks independently should fork into its own issue at that point, not before.
- Abilities (G-B1) and the rule engine overlap — implementers must reuse `domain/rule` conditions or justify a second trigger engine in the PR.
- "Wiring" evidence for P-issues in MG-C is only honest if tests exercise the entity/packet boundary, not just domain round-trips — the register already requires this; do not accept domain-only closes.
- The open stack (#152–#157) is unmerged; every milestone below MG-E assumes it lands first. If the stack stalls or is abandoned, re-derive the floor snapshot before re-planning.
- `markov-names` (#123) is the only content surface with no committed parity decision — MG-F resolves it before any bundled-name work is scheduled.
