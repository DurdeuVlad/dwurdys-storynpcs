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

- Remote/player skin resolution, cloak/glow rendering, model-entity projection, availability rules, boss-bar rendering, and hitbox projection are not complete at that point in history. (Editor/network field coverage was added in the third pass below; the whole-definition network payload already carried all display fields.)
- This document records a domain-contract slice; it is not a P3-1 completion claim.

## Second pass — display projection boundary

- Added `DisplayProjection` (immutable resolved render contract), `DisplayProjectionResolver` (deterministic resolution + diagnostics), and `DisplayProjectionCache` (per-actor, content-fingerprint invalidation).
- Skin source rules are now deterministic: TEXTURE resolves to a validated namespaced id (blank/invalid → default + `SKIN_TEXTURE_EMPTY`/`SKIN_TEXTURE_INVALID`); URL requires well-formed http(s) with a host and no whitespace (`SKIN_URL_INVALID`); PLAYER validates Minecraft username shape 3-16 `[A-Za-z0-9_]` (`SKIN_PLAYER_INVALID`). Remote resolution stays client-runtime — the projection carries the validated identity + placeholder.
- Cloak/glow resolve independently; invalid ids disable the layer with a diagnostic.
- Name visibility projects legacy `showName` + mode into `ALWAYS`/`NEVER`/`WHILE_ATTACKING`.
- Hitbox projection: width `0.6*scaleX*(modelSize/5)`, height `1.8*scaleY*(modelSize/5)`, eye `0.9*height`; `hitboxState=1` projects non-solid dims (entity keeps space, `isPushable` false).
- `StoryNpcHitboxHandler` applies dims through NeoForge `EntityEvent.Size` (`getDimensions` is final in `LivingEntity`); entity `isPushable` honors statue mode; renderer `resolveTexture` routes through the resolver.
- `DisplayProjectionResolverTest`: 11 fixtures — per-source resolution, invalid fallbacks+diagnostics, hitbox math, statue mode, null-display default, per-actor cache isolation + fingerprint refresh.
- Full suite: `BUILD SUCCESSFUL` — 56 suites, 495 tests, 0 failures. No live MC testing.

### Still open

- Remote skin fetch, availability-rule visibility beyond visibility flags, animation playback beyond stance flags, and live client render smoke fixtures. Cloak/glow/tint render layers (`NpcRenderLayer`), boss-bar lifecycle, entity glow/invisibility flags, nameplate projection, and display scale are now wired to the projection (see register P3-1 status).

## Third pass — display editor coverage

- Added `NpcDisplayScreenModel` (headless editor state) + `NpcDisplayScreen`, reachable from the main editor's new "Display & Render" button. Covers the full `NpcDisplay` contract the main panel did not fit: skin source (`texture`/`player`/`url`), skin texture/URL/player, cloak/glow textures, overlay-glow and show-layers toggles, visibility, model type/id/size, scale XYZ, tint, show-name + name-mode, living-animation, hitbox state, boss-bar mode/color, and the `NpcAi` animation stance.
- `apply()` pushes state through the domain setters only, so out-of-range/invalid input is rejected with an inline error before the save payload is built — server-side `saveNpc` validation remains the single authority.
- The skin-source picker is applied last so an explicit selection wins over stale URL/player values (the domain's own auto-flip precedence is preserved for command/YAML paths).
- Save routes through the existing `ServerboundNpcSavePayload` whole-definition JSON; `StoryNpcsClient` now dispatches save results to the display screen the same as the rules screen.
- `NpcDisplayScreenModelTest`: 8 tests — full-field load, apply→JSON→restore round-trip, picker-over-stale-URL precedence, URL source selection, validation error blocking, tint forms, missing-block creation, cycle wrap.
- Remaining honest gap: animation timelines/playback beyond the stance flag, availability-rule evaluation beyond the stored mode, and live in-client render smoke remain unproven — this slice is editor coverage only.
