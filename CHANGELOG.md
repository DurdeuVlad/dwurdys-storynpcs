# Changelog

All notable changes to Dwurdy's StoryNPCs are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions are pre-1.0 [Semantic Versioning](https://semver.org/).

## [0.11.0-beta.1] — 2026-10-06

**First public beta.** The complete CustomNPCs-parity program (milestones
M0–M12, issues P0-1 → P11-3) shipped in this release — ~220 commits and
100+ pull requests since v0.10.0.

> **Honest status:** the full feature set is implemented and heavily
> tested headlessly. Parity with the CustomNPCs target runtime is **not
> certified** — that evidence requires live servers; see Known limitations.

### Added

- **Core platform** — 15 typed canonical operations in
  `StoryNpcsApplicationService`; managed actor lifecycle with per-world
  scope identity; capability-based adapter authorization; versioned YAML
  definition families; durable persistence via atomic `.tmp`→rename writes
  with recovery; plain-language YAML diagnostics surfaced in-game.
- **NPC systems** — display variants, MPM cosmetic parts, emote
  animations; stats, melee/ranged combat, ADR-007 resistance/immunity
  contract; authored defeat modes (HIDE statue, FLEE, respawn); AI,
  movement, and targeting vocabulary; inventory, equipment, authored drop
  tables, marks; archetypes, simulation tiers, and LOD; bounded path
  scheduling and squad coordination.
- **Dialogue & quests** — directed-graph dialogue runtime and full editor
  workflow (node/edge editing, delete controls, per-node speaker + sound,
  START_QUEST actions); exactly-once, stale-safe choices; six quest repeat
  modes with cooldowns; objectives, dependencies, rewards; shared-party
  teams; quest mail with overflow delivery.
- **Factions, roles & economy** — inter-faction relationship matrix;
  healer/bard roles; transporter role with locations and unlock tracking;
  all 11 job handlers; companion lifecycle (wages, stages, talents,
  inventory); transactional trader with fail-closed purchase execution;
  banker with the target's six-tab ceiling and durable restock.
- **Creator tools** — persistent templates with capture and anchored
  spawners; movement and utility tools; scriptable world tools; recipes
  and carpentry; linked NPCs, scenes, transforms, timers, and natural
  spawning; custom GUI layouts, model presets, session overlays; NBT book
  tool, chair mount, and fake-living support entities; schematic reader,
  store, and bounded build pipeline with all 27 bundled schematics.
- **Scripting & API** — typed public extension API with server-owned
  capability sessions; bounded Rhino 1.9.1 script host (class shutter,
  instruction/memory budgets, quarantine); command and suggestion parity
  across all 70 classified target leaves; remote, player-data, and config
  administration.
- **Authoring UI** — unified authoring hub; trade/bank admin GUIs;
  per-NPC behavior-rules editor; player-facing screen suite (quest log,
  factions, mail, transport); canonical AI patch-plan apply path with
  snapshot rollback; creator docs, examples, and migration guidance.
- **Migration** — CustomNPCs import contract: plan / dry-run / apply with
  FAIL·SKIP·RENAME·REPLACE conflict policies, per-document quarantine,
  and full rollback. Unsupported formats fail closed rather than guessing.
- **Beta surface** — `/storynpcs beta report [note]` writes a
  self-contained diagnostic bundle to `world/storynpcs/beta/reports/`;
  `/storynpcs beta feedback <text>` appends to `beta/feedback.log`.
- **Player commands** — `quickstart` demo, `me`, `team`, `transport`,
  `mail`, `follower`, plus full `npc`/`dialogue`/`quest`/`faction`/
  `template`/`spawner`/`worldtool` authoring grammars.

### Changed

- `/noppes`-style grammar replaced by the typed `/storynpcs` grammar;
  unified authoring hub instead of 149 target GUI classes; directed-graph
  dialogue instead of fixed option arrays; typed-hook scripting instead of
  arbitrary script source; modernized network protocol. All recorded as
  intentional deviations in the compatibility map.
- Simulation tiers are now live-configurable via `RuntimeTunables`.

### Fixed (notable)

- Panel-action replay could double-charge carpentry/hire commits — the
  admission result is now consumed; a late `closePanel` can no longer
  invalidate a newer screen's session token.
- Dialogue-condition quest-state reads made non-mutating.
- Durable-store recovery hardened after adversarial review; exactly-once
  semantics for faction/transport/mail request ids.
- YAML boundary strictness: unknown fields fail closed; alias-resolution
  drift corrected.

### Verification & evidence

- 146 suites / 1,440 JUnit tests / 0 failures; 41/41 parity fixtures
  observed; 2,836/2,836 compatibility-report rows in terminal states;
  headless + live-GameTest benchmark artifacts; fingerprint-bound fixture
  reports; truth-gate and feature-status contradiction checking.

### Known limitations

- **Target-runtime parity: uncertified.** No CustomNPCs runtime probe
  exists; behavioral rows are `UNVERIFIED_TARGET_RUNTIME` and the release
  gate stays `BLOCKED` by design. Tracked in #192/#193/#194.
- Live-server MSPT and rendered-screen behavior are unverified; GUI logic
  is tested headlessly via ScreenModel contracts.
- CustomNPCs world/NBT import is `UNSUPPORTED_NEEDS_EVIDENCE` until a real
  export sample exists (#194).

## [0.10.0] — 2026-09-18

Authoring-tools release: five authoring wands and a creative tab;
`NpcEditorScreen`; `/storynpcs npc create` scaffolding; in-game YAML
validation diagnostics; `/storynpcs quickstart` guided demo; dialogue
speaker/sound/consequence editor fields; live-server test suite.

## Earlier

v0.1.0 – v0.9.0 were internal platform bring-up (entities, YAML loading,
dialogue engine, persistence groundwork). See git history for detail.
