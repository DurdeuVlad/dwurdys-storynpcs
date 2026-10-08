# CustomNPCs export corpus — provenance (#194)

Real CustomNPCs text-export sample captured from a live server world. These
files are the exact bytes the mod writes under `world/customnpcs/` — no
synthesized or hand-edited content.

- **Mod**: `CustomNPCs-Unofficial-NeoForge-1.21.1.20251230.jar` (CustomNPCs
  Unofficial, NeoForge port)
- **Minecraft**: 1.21.1
- **Mod loader**: NeoForge 21.1.x
- **Source**: `_cnpc_guard_test` development server world `customnpcs/` store
- **Format**: pretty-printed SNBT (NBT text) for `clones/`, `dialogs/`,
  `quests/` files — `*.dat` stores (factions, recipes, global) are gzipped
  binary NBT and remain `TARGET_WORLD_NBT` / `UNSUPPORTED_NEEDS_EVIDENCE`.

## Contents

| Path | What it exercises |
|---|---|
| `clones/Dorian both trades.json` | Named NPC with `Title`, `Texture`, `Role`/`TraderSold`/`TraderCurrency` fields, faction link |
| `clones/Cavaler.json` | Guard-style NPC with weapons/armor and resistances |
| `clones/Imp.json` | Creature variant (`CreatureType`, non-humanoid display) |
| `dialogs/36.json` | CNPC dialog document — corpus evidence only; dialog translation is re-scoped |
| `quests/16.json` | CNPC quest document — corpus evidence only; quest translation is re-scoped |

Importer support (#194): `clones/` documents translate to StoryNPCs `npc`
definitions via `CnpcTextExportTranslator`; `dialogs/`, `quests/`, and binary
`*.dat` stores are explicitly re-scoped and quarantine with named reasons.
