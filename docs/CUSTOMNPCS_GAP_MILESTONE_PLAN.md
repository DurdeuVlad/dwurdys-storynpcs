# CustomNPCs parity — gap milestone plan

Status: `draft` — local plan. No GitHub issues have been created or modified; that requires explicit authorization.
Compiled: 2026-10-05.
Basis: [CUSTOMNPCS_FEATURE_COMPARISON.md](CUSTOMNPCS_FEATURE_COMPARISON.md) Part 4 + verified `target-surface-manifest.json`.

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

### Issue G-B2 — Implement NBT book and support entities (chair mount, fake living)

- **Intent:** P8-2 (#78) already names path/mount/teleporter/remover/soulstone — this issue covers only what it does not: `ItemNbtBook`/`GuiNbtBook` and the utility entities `EntityChairMount`/`EntityFakeLiving` that the mounter/sit and scripted display features need.
- **Expectation:** NBT book opens a safe viewer/editor for permitted targets (viewer for normal users; writes restricted per D-A1's file-first rule — no arbitrary NBT writes that would bypass canonical operations); chair-mount/fake-living entities exist behind the same projection rules as `StoryNpcEntity` (P1-2) and clean up on unload.
- **Acceptance criteria:** NBT book is permission-gated server-side; write operations are allowlisted or explicitly read-only with a recorded deviation; support entities never outlive their owner session and survive reload correctly; session-scoped selection (no static maps — the P3-4/P8-2 requirement).
- **Context code cannot infer:** the target NBT book writes arbitrary entity/block NBT — that conflicts with YAML-first/canonical-operations rules, so the StoryNPCs write scope is deliberately narrower (read + allowlisted fields); document the deviation in the PR.
- **Scope:** item, support entities, GUI, payloads, fixtures. Type: feature; priority P2; milestone: CustomNPCs parity M8; unassigned.
- **Non-goals:** generic world-edit NBT access; soulstone/teleporter/remover (owned by #78 P8-2); scripted item execution (P9-2).
- **Dependencies:** #61 P3-4, #78 P8-2, #54 P1-4.
- **Verification:** permission matrix, session isolation, unload cleanup, allowlist rejection fixtures.

### Issue G-B3 — Ship the bundled schematic content pipeline

- **Intent:** The target bundles 27 `.schematic` structures (bakery, church, guard tower, ship, stalls, tier houses, walls…) consumed by the Builder job and `/noppes schema` commands. StoryNPCs has no schematic reader or bundled content — the builder job issue (#73) covers the *job* but not the *content format/pipeline*.
- **Expectation:** A `Schematic`/`SpongeSchem`-compatible reader loads bundled structures; builder job (P6-4) consumes them; `/storynpcs schema` parity commands (P9-3 scope) can list/build/stop them. **Decision (owner, 2026-10-05): port all 27** target bundled structures — they ship as mod assets.
- **Acceptance criteria:** All 27 target schematics (archery_range, bakery, barn, building_site, chapel, church, gate, glassworks, guard_tower, guild_house, house, house_small, inn, library, lighthouse, mill, observatory, ship, shop, stall, stall2, stall3, tier_house1-3, tower, wall, wall_corner) load and build; legacy `.schematic` → internal representation path recorded; malformed/oversized schematics fail with diagnostics; builds are bounded and chunk-safe.
- **Context code cannot infer:** "port all" includes the original files — implementer extracts them from the target jar `data/customnpcs/schematics/` (licensing note: they are CustomNPCs assets — confirm redistribution terms or re-author equivalents if the license blocks porting; flag in PR if uncertain).
- **Scope:** schematic domain/reader, 27 bundled assets, builder-job + command integration. Type: feature; priority P2; milestone: CustomNPCs parity M8; unassigned.
- **Non-goals:** world-edit-class tooling; runtime schematic authoring UI.
- **Dependencies:** #73 P6-4, #85 P9-3.
- **Verification:** per-structure load/build fixture, malformed/oversize rejection, bounded build fixture, command parity entries.

### Issue G-B4 — Implement player-facing screen suite

- **Intent:** P10-1 (#88) is the *creator* authoring hub; the target also has ~20 *player-facing* screens with no dedicated owner: quest log (`GuiQuestLog`), faction panel (`InventoryTabFactions`), mail read/write (`GuiMailbox`, `GuiMailmanWrite`, `SubGuiMailmanSendSetup`), transport selection (`GuiTransportSelection`), follower hire/setup (`GuiNpcFollowerHire/Setup`), companion stats/talents/inventory (`GuiNpcCompanion*`), achievements (`GuiAchievement`), carpentry bench (`GuiNpcCarpentryBench`), custom scripted GUIs (`GuiCustom`).
- **Expectation:** Every player-facing interaction reachable in the target has a StoryNPCs screen or an explicitly recorded deviation; screens read only through server-issued views (no client-side mutation); stale revisions are handled per P1-3.
- **Acceptance criteria:** Per-screen open/data/commit path exists for: quest log + turn-in status, faction standing panel, mail list/read/write, transport destination picker (unlocked-only), follower hire flow, companion inv/stats/talents, achievement view, carpentry bench, custom GUI runtime renderer; each fails closed on stale session/revision.
- **Context code cannot infer:** several depend on unfinished backends (P5-5 quest log needs recovered-outcome surfacing; transport needs P6-3 unlock persistence) — this issue may split per screen if it grows past one PR; keep one issue until a sub-screen independently blocks.
- **Scope:** `client/gui/player/*`, screen models, open/data payloads, fixtures. Type: ui/feature; priority P2; milestone: new `CustomNPCs parity M12: player-facing surfaces` (or M10 — decide at creation); unassigned.
- **Non-goals:** creator/authoring screens (P10-1); scripted-GUI *authoring* (P8-6 — this issue only renders the runtime side).
- **Dependencies:** #69 P5-5, #70 P6-1, #71 P6-2, #72 P6-3, #74 P6-5, #75/#76, #79 (mailbox block), #80 (carpentry), #82 (custom GUI runtime).
- **Verification:** UI automation per screen, stale-revision rejection, permission denial, minimum-resolution (854×480, GUI scale 2) fixtures.

### G-B5 — Cosmetic parts / emote animations scope: resolved without a new issue

- **Resolution (2026-10-05):** `MpmPart*` cosmetic parts and the 10 `Ani*` emotes sit inside #58 P3-1's boundary ("model, render features") but were unnamed in its expectation. Fixed by amending the P3-1 expectation text in the issue register (names parts + emotes explicitly) and a scope comment on GitHub #58 — no separate issue needed.

---

## Milestone MG-C — Declared-to-wired closure

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

---

## Handoff audit

- Each G-issue carries intent, expectation, acceptance criteria, non-code context, scope/non-goals, dependencies, and verification — consumable by `/flux-close-issue`.
- **Filed on GitHub (2026-10-05):** G-B1 → [#147](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/147), G-B2 → [#148](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/148), G-B3 → [#149](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/149), G-B4 → [#150](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/150) (new milestone `CustomNPCs parity M12: Player-facing surfaces`, #21), G-D1 → [#151](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/151). Scope/decision comments posted on #58, #65, #68, #86.
- **Resolved without new issues:** D-A1 (SQL → deviation, ADR-006), D-A2 (categories → parity, folded into #65/#68), G-B5 (parts/emotes → #58 scope amendment).
- **Still needs owner input:** existing #122/#123/#125/#126.
- **PR contract:** every PR for these issues must repeat intent, expectation, acceptance criteria, non-code context, scope/non-goals, affected areas/ownership, dependencies, and verification evidence — `Not applicable`/`Unknown—blocked` where honest. A link to the issue alone does not satisfy review.
- **Milestone placement:** G-B1 → M3; G-B2, G-B3 → M8; G-B4 → new milestone `CustomNPCs parity M12: Player-facing surfaces`; G-D1 → M11. MG-C is an ordering overlay on existing issues.

## Residual risks

- G-B4 is large; expect to split per screen during implementation rather than up-front.
- Abilities (G-B1) and the rule engine overlap — implementers must reuse `domain/rule` conditions or justify a second trigger engine in the PR.
- "Wiring" evidence for P-issues in MG-C is only honest if tests exercise the entity/packet boundary, not just domain round-trips — the register already requires this; do not accept domain-only closes.
