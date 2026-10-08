# GUI audit — beta.1 rendered evidence (real client + server)

Evidence captured 2026-10-07 with a real NeoForge 1.21.1 client (mc-pilot `beta-01`)
on a dedicated StoryNPCs dev server (`0.11.0-beta.1`, NeoForge 21.1.248, MC 1.21.1,
`localhost:25566`, offline-mode test world). 38 annotated shots in `annotated/`,
raw captures in `raw/` (incl. `00-calibrate.png`, the GUI-scale probe frame),
journey manifest in `manifest.json`.

Method: every screen opened through real game paths (commands, item use, entity
right-click), verified by `gui info` (screen class + title) before capture, then
audited under the flux-frontend rubric (behavioral correctness + visual design).
Every claim below names inspected evidence.

## Verified working (rendered, real client)

| Surface | Screen class | Path proven |
|---|---|---|
| Player dialogue | DialogueScreen | right-click NPC → greeting; digit keys navigate; quest-hint tags render; terminal node shows Close (Esc) |
| Quest log | PlayerQuestLogScreen | `/storynpcs panel quests`; live data — accepted bounty shows IN_PROGRESS |
| Faction standings | PlayerFactionPanelScreen | `/storynpcs panel factions`; 5 factions with tiers |
| Mailbox + compose | PlayerMailScreen | `/storynpcs panel mail`; compose→send→list roundtrip works |
| Transport | PlayerTransportScreen | `/storynpcs panel transport` (empty state) |
| Companions | PlayerCompanionScreen | `/storynpcs panel companions` (empty state) |
| Hire | PlayerFollowerHireScreen | `/storynpcs panel hire` (empty state) |
| Achievements | PlayerAchievementsScreen | `/storynpcs panel achievements` (empty state) |
| Carpentry bench | PlayerCarpentryScreen | `/storynpcs panel carpentry` (empty state) |
| Authoring hub | AuthoringHubScreen | `/storynpcs hub`; 17 routed panels over 2 pages, search+pagination |
| NPC editor | NpcEditorScreen | wand `useOn` block → spawn+edit; identity/stats/movement/stance all render |
| Display editor | NpcDisplayScreen | editor sub-screen; 3-column appearance/model/identity/parts |
| Rules editor | NpcRulesScreen | full add-rule flow executed end-to-end (trigger→condition→action→save) |
| Trade/bank admin | TraderBankerAdminScreen | listing authored and persisted via canonical save |
| Player trade | NpcTradeScreen | trader-role NPC right-click → authored listing rendered |
| Player bank | NpcBankScreen | banker-role NPC right-click → vault tabs/deposit/unlock |
| Dialogue graph editor | DialogueEditorScreen | `/storynpcs dialogue edit`; node canvas, edges, inspector, zoom/search/validate toolbar |
| Quest editor | QuestEditorScreen | `/storynpcs quest gui`; list + detail (objectives/rewards) |
| Faction editor | FactionEditorScreen | `/storynpcs faction gui`; list + detail (thresholds) |
| NBT book | NbtBookScreen | nbt_book right-click; rows render, allowlist highlighted |
| Authored custom GUI | CustomGuiScreen | hand-authored `guilayouts/*.yaml` → `/storynpcs layout open`; labels/input/buttons live |

This satisfies the "rendered-screen evidence" gap tracked in #193 for the StoryNPCs
side of the surface. It does **not** certify CustomNPCs target parity — no CNPC
runtime was probed.

## Defects found (functional)

### F1 — Tool items can never interact with an NPC entity (blocker for in-game editing)
`StoryNpcEntity.mobInteract` returns `InteractionResult.PASS` only for
`NbtBookItem` (StoryNpcEntity.java:1715). Every other creator tool —
`npc_wand`, `npc_dialogue_wand`, `npc_cloner`, `npc_remover`, `npc_soulstone`,
`npc_mounter`, `npc_path`, `npc_teleporter` — relies on
`Item.interactLivingEntity`, which vanilla only reaches when `mobInteract`
does NOT consume the interact. `mobInteract` consumes every NPC click
(dialogue → SUCCESS; no dialogue → role checks → SUCCESS at :1857).
Observed live: wand right-click on Captain Valerie opened *dialogue*; wand
right-click on a dialogue-less NPC did nothing at all.
Consequence: the documented "wand → right-click NPC → edit" path is dead;
the only editor entry is wand-on-block (spawn new) — existing NPCs have no
in-game GUI edit path, and the dialogue wand can never open the graph editor
on a dialogued NPC (its exact use case).
Fix direction: return PASS when the held item is a creator tool
(or move tool handling into `mobInteract` before dialogue).

### F2 — Mailbox list does not refresh after compose
After Send, the compose view returns to the list still showing the stale
snapshot ("0 message(s)") — reopening the panel is required to see the mail.
Fix: re-issue the panel payload (or re-render from server data) on view return.

### F3 — Mail Read requires row selection that has no affordance
Clicking the mail row doesn't visibly select it; Read then no-ops silently.
Fix: row click selects (highlight) or opens the message directly.

## Design findings (visual, flux-design rubric)

