# P3-1 — Display, model, hitbox, and render-feature progress

Status: `IN-PROGRESS`

## Delivered locally

- Extended the YAML-first `NpcDisplay` contract with explicit skin source (`TEXTURE`, `PLAYER`, `URL`), player/URL skin fields, cloak and glow textures, overlay/layer flags, visibility mode, model identity, bounded model size, name mode, tint, living-animation flag, hitbox state, boss-bar mode, and boss-bar color.
- Added finite/bounded validation for scale, model size, visibility, name mode, tint, hitbox, boss-bar mode, and authored text lengths. Invalid values are rejected before field assignment.
- Updated the renderer name-tag projection to honor legacy `showName` plus the target-compatible always/never/attacking name modes.
- Added JSON round-trip, bounds, and renderer-mode tests.

## Evidence

- Target source: decompiled `noppes.npcs.entity.data.DataDisplay`, whose persisted fields include skin URL/player/texture, cloak, glow, layer and overlay flags, visibility, model size, name mode, tint, living animation, hitbox, boss-bar mode/color, and model-related state.
- StoryNPCs tests: `NpcDefinitionSerdeTest` and `StoryNpcRenderModelTest` focused run: `BUILD SUCCESSFUL`.

## Explicit limits

- Remote/player skin resolution, cloak/glow rendering, model-entity projection, availability rules, boss-bar rendering, hitbox projection, and editor/network field coverage are not complete.
- This document records a domain-contract slice; it is not a P3-1 completion claim.
