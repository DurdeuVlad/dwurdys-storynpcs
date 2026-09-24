# CustomNPCs parity traceability register

Status: `REVISION-2` — refreshed after three independent implementation-readiness reviews; fixture mappings are now semantically validated and newly identified target surfaces have explicit issue owners.

This register prevents a broad milestone from silently omitting a target capability. The supplied artifact is the comparison baseline; Citizens is not a target.

## Target evidence contract

| Evidence item | Recorded value |
|---|---|
| Artifact | `CustomNPCs-Unofficial-NeoForge-1.21.1.20251230.jar` |
| SHA-256 | `6c28d87b215fc1191488194188ec8a39dd908ae7d2d887b7c9d7d463be0a162c` |
| Archive inventory | 2,467 entries; 1,230 classes; 972 assets; 49 data entries |
| Static surfaces | 972 assets; 49 data entries; 15 operation-matrix rows; 149 GUI source records; 155 packet payloads; 70 executable command leaves; 97 API event classes; 60 persistence participants; 18 persistence stores |
| Role inventory | 7 concrete roles; 11 concrete jobs (`Bard`, `Builder`, `ChunkLoader`, `Conversation`, `Farmer`, `Follower`, `Guard`, `Healer`, `ItemGiver`, `Puppet`, `Spawner`); 3 companion-job classes |
| Runtime evidence | Static evidence is strong; target semantic equivalence is currently `0/15` proven and unavailable probes remain `UNVERIFIED` |

## Surface coverage rule

For every row below, the issue that closes it must record a field-level manifest with these columns:

`target symbol -> StoryNPCs schema -> canonical StoryNpcsApplicationService operation -> command -> GUI -> public API -> script -> event -> persistence store -> fixture -> evidence state`.

An inapplicable surface must contain an explicit reason. A target runtime behavior that cannot be probed remains `UNVERIFIED`; it cannot become green because the implementation looks plausible.

## 15 operation families

| Operation family | Target capability | Closing issues | Required proof |
|---|---|---|---|
| NPC-MODULE-MUTATION | Create, edit, spawn, despawn, delete and project NPC state | P1-1, P1-2, P3-1, P3-2, P3-3, P3-4 | Field/surface manifest, revisioned mutation, entity projection, reload fixture |
| DIALOG-DEFINITION | Graph/line/option/condition/action/sound/asset definition | P5-1, P5-3 | YAML/UI/API round trip, validation, localization/assets |
| DIALOG-PLAYER-CHOICE | Authoritative session and option selection | P5-2 | Opaque token, exactly-once effect, stale/replay/concurrency fixtures |
| FACTION-PROGRESSION | Reputation, thresholds, hostility and sources of change | P6-1 | Kill/dialogue/quest/API/script sources, clamping, cleanup, persistence |
| QUEST-DEFINITION | Objectives, dependencies, repeats, rewards and metadata | P5-4 | All objective/repeat/reward fields round trip |
| QUEST-COMPLETION | Progress, turn-in, team state and reward delivery | P1-1, P5-5, P2-3 | Typed player-scoped progression requests plus atomic/idempotent reward and restart/fault fixtures |
| ROLE-JOB | Roles, jobs and capability lifecycle | P6-2, P6-3, P6-4, P6-5 | Per-capability schema, operation, event, UI, tick budget and cleanup |
| INVENTORY-EQUIPMENT | Armor, hands, inventory, drops, XP and loot | P3-4, P2-3 | Slot/count/drop/loot field fixtures and crash safety |
| TRADE | Offers, inputs, outputs, uses, restock and access | P7-1, P2-3 | Two-input barter, pages, concurrency, restart and UI/command parity |
| BANK | Tabs, upgrades, access and vault contents | P7-2, P2-3 | Six-tab boundary, upgrade cost, private/shared access, recovery |
| TRANSPORT | Locations, categories, unlocks and cross-dimension travel | P6-3, P2-3 | Destination validation, fees/unlocks, unloaded dimension recovery |
| SPAWN-CLONE | Templates, clone capture, spawners and quotas | P8-1, P2-1, P2-3 | Named template persistence, import isolation, quota/cleanup fixtures |
| MARK | Marker catalog, color, availability and visibility | P3-4 | RGB/availability/client resync fixtures |
| SCRIPT | Hooks, wrappers, commands and bounded execution | P9-1, P9-2, P9-3 | Hook matrix, capability policy, timeout/quota/reload tests |
| CONFIG-WORLD-TOOLS | Creator tools, recipes, registered content, blocks, scenes, config and world mutations | P8-2, P8-3, P8-4, P8-5, P8-6, P9-4 | Tool/content catalog, permissions, preview, audit and recovery fixtures |

## 22 target domains

