# P3-4 — Inventory, equipment, drops, marks, and item tools progress

Status: `IN-PROGRESS`

## Delivered locally

- `NpcItemStack`: bounded item record — namespaced `itemId` (required), `count` [1,99], server-authored `components` payload ≤4096 chars (never trusted from client NBT).
- `NpcInventory` replaces `List<String>`: 7 `ItemSlot` positions (4 armor + 3 weapon), 21 API-addressable drop entries (0–20), first 9 = visible GUI slots, `LootMode` (NORMAL/NOTHING/AUTO_PICKUP), authored `minExp`/`maxExp` experience range.
- Rejection-safe ops: `equip` → EQUIPPED/REPLACED/REJECTED_* with displaced stack returned; `unequip` → Optional; `setDrop`/`clearDrop` enforce index 0–20 and chance 0–100 — invalid operations cannot lose items.
- `getDrops()` always 21 entries; `legacyItemIds()` flattens the legacy view.
- Dual-shape serde: structured object round-trips; legacy `inventory: ["ns:item"]` migrates onto visible drops at 100% — `guard_captain.yaml` loads unchanged.
- `NpcMark`: bounded `type` [0,255], `color` [0,0xFFFFFF], `text` ≤128, `available` flag; `NpcDefinition.marks` collection ≤8 alongside the legacy `mark`.
- Service: `updateNpcInventory(id, NpcInventory)` typed route; `updateNpcInventory(id, List<String>)` compat adapter maps to drops; `setNpcMarks`.
- Creator tool selections already session-scoped via `getRuntimeSessions` — confirmed, no statics added.
- `NpcInventoryTest`: 7 fixtures — slot contract, stack bounds, equip/replace/unequip loss-safety, drop index/chance bounds, legacy flattening, loot mode, mark bounds. Plus serde tests for structured round-trip and legacy migration.
- `NpcDropRoll` (headless, VERIFIED_TARGET_SOURCE): per-slot independent `nextInt(100) + chance >= 100` rolls and `minExp + nextInt(maxExp - minExp)` XP — fixed-seed fixtures prove determinism, 0%/100% exactness, NOTHING suppression, and AUTO_PICKUP roll parity.
- `StoryNpcEntity.applyEquipmentProjection`: authored armor/hand items project onto real `EquipmentSlot`s on every definition apply — un-authored slots cleared, unknown items warn-once and clear, vanilla drop chances pinned to 0 so equipment can never double-dip the authored drop table. `PROJECTILE` stays authored intent (the arrow entity has no item-visual seam yet).
- `StoryNpcEntity.dropAllDeathLoot`: DIE-mode deaths roll the authored table — world items spawn at eye height with 40-tick pickup delay and random scatter (faithful target port); `AUTO_PICKUP` absorbs into the killer player's inventory (pickup sound, leftovers stay in the world, orbs spawn at the killer); `NOTHING` suppresses drops and inventory XP. `NpcLootDroppedEvent` publishes the rolled stacks + XP.
- GameTest: `dieNpcRollsAuthoredDropsIntoTheWorld` (item entity + exact XP total) and `authoredEquipmentProjectsOntoEntitySlots` cover the live entity path.
- Full suite: `BUILD SUCCESSFUL` — all tests pass.

## Explicit limits

- `NpcItemStack.components` is an opaque validated payload; no canonical `DataComponentPatch` serialization format is defined, so the string is stored-but-unapplied at equip/drop time.
- `PROJECTILE` equipment slot is authored intent only — `NpcProjectileEntity` renders as an arrow (`getDefaultPickupItem` returns EMPTY); wiring the authored item as the projectile visual needs a renderer/model decision.
- The target's `Looting` enchant read is dead code in the decompiled `dropStuff` (computed, never consumed) — replicated honestly: enchant does not modify authored chances.
- AUTO_PICKUP delivery is unit-covered at the roll level; live killer-player absorption is not GameTested (needs a real ServerPlayer).
- `legacyItemIds`/legacy array migration leaves `minExp`/`maxExp` at 0 — the legacy shape carried no XP range.
- Mark availability resync to the client, tool-item coverage beyond sessions, and live runtime-parity certification remain open.
