# UX & Feature-Parity Report: StoryNPCs vs. CustomNPCs
### Focus: first-time use for a non-technical server admin

Prepared 2026-09-20. Sources: direct read of `dwurdys-storynpcs` (commit `9fb1eee`) and the sibling research repo `dwurdys-storynpcs-research` (Phase 1 archaeology of CustomNPCs, 125 research docs + Milestone 7 GUI audit).

Truth status: `DRAFT / UNVERIFIED` — this report is UX analysis and issue discovery, not proof of delivered CustomNPCs parity. The current acceptance source is [CUSTOMNPCS_PARITY_TRACEABILITY.md](CUSTOMNPCS_PARITY_TRACEABILITY.md) plus the issue register.

---

## 1. Executive summary

The report observes partial engine foundations (hot-reload, in-game validation, tab-completion, and clickable chat actions), but those observations are not a target-parity certification. The **authoring surface** remains incomplete: three of the mod's intended differentiators — dialogue conditions/actions, quests, and factions — were identified as YAML-heavy. A non-technical admin who installs the mod today, doesn't read logs, and doesn't dig through the creative tab may see **nothing happen** on first run, and the moment they want anything beyond "spawn an NPC and tweak its stats," they may hit a wall that requires a text editor.

This is not a "add more features" problem. CustomNPCs itself is proof that more GUI surface doesn't guarantee good UX — the research repo's own audit found **15 of 18 CustomNPCs screens have visual defects** (overflowing buttons, colliding widgets, clipped localized text) and only 4 of 149 GUI classes have any search/filter. The goal here is not to out-feature CustomNPCs, it's to make the *existing* engine self-explanatory without requiring the admin to read anything.

Two failure modes to fix, in priority order:
1. **Nothing announces itself.** Starter content, wand tools, and the creative tab are all silently present — discoverable only by luck or documentation the target user won't read.
2. **Three core systems are YAML-only.** Dialogue conditions/actions, quest authoring, and faction authoring have working engines and zero in-game authoring surface.

---

## 2. Current state (verified against code)

| System | Engine maturity | In-game authoring (no file editing)? |
|---|---|---|
| NPC core (spawn/stats/display/AI) | Polished — full CRUD via command + `NpcEditorScreen`, hot-reload, tab-complete, clickable chat actions | **Yes** |
| Dialogue graph (branching/cycles) | Functional — real graph model, visual editor for node/edge topology | Partial — can't edit node text or delete nodes/edges from the UI (`DialogueEditorScreen` has no wired text box or delete button; `removeSelectedNode()` exists on the model but nothing calls it) |
| Dialogue conditions/actions (`START_QUEST` etc.) | Functional engine | **No** — YAML/API only, zero UI |
| Quests | Functional engine (start/complete/progress) | **No creation** — command tree only has `list/info/start/complete`, no `quest create` |
| Factions | Functional engine (reputation, thresholds) | **No creation** — only `set`/`adjust` on an *existing* faction |
| NPC behavior rules (threat/stance/faction/message conditions+actions) | Functional engine, used by sample content | **No** — no command or GUI surface at all |
| Follower system (formations/recall) | Functional, player-facing | Yes |
| Authoring wands (wand/clone/path/mount/dialogue-wand) | Just shipped, mirrors CustomNPCs' toolset | Yes once found — but nothing in-game explains them |
| Trading | Domain model + `executeTrade()` service exist | **Dead end** — not wired to `NpcDefinition`, no command, no UI |
| Banking | Domain model exists | **Dead end** — same as trading |
| First-run seeding | Works silently, logs a hint server-console-side only | Content exists, admin isn't told |
| Diagnostics | In-game, file:line:col + error codes, bounded, broadcasts to ops | Developer-toned, not plain-language |
| Scripting (claimed 4th authoring surface in Business.md) | **Not implemented** — no scripting package exists | N/A — pitch is ahead of code |

