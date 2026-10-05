# CustomNPCs ↔ StoryNPCs feature comparison

Status: active readable layer over the engineering register.
Last compiled: 2026-10-05.
Companion docs: [CUSTOMNPCS_PARITY_ISSUE_REGISTER.md](CUSTOMNPCS_PARITY_ISSUE_REGISTER.md) (canonical acceptance criteria) and [CUSTOMNPCS_PARITY_GAP_INVENTORY.md](CUSTOMNPCS_PARITY_GAP_INVENTORY.md) (evidence states). This document exists because those are engineering-parity artifacts; this is the feature-level view.

## Sources and method

- **CustomNPCs side**: the verified target surface manifest (`docs/parity/target-surface-manifest.json`, generated from `CustomNPCs-Unofficial-NeoForge-1.21.1.20251230.jar`, SHA-256 `6c28d87b…`). Counts used below are exact: 1,230 classes, 149 GUI screens, 155 packets (115 server-bound / 40 client-bound), 70 command leaves, 97 API event classes, 32 containers, 11 blocks, 7 roles, 11 jobs, 3 companion jobs, 18 persistence stores, 49 data entries (19 markov name lists + 28 builder schematics + 2 misc), 972 assets.
- **StoryNPCs side**: `src/main/java` (311 files, 104 test files), `src/main/resources`, and the open GitHub issue list (#51–#126 parity issues, plus #1–#45 historical).
- Static class/field presence is **not** proof of working runtime behavior. Statuses below reflect "what exists in code today"; certification status lives in the gap inventory.

### Status legend

| Mark | Meaning |
|---|---|
| ✅ Implemented | Runtime-wired behavior exists (entity tick, screen, packet, or command path) |
| ✅ superset | Implemented and structurally exceeds the target (e.g. directed-graph dialogue) |
| 🟡 Partial | Some runtime behavior; materially narrower than the target surface |
| 🧩 Declared | Domain types/schema/vocabulary exist; no runtime behavior wired |
| 📋 Planned | Covered by an open issue; no code yet |
| ❌ Missing | No code in `src/main` |
| 🚫 deviation | Intentional deviation, recorded in `Decision.md` ADRs |

---

## Part 1 — Complete CustomNPCs feature list

### A. NPC entity & presentation

| # | Feature | Evidence in target |
|---|---|---|
| A1 | NPC creation wand + main editor tabs (display/stats/AI/inventory/advanced) | `ItemNpcWand`, `GuiNPCInterface`, `GuiNpcMenu`, `GuiNpcDisplay/Stats/AI/Inv/Advanced` |
| A2 | Display: name, title, skin (texture + player-skin + URL), model picker, XYZ scale, tint, cloak, glow, boss bar, hitbox, visibility, body animation | `DataDisplay`, `GuiCreationScale`, `SubGuiNpcName`, `GuiTextureSelection` |
| A3 | Entity model variants: classic player, Alex, 64x32, golem, flying, dragon, slime, crystal, pony; chair-mount entity; fake-living entity; projectile entity | `EntityNpc*` (9 classes), `EntityChairMount`, `EntityFakeLiving`, `EntityProjectile` |
| A4 | Cosmetic parts (MPM-style): beard/ears/horns/snout/tail/wings/fin/skirt/eyes with colors and behavior | `noppes.npcs.client.parts` (12), `MpmPart*` (8), `EnumParts`, `PartBehaviorType` |
| A5 | Emote animations: aim, bow, crawl, dance, hug, no, point, wave, yes, blank | `Ani*` (10), `AnimationContainer`, `EntityAIAnimation` |
| A6 | Availability gates: NPC visible/interactable by faction standing, dialog read/unread, quest state, scoreboard value, day-time | `EnumAvailability*` (5), `SubGuiNpcAvailability*` (4) |
| A7 | Editor marks: colored marks on entities with type/color/availability | `GuiNPCMarks`, `/noppes mark/*` commands, marks domain |
| A8 | Advanced: night setup, sounds menu, dimension rules, remote editor, linked NPCs | `GuiNPCNightSetup`, `GuiNPCSoundsMenu`, `GuiNpcDimension`, `GuiNpcRemoteEditor`, `GuiNPCManageLinkedNpc` |

### B. Combat, stats & AI

| # | Feature | Evidence |
|---|---|---|
| B1 | Stats: health, health regen, combat regen, XP, respawn timer/mode, defeat mode (die/hide/flee), drops profile | `DataStats`, `SubGuiNpcRespawn` |
| B2 | Melee: strength, delay, range, knockback multiplier, on-hit potion effect | `DataMelee`, `SubGuiNpcMeleeProperties` |
| B3 | Ranged: projectile type, damage, speed, size, area, trail, delay, range, fire rate, burst count, accuracy, physics/gravity, effects, sounds | `DataRanged`, `SubGuiNpcRangeProperties`, `SubGuiNpcProjectiles` |
| B4 | Resistances: 4 channels (knockback, arrow, melee, explosion); 6 immunities (potion, fall, sunlight, fire, drowning, cobweb) | `SubGuiNpcResistanceProperties` |
| B5 | Passive combat abilities: block, pull, push, smash, snare, teleport | `noppes.npcs.ability` (11), `DataAbilities`, `EntityAIAbilities` |
| B6 | Movement AI: stand/wander/moving-path, wander radius, door open/bust, water nav, find shade, move indoors, leap, return home, look, watch-closest | `EntityAI*` (26), `EntityAIWorldLines`, `NpcGroundPathNavigator` |
| B7 | Targeting & tactics: attack-on-sight, avoid, faction/target selectors, defend owner/allies, retaliation, pounce, sprint-to-target, panic, transform | `ai.target`, `NPCAttackSelector`, `NPCInteractSelector`, `CombatHandler` |
| B8 | Inventory: 4 armor + 3 weapon slots (L/R hand + projectile), 9 visible drop slots / 21 API-addressable drop indices, drop chances, XP, loot mode | `ContainerNPCInv`, `InventoryNPC`, `DataInventory` |

### C. Roles (7)

| Role | Surface |
|---|---|
| Trader | 2-input/1-output listings, setup + trade GUIs, `ContainerNPCTrader(Setup)` |
| Banker | Up to 6 vault tabs, unlock + upgrade costs, small/large containers, `GuiNpcBankSetup`, `GuiNPCBankChest` |
| Transporter | Locations, categories, unlock, cross-dimension, `GuiNpcTransporter`, `GuiNPCManageTransporters`, `GuiTransportSelection` |
| Follower (mercenary) | Hire/setup GUIs, days + rate, follow/stay/guard, `ContainerNPCFollower*` |
| Companion | Stages, talents, stats, inventory + companion jobs: farmer, guard, trader — `GuiNpcCompanion*` (4), `ContainerNPCCompanion` |
| Postman / mailman | Deliver player mail, `RolePostman`, `GuiMailmanWrite`, mailbox blocks |
| Dialog | NPC whose interact opens a dialog, `RoleDialog`, `EntityDialogNpc` |

### D. Jobs (11)

Bard (AoE buffs), Builder (builds bundled `.schematic` files), ChunkLoader, Conversation (multi-NPC), Farmer, Follower, Guard, Healer, ItemGiver, Puppet (pose/anim control), Spawner — `noppes.npcs.roles.Job*`.

### E. Dialogue

- Dialog definitions in folders/categories; title, text, options (color, availability, sound, command effect); `GuiDialogEdit`, `GuiNPCLinesEdit/Menu`, `SubGuiNpcDialogOption(s)`, `GuiNPCManageDialogs`
- Player UI: `GuiDialogInteract`, `GuiDialogSelection`
- Commands: `/noppes dialog read|unread|show|reload`
- Quest-type: dialog objective

### F. Quests

- 5 objective types: item, kill, location, dialog, manual — `GuiNpcQuestType*`, `ContainerNpcQuestTypeItem`, `ContainerNpcQuestReward`
- 6 repeat modes; rewards (items/XP/faction/commands); categories; `GuiQuestEdit`, `GuiQuestCompletion`, `GuiNPCManageQuest`, `GuiNpcQuestReward`
- Player UI: quest log (`GuiQuestLog`, `GuiQuestSelection`, `InventoryTabQuests`)
- Commands: `/noppes quest start|stop|finish|objective|remove|reload`
- API events: `QuestEvent$QuestStart/Completed/TurnedIn`

### G. Factions

- Editor (`GuiFaction`, `GuiNPCFactionSetup`, `GuiNPCManageFactions`), default points, hostile/friendly thresholds, inter-faction relation matrix, faction colors
- Player UI: `InventoryTabFactions`, `GuiNPCFactionSelection`
- Commands: `/noppes faction … add|set|reset|drop`
- Events: `PlayerEvent$FactionUpdateEvent`, `HandlerEvent$FactionsLoadedEvent`

### H. Economy & containers

32 containers incl. trader setup, bank small/large/unlock/upgrade, companion/follower inventories, carpentry bench, mail, recipe manager, merchant-add, NPC inventory, item-giver, quest reward.

### I. Creator tools (items, 12)

`ItemNpcWand`, `ItemNpcCloner` (server clone library with tabs + grid spawn), `ItemNpcMovingPath`, `ItemMounter`, `ItemNpcScripter`, `ItemTeleporter`, `ItemNbtBook`, `ItemSoulstoneEmpty/Filled` (capture mob → `GuiNpcMobSpawner*`), `ItemScripted`, `ItemScriptedDoor`, `ItemNpcBlock`.

### J. World blocks (11)

`BlockBuilder`, `BlockCopy`, `BlockBorder`, `BlockWaypoint`, `BlockNpcRedstone`, `BlockScripted`, `BlockScriptedDoor`, `BlockNpcDoorInterface`, `BlockInterface`, `BlockMailbox`, `BlockCarpentryBench` — each with tile entities (`Tile*` ×15).

### K. World/story orchestration

- **Scenes**: record/play NPC scenes (`DataScenes`, `GuiNPCScenes`, `/noppes scene pause|reset|start|time`)
- **Linked NPCs**: `GuiNPCManageLinkedNpc`, `SPacketLinked*`, `linked_npcs` store
- **Transformations**: `DataTransform`, `EntityAITransform`
- **Timers**: `DataTimers`, `NpcEvent$TimerEvent`
- **Natural spawning**: `GuiNpcNaturalSpawns`, `SPacketNaturalSpawn*`, `spawns` store
- **Recipes & carpentry**: `GuiNpcManageRecipes`, `GuiNpcCarpentryBench`, `GuiRecipes`, `RecipeCarpentry`, `RecipesDefault`, `HandlerEvent$RecipesLoadedEvent`
- **Schematics**: `Schematic`, `SchematicWrapper`, `SpongeSchem`, 28 bundled `.schematic` files, `/noppes schema build|info|list|stop`
- **Name generation**: 19 markov name lists (`nikedemos.markovnames`, 15 classes)
- **SQL player data**: `DatabaseController`, `noppes.npcs.db` (3)

### L. Scripting

Script host with per-host editors — `GuiScript`, `GuiScriptBlock`, `GuiScriptDoor`, `GuiScriptForge`, `GuiScriptGlobal`, `GuiScriptInterface`, `GuiScriptItem`, `GuiScriptList`, `GuiScriptPlayers`; `EnumScriptType`; `scripts` store; `/noppes script reload|trigger`.

### M. Custom GUI & HUD overlays

Scripted custom GUIs — `GuiCustom`, `GuiCustomComponents`, `GuiCustomScrollingPanel`, 16 component classes (`CustomGuiButton`, `CustomGuiScroll`, `CustomGuiSlider`, `CustomGuiSlot`, `CustomGuiTextArea`, `CustomGuiTextField`, `CustomGuiTexturedRect`, `CustomGuiColoredLine`, `CustomGuiEntityDisplay`, `CustomGuiItemRenderer`, `CustomGuiAssetsSelector`, `CustomGuiButtonList`), `ContainerCustomGui`, `CustomGuiEvent` (6 events); HUD overlays (`noppes.npcs.api.overlay` ×5); `GuiPresetSave`, `GuiCreation*` model/preset screens.

### N. Administration & player data

Global main menu (`GuiNPCGlobalMainMenu`), remote editor (`GuiNpcRemoteEditor` + `SPacketRemote*`), manage player data (`GuiNpcManagePlayerData` + `SPacketPlayerData*`), achievements (`GuiAchievement`), NBT book editor (`GuiNbtBook`), config toggles (`/noppes config …`: chunkloaders, debug, font, freezenpcs, icemelts, leavesdecay, scripting, vineinflateth), `/noppes slay`, `/noppes npc create|delete|home|owner|reset|visible`.

### O. API & network

97 API event classes (Block×15, CustomGui×6, Dialog×3, Forge×3, Handler×2, Item×7, Npc×13, Player×20, Projectile×2, Quest×3, Role×9, World×1); API wrappers (~66 classes incl. GUI wrappers); `api.handler` data handlers; 155 packets; 56 mixins (incl. optional-mod integration hooks).

### P. Persistence

18 stores: banks, client_presets, clones, config, database, dialogs, factions, global_data, linked_npcs, npc_entity_and_modules, player_data, quests, recipes, schematics, scripted_item_and_blocks, scripts, spawns, transport.

---

## Part 2 — StoryNPCs status vs. the target feature list

The table below is generated by `python tools/parity/check_feature_status.py --write` from `tools/parity/feature_status_map.json` and checked in CI (`--check`); edit the map, not the table.

<!-- feature-status:begin -->
| CustomNPCs feature | StoryNPCs today | Status | Owning issue(s) |
|---|---|---|---|
| NPC creation & main editor | NpcWandItem, NpcEditorScreen, /storynpcs npc create\|spawn\|set â€¦ | 🟡 | #58 P3-1 |
| Display (name/title/skin/scale/tint/cloak/glow/bossbar/hitbox) | NpcDisplay, DisplayProjection*, StoryNpcRenderer, StoryNpcHitboxHandler â€” base fields only | 🟡 | #58 P3-1 |
| Entity model variants (9) | NpcVariant (9 target variants) + variant-scaled hitbox + renderer dispatch to baked vanilla geometry + /storynpcs npc set variant; generic-variant animation is static (documented) | ✅ | #58 P3-1 |
| Cosmetic parts (MPM) | NpcCosmeticPart (9 MPM parts, type/color/behavior) + NpcPartLayer clean-room cube geometry + editor part editor + /storynpcs npc set part; PARTS_VARIANT_INCOMPATIBLE diagnostic on non-humanoid variants | ✅ | #58 P3-1 (scope confirmed) |
| Emote animations (10) | NpcEmote (10 target Ani*) + NpcEmoteState server lifecycle + 3 synced data fields + NpcEmoteAnimator pose overrides + /storynpcs npc emote; emotes are runtime-only (never YAML-authored) | ✅ | #58 P3-1 / #82 P8-6 |
| Availability gates | DialogueCondition evaluated by the service (dialogue/faction gating); no dedicated NPC availability surface | 🟡 | #58 P3-1, #88 P10-1 |
| Marks | Bounded NpcMark catalog (type/color/text/available) with first-available-glyph nameplate projection and definition-refresh resync | ✅ | #61 P3-4 |
| Advanced (night/sounds/dimension/remote/linked) | LinkedNpcGraph domain only | 🧩 | #81 P8-5, #86 P9-4 |
| Stats/defeat | Bounded NpcStats + melee/ranged/defeat blocks; DefeatResolution drives die/hide/flee with events; respawn timers; XP reward hook | ✅ | #59 P3-2, #118, #122 |
| Melee | NpcMeleeAttackGoal consumes authored delay/range/knockback/potion-effect; attributes projected server-side | ✅ | #59 P3-2 |
| Ranged/projectiles | NpcRangedAttackGoal + NpcProjectileEntity consume the full ranged block: windup, fire-rate, shotCount, accuracy, gravity, size, area damage, trail, impact sound, on-hit effects | ✅ | #59 P3-2 |
| Resistances/immunities | ADR-007 target-faithful 2.0-resistance damage scale + unclamped read passthrough + StoryNpcsRev marker; 6 immunity toggles round-trip; compat-adapter type 1/4 quirk inherited by #91 | ✅ | #59 P3-2, #122 |
| Abilities (pull/push/smash/snare/teleport/block) | All six types on NpcDefinition.abilities â€” DAMAGED/ATTACK/UPDATE triggers, bounded params, rule conditions; NpcAbilityController applies velocity/snare/teleport/block/smash via entity APIs | ✅ | #147 |
| Movement AI (wander/path/door/water/indoors/shade) | MovementType stand/wander/path + full B6 vocabulary: doorInteract/doorBust, seekShade, shelterIndoors, watchClosest, avoidWater, leap, returnToStart — bounded goals gated per authored flag | ✅ | #60 P3-3 |
| Targeting & tactics (selectors/defend/pounce/panic/transform) | Full B7 vocabulary: attack-on-sight + avoidTargets (isEligibleForAvoidance), faction/target selectors, defendOwner + defend-allies, retaliation, pounce(leapAtTarget), sprintToTarget, panicOnHurt, defeatTransformId transform-on-defeat | ✅ | #60 P3-3 |
| Inventory/equipment/drops | NpcItemStack + NpcInventory (7 slots, 21 drops, loot mode, XP range) with DataComponentPatch component application, authored projectile visual, lossless ops | ✅ | #61 P3-4 |
| Role: Trader | TraderRole, TradeListing, NpcTradeScreen, trade payloads, TradeExecutedEvent; 2nd input slot added | 🟡 | #75 P7-1 |
| Role: Banker | BankerRole, BankVault, NpcBankScreen, bank payloads, BankTransactionEvent; 6-tab ceiling enforced | 🟡 | #76 P7-2 |
| Role: Transporter | TransporterRole, transports/*.yaml family, TransportEvaluator, /storynpcs transport, requestTransport + player transport-picker panel (#150) | 🟡 | #72 P6-3 |
| Role: Follower | FollowerRole, FollowerGroup, FormationCalculator/Type/Offset; entity-wired follow/formation tick | 🟡 | #71 P6-2, #74 P6-5 |
| Role: Companion | CompanionProfile/Stage/Talent/WagePolicy/WageLedger; wage charge via canonical op + entity tick; no inventory/stats/talent GUI | 🟡 | #74 P6-5 |
| Role: Postman/mail | PostmanRole field; MailMessage, QuestMailStore, /storynpcs mail + player mail panel with session-bound send/read actions (#150); no mailbox block or NPC write GUI | 🟡 | #71 P6-2, #79 P8-3 |
| Role: Dialog | dialogueId on NpcDefinition; interact opens graph dialogue (model differs by design) | ✅ superset | #65 P5-1 |
| Jobs (11) | JobType enum (all 11), JobConfig on def, NpcJobRuntime dispatch â€” 9 types have tick handlers, BUILDER + FOLLOWER fail validation | 🟡 | #73 P6-4 |
| Companion jobs (3) | No companion-job classes or runtime | 📋 | #74 P6-5 |
| Dialogue engine | Directed graph (DialogueGraph/Node/Edge), conditions, actions, sessions, validators â€” structurally stronger than target | ✅ superset | #65-67 P5-1/2/3 |
| Dialogue player UI | DialogueScreen + model, choice tokens (DialogueChoiceProtocol) | 🟡 | #66 P5-2 |
| Dialogue editor | DialogueEditorScreen â€” visual node/edge editor (target has no equivalent); field coverage narrow | ✅ superset | #67 P5-3 |
| Dialog categories/folders | Quest.category serialized in YAML + editable in QuestEditorScreen; dialogue graphs lack a category field | 🟡 | #65 P5-1 (ADR-006 parity) |
| Quests: 5 objective types | QuestObjective 5 kinds | ✅ | #68 P5-4 |
| Quests: 6 repeat modes | 3 of 6 (RepeatSchedule) | 🟡 | #68 P5-4 |
| Quest dependencies/team | QuestDependencyValidator wired into the service; TeamProgression declared only (no runtime consumer) | 🟡 | #68 P5-4, #69 P5-5 |
| Quest completion/rewards | Revisioned via service; RewardOverflowPolicy + quest-mail channel | 🟡 | #69 P5-5 |
| Quest log / player UI | PlayerPanelViews quest/faction/mail/transport views -> ClientboundPlayerPanelPayload -> player screens; session-bound commits, server-built JSON views (4 of the #150 acceptance surfaces; follower hire -> #71/#74, companion inv/stats -> #74, achievement view -> #86/#88, carpentry bench -> #80, custom GUI renderer -> #82) | 🟡 | #150 |
| Faction matrix & relation model | Faction, FactionStanding, FactionDeletionPlanner, FactionRelationshipProvider, editor GUI; relation matrix incomplete | 🟡 | #70 P6-1 |
| Clone library (tabs, grid) | NpcTemplate, TemplateLibrary, /storynpcs template; no tabbed clone store or grid spawn | 🟡 | #77 P8-1 |
| Spawner blocks | SpawnerRule domain | 🧩 | #77 P8-1 |
| Moving-path / waypoint tool | NpcPathItem, WaypointPath (domain+ai) | 🟡 | #78 P8-2 |
| Mounter / soulstone / teleporter / remover tools | NpcMounterItem exists; soulstone/teleporter/remover absent | 🟡 | #78 P8-2 |
| Scripter / NBT book tools | NbtBookItem + NbtBookService â€” op-gated allowlist entity edits (CustomName/NoGravity/Invulnerable/Silent/Glowing) via session-bound payload; scripter tool absent | 🟡 | #148 |
| Creator blocks (11) | None â€” zero block registrations | 📋 | #79 P8-3, #80 P8-4 |
| Scripted block/door | ScriptedHookBinding domain (inert) | 🧩 | #79 P8-3, #84 P9-2 |
| Mailbox block / carpentry bench | CarpentryRecipe domain only | 🧩 | #79 P8-3, #80 P8-4 |
| Scenes | SceneDefinition, SceneTimer | 🧩 | #81 P8-5 |
| Linked NPCs / transformations / timers / natural spawn | LinkedNpcGraph, TransformRule, NaturalSpawnRule | 🧩 | #81 P8-5 |
| Recipes/carpentry | CarpentryRecipe | 🧩 | #80 P8-4 |
| Schematics (28 bundled + build commands) | Bounded reader (Sponge v1-v3 + legacy MCEdit), traversal-proof store, budgeted multi-tick build pipeline + schema commands; all 28 bundled .schematic assets shipped at data/storynpcs/schematics/ (ported from the pinned target jar, datapack-overridable) | ✅ | #149 |
| Markov name generation | None â€” engine choice undecided | 📋 | #123 (needs-input) |
| SQL player-data store | Recorded deviation â€” ADR-006 + PersistenceStoreCatalog entry target.persistence_stores.0012 (INTENTIONAL_DEVIATION): file-first rule, no embedded SQL | 🚫 deviation | ADR-006 (intentional deviation) |
| Scripting host | ScriptHook enum, ScriptBudget, ScriptScheduler wired into lifecycle â€” no interpreter/engine | 🟡 | #84 P9-2, #125 engine pin |
| Custom GUI authoring | CustomGuiLayout | 🧩 | #82 P8-6 |
| HUD overlays / model presets | OverlaySession | 🧩 | #82 P8-6 |
| Global main menu / remote editor / player-data mgmt / achievement GUI | RemoteAccessProof, PlayerDataScope, RuntimeTunables, ConfigTransaction wired server-side; no admin GUIs | 🟡 | #86 P9-4, #88 P10-1 |
| Config toggles | RuntimeTunables wired into mod lifecycle (partial set) | 🟡 | #86 P9-4 |
| Command surface (70 leaves) | Rich /storynpcs tree; CommandParityCatalog marks nearly all target leaves UNVERIFIED | 🟡 | #85 P9-3 |
| API events (97) | 22 events in api/event/ | 🟡 | #83 P9-1 |
| Public API | StoryNpcsApi read-only facade | 🟡 | #83 P9-1 |
| Optional-mod integrations (mixins) | Recorded deviation â€” P9-5 closeout enumerates the target's 7-class Pixelmon/Cobblemon surface; StoryNPCs implements no optional-mod integration; absent-check + P9-5 tests enforce it | 🚫 deviation | #87 P9-5 (closed: documented deviation) |
| Persistence (18 stores) | DurableJsonStore, ProgressionRepository, BankRepository, TradeStateRepository, ActorStateRepository, DurableOperationJournal â€” selected stores, not the 18-store crash matrix | ✅ | #56 P2-2, #57 P2-3 |
| Simulation tiers / LOD / squad / path scheduler | sim/ package wired into entity tick (SimulationScheduler); squad + path queue declared; not certified | 🟡 | #62-64, #95, #126 |
| Canonical mutation pipeline | StoryNpcsApplicationService + typed requests/results + AuthorizationPolicy + CapabilityRegistry | ✅ | #51 P1-1, #54 P1-4 |
| CustomNPCs world/data import | migration/ package + /storynpcs import | 🟡 | #91 P11-1 |
| Unified authoring hub | AuthoringHub model, not wired into screens | 🧩 | #88 P10-1 |

**Status counts:** ✅ 16 · ✅ superset 3 · 🟡 29 · 🧩 10 · 📋 3 · 🚫 deviation 2 — 63 rows

<!-- feature-status:end -->

## Part 3 — StoryNPCs-only capabilities (no CustomNPCs counterpart)

| Feature | Evidence | Issue |
|---|---|---|
| Directed-graph dialogue (vs. fixed option slots) | `DialogueGraph`, graph editor | done, extends target |
| YAML-first versioned definitions + diagnostics | `YamlDefinitionLoader/Writer`, `DefinitionSchema`, `CrossReferenceValidator` | #55 P2-1 |
| Behavior-rule engine (trigger/condition/action) | `domain/rule/*`, `NpcRulesScreen`, `/storynpcs npc rule` | implemented |
| Canonical service + typed ops + capability auth | `service/` package | #51, #54 |
| AI-generated content patch plans + schema bundle | `authoring/ai/*`, `/storynpcs author validate` | #89 P10-2 |
| Durable stores + operation journal (crash recovery) | `persistence/` package | #56, #57 |
| Budgeted simulation tiers + squad coordination | `sim/` package | #62–64 |
| Deterministic parity evidence harness | `tools/parity/*`, fixture catalog | #47–50 |
| Creator docs/quickstart/migration guide | `docs/creator/*`, `/storynpcs quickstart` | #90 P10-3 |

## Part 4 — Gaps and ambiguities to resolve

Items where **no open issue clearly owns the target surface**, or ownership is ambiguous. **Update 2026-10-05:** all rows resolved — see [CUSTOMNPCS_GAP_MILESTONE_PLAN.md](CUSTOMNPCS_GAP_MILESTONE_PLAN.md) and GitHub issues created under it.

| # | Target feature | Resolution |
|---|---|---|
| 1 | **Combat abilities** (`AbilityBlock/Pull/Push/Smash/Snare/Teleport`, `DataAbilities`, `EntityAIAbilities`) | #147 (M3) |
| 2 | **Markov name generation** | Still `needs-input` — #123 |
| 3 | **SQL database player-data store** | **Deviation recorded** (D-A1 / ADR-006): file-based YAML/TOML for admin content; managed durable stores for concurrency-sensitive state only. No SQL |
| 4 | **28 bundled `.schematic` files** | #149 — **port all 28** (owner decision 2026-10-05) |
| 5 | **NBT book item/editor** | #148 (write scope restricted per file-first rule) |
| 6 | **EntityFakeLiving, EntityChairMount** | #148 (M8) |
| 7 | **Dialog/quest folders (categories)** | **Parity** (D-A2 / ADR-006): `Quest.category` already serialized in YAML + editable in `QuestEditorScreen` — remaining gap is a `category` field on dialogue graphs under #65 P5-1 |
| 8 | **Player-facing screens** | #150 → milestone `CustomNPCs parity M12` |
| 9 | **6 animation stances + emotes + MPM parts** | Resolved: P3-1 (#58) expectation amended to name them explicitly |
| 10 | **Client presets store** (`client_presets`, `GuiPresetSave`) | Covered by #82 P8-6 expectation (named target surface) — confirmed, no change |

## Part 5 — Summary counts

| Bucket | Count |
|---|---|
| CustomNPCs feature rows (Part 1 areas) | ~95 sub-features across 16 areas |
| Part 2 rows (generated) | See the generated table footer — exact per-status counts are emitted by the checker |
| StoryNPCs-only capabilities | 9 |

**Honest headline**: StoryNPCs has complete *issue coverage* for roughly 90% of the CustomNPCs feature surface, but most of that coverage is "domain types exist, runtime not wired/certified". The genuinely unowned surfaces are small but real: combat abilities, markov names (pending decision), SQL store (recorded deviation), bundled schematic content, and NBT book. Dialogue-graph categories remain scoped under #65 (quest categories are already implemented). The register's certification machinery (P0/P11) is in place; the center of gravity of remaining work is M3 (core NPC capability) and M6/M7 (roles/jobs/economy runtime).