| # | Domain | Target evidence to inventory | StoryNPCs issue(s) | Completion signal |
|---:|---|---|---|---|
| 1 | Core entity and variants | `EntityCustomNpc`, model wrappers, scale, hitboxes | P1-2, P3-1 | Stable logical actor survives entity replacement; variants/hitboxes are explicit |
| 2 | Display and aesthetics | Name/title, skin sources, cloak, glow, boss bar, tint | P3-1 | Field manifest plus renderer/cache/fallback tests |
| 3 | Stats and combat | Health/regen, melee, ranged, resistances, projectile effects | P3-2 | Numeric boundaries and combat-result fixtures |
| 4 | AI and movement | Standing/wander/path, stances, doors, water, tactical movement | P3-3 | State-machine and path-budget fixtures |
| 5 | Targeting and defeat | Factions, attack-on-sight, allies, retaliation, flee/hide/dead, respawn | P3-3, P6-1 | Target policy matrix and defeat lifecycle fixtures |
| 6 | Inventory/equipment/drops | 4 armor slots, 3 weapon positions (right/left/projectile), 9 visible drop slots, 21 API-addressable drop indices (0–20), XP and loot | P3-4 | Slot/drop/loot fixtures; no loss on rejection; distinguish the drop UI from a general NPC backpack |
| 7 | Dialogue | Graph/lines/options, availability, sound, commands, variables | P5-1, P5-2, P5-3 | Field/surface matrix and session fixtures |
| 8 | Quests | Five objective families, six repeat modes, rewards, dependencies | P1-1, P5-4, P5-5 | Typed player-scoped progression, repeat boundary, dependency, team and reward fixtures |
| 9 | Factions | Points, thresholds, colors, matrix, change sources | P6-1 | Clamping, matrix and cleanup fixtures |
| 10 | Roles | Trader, follower, bank, transporter, dialog, healer/bard, postman/mailbox | P6-2 (social/service), P6-3 (transporter), P6-5 (companion/follower), P7-1 (trader), P7-2 (bank) | One acceptance subsection per role; manifest owner is role-specific |
| 11 | Jobs | `Bard`, `Builder`, `ChunkLoader`, `Conversation`, `Farmer`, `Follower`, `Guard`, `Healer`, `ItemGiver`, `Puppet`, `Spawner`; handlers, budgets, pause/resume/cleanup | P0-1, P6-4 | Artifact-resolved job list and per-job fixtures; companion job variants remain P6-5 |
| 12 | Transport | Categories, locations, unlocks, fees, cross-dimension movement | P6-3 | Safe destination and failure recovery |
| 13 | Banks | Up to six tabs, upgrade costs, private/shared access | P7-2 | Six-tab and upgrade/access fixtures |
| 14 | Trading | Two inputs, one output, pages, uses, restock | P7-1 | Atomic concurrent purchase/restock fixtures |
| 15 | Companions | Wages, stages, talents, inventory, stance and job | P6-5 | Unloaded wage, talent, inventory and lifecycle fixtures |
| 16 | Spawners/templates | Template tabs, clone/import, mob spawner, quotas | P8-1 | Named persistent template and quota fixtures |
| 17 | Marks | Symbols, RGB, availability and visibility | P3-4 | Catalog and client resync fixtures |
| 18 | Creator/world tools | Wand, cloner, scripter, remover, teleporter, soulstone, blocks, recipes, registered content, scenes and overlays | P8-2, P8-3, P8-4, P8-5, P8-6 | Tool/content workflow, permissions, preview, audit and recovery |
| 19 | Scripting | `init`, `tick`, `interact`, `damaged`, `killed`, `target`, `dialog`, `quest`, `timer` and API wrappers | P9-1, P9-2 | Hook/capability matrix and failure isolation |
| 20 | Commands | 70 leaves: 61 mutations and 9 queries, selectors and suggestions | P9-3, P9-4, P11-2 | Command inventory, administration, and parity fixtures |
| 21 | Networking | 115 server-bound and 40 client-bound payloads, including remote/admin/overlay/session traffic | P1-3, P8-6, P9-4 | Versioned codecs, malformed/replay/stale/session tests |
| 22 | Persistence | Entity/world/player stores and lifecycle ownership | P2-1, P2-2, P2-3 | Store inventory, migration and fault-injection fixtures |

## Required evidence states

Use only these states in the matrix:

- `VERIFIED_TARGET_SOURCE`: extracted from the exact JAR/decompilation.
- `VERIFIED_TARGET_RUNTIME`: observed in a target runtime fixture.
- `VERIFIED_STORYNPCS_RUNTIME`: observed in StoryNPCs runtime.
- `VERIFIED_PARITY`: both target and StoryNPCs observations match at the defined boundary.
- `UNVERIFIED_TARGET_RUNTIME`: target behavior is not currently runnable/probed.
- `INTENTIONAL_DEVIATION`: documented improvement or incompatibility approved in the issue and release notes.
- `UNKNOWN`: evidence missing; this blocks certification and cannot be silently upgraded.

## Truth-gate requirements

The following existing claims must be marked as aspirational or removed until their referenced implementation and evidence exist: `100% mutation parity`, public scripting/API parity, crash-safe progression, 500-NPC/25v25/LOD/async-path performance, and complete UI parity. This is tracked by issue P0-2.
