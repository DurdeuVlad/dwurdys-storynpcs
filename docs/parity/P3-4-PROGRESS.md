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
- `StoryNpcEntity.dropAllDeathLoot`: DIE-mode deaths roll the authored table — world items spawn at eye height with 40-tick pickup delay and random scatter (faithful target port); `AUTO_PICKUP` absorbs into the killer player's inventory (pickup sound, leftovers stay in the world, orbs spawn at the killer); `NOTHING` suppresses drops and inventory XP. `NpcLootDroppedEvent` publishes the rolled stacks + XP. Killer resolution unwraps `OwnableEntity` owners (a wolf's kill is the owner's kill — matches `NoppesUtilServer.GetDamageSourcee`). A persisted `StoryNpcDeathResolved` flag makes the roll exactly-once across chunk unloads: a resolved corpse reloading mid-window runs corpse cleanup only — no second roll, no re-published events (the cleanup kill uses a generic source with no player credit, so vanilla's `lastHurtByPlayerTime` gating suppresses the `xpReward` orb re-drop too, and `applyDefinition` no longer revives resolved corpses to full health on reload).
- GameTest: `dieNpcRollsAuthoredDropsIntoTheWorld` (item entity + exact XP total) and `authoredEquipmentProjectsOntoEntitySlots` cover the live entity path.
- Full suite: `BUILD SUCCESSFUL` — all tests pass.

- Seventh pass — component application, projectile visuals, mark resync, xpReward reset (issue #61):
  - `resolveAuthoredStack` (shared by equipment projection and drop resolution) now applies the server-authored `components` payload as a `DataComponentPatch` decoded against the registry serialization context — invalid payloads warn-once and drop the patch, not the item. Removes the stored-but-unapplied residual.
  - `NpcProjectileEntity` carries a synced `AUTHORED_ITEM` ItemStack accessor; `NpcRangedAttackGoal` resolves the authored `PROJECTILE` equipment slot once per volley; `NpcProjectileRenderer` billboards the item sprite when present, arrow model otherwise.
  - Marks resynchronize visibility: `NpcMark.displayGlyph` maps type buckets to deterministic glyphs (None→"", Exclamation→"!", Question→"?", Pointer→"▼", other→"◆"); `formatNameTag` prepends the first available mark tinted to its color — the glyph still renders when the authored nameplate is hidden. Availability edits propagate on the existing definition refresh, no new channel.
  - `xpReward` projection is now unconditional — an authored 0 clears a previously applied reward instead of leaving the stale value armed.
  - `NpcInventoryTest.markDisplayGlyphMapsTypeDeterministically` covers the glyph contract.

## Explicit limits

- `components` is applied via `DataComponentPatch` decoded from the authored JSON; component values that reference absent registry entries (e.g. an uninstalled enchantment) fail codec decode and drop the patch with a warn — the item itself still applies.
- `PROJECTILE` equipment slot renders as the authored item sprite; the projectile's impact/pickup behavior is unchanged (`getDefaultPickupItem` stays EMPTY — authored projectiles are never pickable).
- The target's `Looting` enchant read is dead code in the decompiled `dropStuff` (computed, never consumed) — replicated honestly: enchant does not modify authored chances.
- AUTO_PICKUP delivery is unit-covered at the roll level; live killer-player absorption is not GameTested (needs a real ServerPlayer).
- Authored drops/XP run unconditionally on death — the target has no `doMobLoot` gamerule check (its `dropCustomDeathLoot`/`dropFromLootTable` are empty stubs); the divergence from vanilla gamerule expectations is deliberate target parity, documented for admins.
- Two XP channels coexist by design: `stats.xpReward` (vanilla `dropExperience`) and inventory `minExp`/`maxExp` (authored drop range).
- `legacyItemIds`/legacy array migration leaves `minExp`/`maxExp` at 0 — the legacy shape carried no XP range.
- Mark visibility resyncs through the nameplate glyph (clean-room equivalent of the target's texture icons); tool-item coverage beyond sessions and live runtime-parity certification remain open.