Full file:line detail is in the two source investigations; ask if you want the raw citations.

## 3. Why this reads as "read a book to start"

- No in-game welcome/quickstart message — the one first-run signal is a server **log line**, not a chat message to the op.
- Five new authoring wands exist with no tooltip, lore, or announcement — discoverable only by opening the creative tab and guessing from item names.
- The moment an admin wants a quest, a faction, a dialogue condition, or an NPC behavior rule, the only path is opening a YAML file and matching an undocumented schema — for a persona explicitly defined (in the research repo's own `Business.md`) as wanting *"beautiful, intuitive in-game GUIs... without JSON syntax errors."*
- CustomNPCs itself is not a positive model to copy wholesale for UI quality — it has widespread widget collisions, overflow, and near-zero search/filter across its 149 screens. Parity should mean *matching its authoring coverage* while avoiding its *specific, catalogued* UI defects (Milestone 7 audit, issues #44–#49 in the research repo).

---

## 4. Milestone plan

Ordered by impact on first-run complexity. Each is independently shippable and scoped for another AI session to execute without further discovery — file paths and current classes are cited so the next agent can start immediately.

### M1 — First-run announcement & discoverability
**Goal:** an admin who installs the mod and does nothing else still finds out what exists.
- On world join, broadcast an op-only chat message (not just a server log) when starter content is seeded, pointing at `/storynpcs help` and naming the seeded NPC (`WorldLifecycleHandler.seedStarterDefinitions`).
- Add item tooltips/lore text to all five wand items (`NpcWandItem`, `NpcClonerItem`, `NpcPathItem`, `NpcMounterItem`, `NpcDialogueWandItem`) explaining their click behavior in one line each.
- Add a `/storynpcs quickstart` command that spawns a demo NPC, gives the admin one of each wand, and prints a 3-step "try this" message.
**Acceptance:** fresh world + op join → chat message naming the starter NPC and `/storynpcs help`; `/give` or creative-tab wand items show usable tooltips; `/storynpcs quickstart` works standalone.

### M2 — Finish the dialogue editor
**Goal:** the visual editor covers everything the YAML format supports, so dialogue authoring never requires a text editor.
- Wire a text-edit widget in `DialogueEditorScreen` calling the already-existing `DialogueEditorScreenModel.updateSelectedNodeText`.
- Add a delete-node and delete-edge control calling the already-existing `removeSelectedNode()`.
- Add speaker-name and sound-effect fields to the node inspector (`renderInspector`), matching the YAML fields that already exist.
- Add a minimal conditions/actions editor to the inspector — start with `START_QUEST` since it's the one used by the bundled sample content.
**Acceptance:** a dialogue with branching options, a condition-gated option, and a sound cue can be built entirely from the GUI with the starter graph as the test case, with no YAML edits.

### M3 — In-game quest & faction authoring
**Goal:** `quest create` and `faction create` exist as commands (or a wizard), matching the level of coverage NPCs already have.
- Add `/storynpcs quest create <id> <title>` + `set` subtree for description/category/repeatType, and command-driven objective/reward add/remove.
- Add `/storynpcs faction create <id> <name>` + `set` subtree for thresholds/defaultPoints.
- Reuse the existing clickable-chat-action pattern (`clickable()` helper) rather than inventing new UI.
**Acceptance:** a new quest with one objective and one reward, and a new faction with custom thresholds, can be created and verified via `info` commands with zero YAML edits.

### M4 — Behavior rule authoring
**Goal:** `BehaviorRule` (threat/stance/faction/message/shout/yield) gets a command surface — currently zero, despite a working engine.
- Add `/storynpcs npc rule add/remove/list <npc_id>` covering the existing condition types (`ActorIsPlayerCondition`, `FactionStandingCondition`, `HealthPercentCondition`, `StrikeCountCondition`) and action types (`AddThreatAction`, `AdjustFactionAction`, `ChangeStanceAction`, `SendMessageAction`, `ShoutAlertAction`, `YieldCombatAction`).
**Acceptance:** a rule ("if health < 20%, yield combat and send a message") can be attached to an NPC via command and observed to fire, with no YAML edits.

### M5 — GUI defect-avoidance pass
**Goal:** don't repeat CustomNPCs' catalogued mistakes as the GUI surface grows through M2–M4.
- Add a fixed-width/no-overflow check to any new screen (CustomNPCs' `GuiScript` overflowed its container by 17px — trivial to avoid by testing at the declared width).
- Ensure buttons are wide enough for the longest supported locale string, or truncate with a tooltip rather than clip.
- Add search/filter to any list-based UI expected to grow (dialogue node list from M2, quest/faction pickers from M3) — CustomNPCs shipped this in only 4 of 149 screens and it shows.
**Acceptance:** a short manual pass against the specific defect categories in `dwurdys-storynpcs-research/docs/Milestone-GUI-Visual-and-Memory-Audit.md` (issues #44–#49), confirming none apply to the new screens.

### M6 — Plain-language diagnostics
**Goal:** validation errors are readable by someone who doesn't know what a YAML column is.
- Extend `ValidationResult`/`DiagnosticError` formatting with a human-readable message variant alongside the existing `file:line:col (CODE)` form — e.g. append a one-line plain-English hint per error code (a lookup table keyed by the existing `CODE` enum, not a rewrite of the validator).
**Acceptance:** a deliberately broken sample YAML (missing required field, bad reference ID) produces a chat message a non-technical reader can act on without opening the file.

### M7 — Decide trading/banking's fate
**Goal:** stop shipping dead code as a pitched feature.
- Either (a) wire `TraderRole`/`TradeListing`/`BankerRole`/`BankVault` to `NpcDefinition` with a minimal command + one GUI screen each, or (b) remove them from `Business.md`'s feature claims until scheduled.
**Acceptance:** `Business.md` claims match what a fresh install can actually do — this is a decision + doc/scope fix, not necessarily new engine work.

---

## 5. Note on the research repo

`dwurdys-storynpcs-research` is a large (125-doc) technical archaeology of CustomNPCs' actual behavior — genuinely useful as a spec reference for M2–M4 (e.g. `research/120-customnpcs-feature-parity-master-specification.md` has a 15-row operation parity matrix, and `docs/Milestone-GUI-Visual-and-Memory-Audit.md` has the specific defect list M5 should check against). Treat its Python "core" prototype and self-reported performance numbers as directional, not verified — but its GUI/UX findings are solid and directly reusable. It has **no dedicated onboarding/first-run research** — that gap is exactly M1 above, and isn't answered by re-reading the research repo further.

---

## 6. Historical audit report (issue #27) — unverified

The following section preserves a prior report's claims for investigation. They were not re-run as part of the current parity baseline, the working tree may differ from the cited commit, and no claim below can close a parity issue until it has a named fixture and evidence state. Treat the table and results as `UNKNOWN`/`UNVERIFIED_STORYNPCS_RUNTIME`, not as delivered parity.

### Reported parity matrix (unverified)

| System | YAML path | Command authoring | GUI authoring |
|---|---|---|---|
| NPC | `world/storynpcs/definitions/npcs/<file>.yaml` | `npc create` + `npc set <name|title|skin|health|damage|speed|range|movement|stance|dialogue|faction>` + `npc delete` | `NpcEditorScreen` — `give @s storynpcs:npc_wand`, Shift+Right-click NPC |
| Dialogue | `world/storynpcs/definitions/dialogues/<file>.yaml` | `dialogue create` / `edit` / `delete` | `DialogueEditorScreen` — node text, node/edge delete, speaker/sound, `START_QUEST` actions |
| Quest | `world/storynpcs/definitions/quests/<file>.yaml` | `quest create` / `set` / `objective add|remove` / `reward add|remove` | `QuestEditorScreen` — `quest gui [quest_id]` |
| Faction | `world/storynpcs/definitions/factions/<file>.yaml` | `faction create` / `configure` | `FactionEditorScreen` — `faction gui [faction_id]` |
| Behavior rule | `rules:` block inside NPC YAML | `npc rule add|remove|list` | `NpcRulesScreen` — "Rules" button in `NpcEditorScreen` |
| Trading (added by #23) | `trader:` block inside NPC YAML | `npc trade enable|disable|list|add|remove` | `NpcTradeScreen` — right-click trader with no dialogue |
| Banking (added by #23) | `banker:` block inside NPC YAML | `npc bank enable|disable` | `NpcBankScreen` — right-click banker with no dialogue |

### Reported live results (unverified)

- All five GUI surfaces open and render within bounds at 427×240: `DialogueEditorScreen`, `QuestEditorScreen`, `FactionEditorScreen`, `NpcEditorScreen`, `NpcRulesScreen`.
- Command-path writes confirmed by file inspection for NPC, dialogue, quest, faction fixtures; GUI-path write confirmed by `Save Changes` → server `saved to disk and updated in world` → YAML content change.
- GUI-authored behavior rule fired identically to a command-authored rule (`yield_combat` on `health_percent`), and `npc rule list` sees GUI-added rules — cross-surface persistence verified.
- `/storynpcs reload` round-trips all definitions clean (`Validation PASSED (0 diagnostics)`).
- List filters verified live on quest, faction, and rules lists (case-insensitive substring, clear restores rows, filter+scroll stays inside the scissor region).
- Plain-language diagnostics verified live: `SCHEMA_MISSING_ID` and `QUEST_OBJ_COUNT_INVALID` both emit actionable hint lines.

### Reported help-text discoverability changes (unverified)

Top-level `/storynpcs help` previously omitted `rule|trade|bank` from the `npc` summary and all authoring verbs from `quest`/`faction` summaries; subsystem help never mentioned YAML paths or the wand GUI. Now:

- `npc` summary lists `create|list|info|set|spawn|despawn|delete|rule|trade|bank`; its footer adds `GUI: give @s storynpcs:npc_wand then Shift+Right-click an NPC (also hosts the Rules editor)` and `YAML: world/storynpcs/definitions/npcs/<file>.yaml`.
- `quest`/`faction` summaries list `create|set|objective|reward|gui` / `create|configure|gui`; each subsystem help ends with its YAML path line.
- `dialogue` help ends with `YAML: world/storynpcs/definitions/dialogues/<file>.yaml`.
- Top-level help ends with an explicit three-paths line: `Every system has three paths: YAML in world/storynpcs/definitions/, the commands above, and GUIs ...`.

### Reported defect and fix (unverified)

- **`EditBox` truncation (data corruption)**: `EditBox.setValue()` clamps to the widget's `maxLength` *at call time*; several editors called `setValue()` before `setMaxLength()`, silently truncating values longer than the 32-char default. Concretely, the NPC editor truncated `storynpcs:textures/entity/default.png` (40 chars) to `...entity/defaul` (32), corrupting the skin path on every GUI save — the command path had no such limit. Fixed by reordering to `setMaxLength` → `setValue` in `NpcEditorScreen` (name/title/skin/faction), `QuestEditorScreen` (id/title/category/description), and `FactionEditorScreen` (id/name/threshold). Verified live: GUI save now writes the full 40-char path.

### Residual gaps reported by the historical audit

- **Trading/banking parity is asymmetric** (follow-up issue #44): `npc trade|bank` commands author the role; `NpcTradeScreen`/`NpcBankScreen` are *player-facing* surfaces (buy/deposit/withdraw), not admin listing editors. An admin cannot add/remove trade listings from a GUI today — YAML or commands only.
- **No `quest delete` / `faction delete`** (follow-up issue #45): YAML files can be deleted by hand and `npc delete`/`dialogue delete` exist, but quests/factions have no command-side delete.
