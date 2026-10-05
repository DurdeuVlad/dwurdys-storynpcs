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
- `apply()` validates into a detached `NpcDisplay` and swaps it in only after every setter succeeds — atomic commit, so invalid input can never leave a half-written display. Server-side `saveNpc` validation remains the single authority.
- The skin-source picker is applied last so an explicit selection wins over stale URL/player values (the domain's own auto-flip precedence is preserved for command/YAML paths).
- Back navigation applies the staged edits and preserves the expected save revision; save results route through `StoryNpcsClient` to the display screen (and the previously-orphaned `TraderBankerAdminScreen`) with payload-bound request IDs.
- `NpcDisplayScreenModelTest`: 8 tests — full-field load, apply→JSON→restore round-trip, picker-over-stale-URL precedence, URL source selection, validation error blocking, tint forms, missing-block creation, cycle wrap.
- Remaining honest gap: animation timelines/playback beyond the stance flag, availability-rule evaluation beyond the stored mode, and live in-client render smoke remain unproven — this slice is editor coverage only.

## Fourth pass — variants, cosmetic parts, emotes (#58 residual)

- **`NpcVariant`** — the target's 9 model variants (humanoid/classic player, alex, classic_64x32, golem, flying, dragon, slime, crystal, pony) as validated display data on the single `StoryNpcEntity`, each carrying base hitbox dims + eye ratio. Hitbox projection now uses variant base dims (was hardcoded player 0.6x1.8). Wire aliases accepted (`player`, `iron_golem`, `ender_dragon`, `horse`, `64x32`); unknown variants fail with the full list.
- **`NpcCosmeticPart` + `NpcBodyPart`** — MPM parts (beard, ears, horns, snout, tail, wings, fin, skirt, eyes) with bounded type index per part, 24-bit tint, and `none`/`follow_head`/`animated` behavior. Key/spec mismatch in `setParts` is rejected; parts on non-humanoid variants emit `PARTS_VARIANT_INCOMPATIBLE` (data preserved).
- **`NpcEmote` + `NpcEmoteState`** — the target's 10 `Ani*` emotes as server-authoritative runtime state (never YAML-authored): bounded duration (default 40t, max 1200t), interrupt-on-start, `progress()` for client interpolation, mirrored to clients via 3 synced data fields.
- **Renderer**: `StoryNpcRenderer` now dispatches per-variant — humanoid variants use the player model (wide/slim/flying), generic variants use `GenericNpcModel` wrapping vanilla mesh geometry baked via `context.bakeLayer` (golem/dragon/slime/crystal/pony) — clean-room, no target models. Armor/held-item layers run behind a `RenderLayerParent` proxy guarded by an instanceof check. `NpcPartLayer` renders clean-room cube parts anchored to head/body bones with tint + behavior. `NpcEmoteAnimator` applies pose overrides (wave/point/hug/bow/no/yes/aim/crawl/dance) blended over the resting pose with edge fade.
- **Surfaces**: `/storynpcs npc set variant`, `npc set part <name> <type> <color> <behavior>` / `part <name>` (clear) / `part clear`, `npc emote <emote> [duration]` (perm-2, live entities); display editor gains a Variant cycler + full part editor (part selector, enable toggle, type, color, behavior) with strict-flush on apply.
- **`NpcVariantPartsEmoteTest`**: 8 tests — variant wire/aliases/serde/hitbox, part validation + map mismatch + serde/projection + variant-incompatible diagnostic, emote lifecycle + wire parse.

### Honest residual for this pass

- Generic-variant models render baked vanilla meshes **statically** — no variant-specific animation calls (documented simplification; animation follow-up).
- Parts render clean-room cube approximations, not target-equivalent meshes — target assets are CC BY-NC and cannot be copied.
- Emote triggers beyond the command/entity API (dialogue effect legs, AI-driven `EntityAIAnimation` equivalent) remain for P3-3/M5 surfaces; the emote state machine is the delivered contract.
- Live in-client render smoke remains evidence-tier deferred (same convention as the rest of the client surface).