| # | Principle | Element | Evidence | Fix |
|---|---|---|---|---|
| D1 | Grouping/essential chrome | All `Player*PanelScreen` + `NpcTradeScreen` | annotated 05–12, 23 | Content floats on dimmed world with zero panel frame — no boundary, no gestalt container; looks unfinished next to editors. Give panels a bounded surface (dark panel + border or vanilla container texture) sized to content. |
| D2 | Hierarchy/readability | NpcRulesScreen footer | raw/16-npc-rules.png | Help text is dark-green-on-dimmed-world, nearly unreadable. Raise contrast (lighter color or background strip). |
| D3 | Lighting | DialogueScreen NPC preview | raw/02,03 | NPC model renders as an unlit black silhouette against the bright panel text. Light the entity render (or drop the preview). |
| D4 | Density/space | NpcEditorScreen | raw/14 | Dense but well-grouped; two-column sections work. Minor: button row crowds bottom on 528-wide logical canvas. |
| D5 | Feedback | Mail/Read + panel returns | 28–30 | State transitions lack feedback (F2/F3) — same class of issue: UI should echo what changed. |

## Positive notes
- Empty states are honest and instructional ("complete a quest or earn standing").
- Editors are consistently framed: title bar, section labels, footer actions.
- Keyboard option selection in dialogue (1-9) works and shows quest hints inline.
- Canonical persistence verified live: saves report "saved to disk and updated in world".

## Test artifacts added during this run
- `run/world/storynpcs/definitions/guilayouts/storynpcs@beta_quest_board.yaml` —
  a demo authored layout (world-local, not shipped).
- Spawned test NPCs npc_88239/npc_86278/npc_74352/npc_46515 in the dev world.

---

## Re-verification sweep — 2026-10-08 (post client/ui migration)

All 21 client screens re-captured on `beta-01` (1.21.1 NeoForge, dev server
`localhost:25566`, ~534×300 logical canvas) after the UI-M1..M3 migration
(#198–#209). Annotated evidence: `2026-10-08/annotated/` (29 shots), raws +
manifest in `2026-10-08/`. Every screen was verified by `gui info` before
capture; interactions are real clicks/keys/entity right-clicks.

A small-window pass (`annotated-small/`, 7 shots at 960×560 ≈ guiScale 2,
472×261 logical) re-verified representative surfaces — dialogue editor,
dialogue, quest editor, mail, NPC editor, bank, faction editor — all chrome,
columns, and footers reflow correctly at the smaller canvas.

### Findings status

| # | Status | Evidence |
|---|---|---|
| F1 — creator tools could not interact with NPC entities | **Fixed** | Wand right-click on an existing NPC (incl. a banker-only spawn) opens `NpcEditorScreen` — `2026-10-08/annotated/14`, `22`. `mobInteract` now PASSes for any `CreatorToolItem` (StoryNpcEntity.java:1717-1727). |
| F2 — mailbox stale after compose | **Fixed** | Compose→send returns to a refreshed list showing the new mail without reopening — `annotated/06`. |
| F3 — mail row selection had no affordance | **Fixed** | Row click highlights + renders an inline preview — `annotated/04`. |
| D1 — player panels unframed | **Fixed** | All Player* panels + trade/bank render inside bounded UiScreen chrome — `annotated/01–11`, `20–21`. |
| D2 — rules footer contrast | **Fixed** | Help text sits on a dedicated footer band, readable — `annotated/16`. |
| D3 — dialogue portrait unlit | **Fixed** | NPC portrait renders lit across node transitions — `annotated/18–19`. |
| D4 — npc editor dense/crowded | **Fixed** | Two proportional columns inside chrome, adaptive footer — `annotated/14`. |
| D5 — transitions lacked feedback | **Fixed** | Footers echo save/refresh outcomes (e.g. faction save ack `annotated/27`, listing save/remove echoes captured in `docs/evidence/`) |

### Journey-level re-verification highlights
- Trade screen separates **Trader buys** (left) / **Trader sells** (right) with
  item icons — `annotated/20`.
- Bank screen reached via banker-only NPC right-click in open space; vault
  tabs/contents/deposit/unlock render — `annotated/21`.
- Quest/faction editors on `UiScreen` with filter, drafts, inline validation,
  save acknowledgements — `annotated/24–27` (+ `docs/evidence/208-*`).
- NBT book preserves contrast-safe allowlist highlighting (cyan editable vs
  muted locked) — `annotated/28`.
- Dialogue editor chrome uses UiTheme tokens; graph canvas intentionally
  remains bespoke (documented in `UiScreen` javadoc) — `annotated/29`.

### Environment notes for future sweeps
- Entity `mobInteract` needs ~≤3-block proximity *and* a clear line-of-sight;
  the fenced test pen at (-37,75,109) blocks interacts — interact packets sent
  while the player is crouch-locked under a low ceiling carry the sneak flag
  and are silently skipped by the role branch. Spawn test NPCs on open ground.
- Never replace the client mod jar while the client process is running —
  lazy class loading then fails (`NoClassDefFoundError`). Relaunch cleanly.
- `fml.toml` `earlyWindowControl = false` avoids the flaky GL handoff that
  intermittently hung client boot.
