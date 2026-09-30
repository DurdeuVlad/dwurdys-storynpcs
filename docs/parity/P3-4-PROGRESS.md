# P3-4 — Inventory, equipment, drops, marks, and item tools progress

Status: `IN-PROGRESS`

## Delivered locally

- `NpcItemStack`: bounded item record — namespaced `itemId` (required), `count` [1,99], server-authored `components` payload ≤4096 chars (never trusted from client NBT).
- `NpcInventory` replaces `List<String>`: 7 `ItemSlot` positions (4 armor + 3 weapon), 21 API-addressable drop entries (0–20), first 9 = visible GUI slots, `LootMode` (NORMAL/NOTHING/NPC_ONLY).
- Rejection-safe ops: `equip` → EQUIPPED/REPLACED/REJECTED_* with displaced stack returned; `unequip` → Optional; `setDrop`/`clearDrop` enforce index 0–20 and chance 0–100 — invalid operations cannot lose items.
- `getDrops()` always 21 entries; `legacyItemIds()` flattens the legacy view.
- Dual-shape serde: structured object round-trips; legacy `inventory: ["ns:item"]` migrates onto visible drops at 100% — `guard_captain.yaml` loads unchanged.
- `NpcMark`: bounded `type` [0,255], `color` [0,0xFFFFFF], `text` ≤128, `available` flag; `NpcDefinition.marks` collection ≤8 alongside the legacy `mark`.
- Service: `updateNpcInventory(id, NpcInventory)` typed route; `updateNpcInventory(id, List<String>)` compat adapter maps to drops; `setNpcMarks`.
- Creator tool selections already session-scoped via `getRuntimeSessions` — confirmed, no statics added.
- `NpcInventoryTest`: 7 fixtures — slot contract, stack bounds, equip/replace/unequip loss-safety, drop index/chance bounds, legacy flattening, loot mode, mark bounds. Plus serde tests for structured round-trip and legacy migration.
- Full suite: `BUILD SUCCESSFUL` — 59 suites, 517 tests, 0 failures. No live MC testing.

## Explicit limits

- `NpcItemStack.components` is an opaque validated payload; conversion to real NeoForge data components at entity equip time is not implemented.
- Equipment is not yet projected onto the entity's armor/hand slots at spawn (applyDefinition wiring).
- Drop chance/loot-mode evaluation on death is not wired (defeat event carries mode only).
- Fixed-seed looting fixtures and tool-item coverage beyond sessions remain open.
