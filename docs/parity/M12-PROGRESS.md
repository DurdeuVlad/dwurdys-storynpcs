# M12 / #150 — Player-facing screen suite progress

Issue: [#150](https://github.com/DurdeuVlad/dwurdys-storynpcs/issues/150) — every
player-facing target interaction has a StoryNPCs screen or a recorded
deviation; screens read server-issued views only; stale sessions fail closed.

## Delivered surfaces

| Surface | View → screen | Commit path |
|---|---|---|
| Quest log | `PlayerPanelViews.questLog` → `PlayerQuestLogScreen` | read-only |
| Faction standing | `factionPanel` → `PlayerFactionPanelScreen` | read-only |
| Mail list/read/write | `mail` → `PlayerMailScreen` | `ServerboundMailActionPayload` → `markMailRead`/`deleteMail`/`sendMail` (canonical) |
| Transport picker | `transport` → `PlayerTransportScreen` | `ServerboundTransportSelectPayload` → `requestTransport` (canonical) |
| Companions | `companions` → `PlayerCompanionScreen` | read-only (state cycle via entity interact) |
| Follower hire | `followerHire` → `PlayerFollowerHireScreen` | `ServerboundPanelActionPayload(hire)` → `mutateFollowerState(SET_OWNER)` (canonical) |
| Achievements | `achievements` → `PlayerAchievementsScreen` | read-only (earned progress rows) |
| Carpentry bench | `carpentry` → `PlayerCarpentryScreen` | `ServerboundPanelActionPayload(craft)` → `CarpentryBench.craft` two-phase verify→consume |
| Authored custom GUI | `CustomGuiLayout` → `CustomGuiScreen` (`CustomGuiScreenModel` flatten) | `ServerboundCustomGuiActionPayload` → `CustomGuiActionEvent`; close → `CustomGuiClosedEvent` + `closePanelSession` |

Open paths: `/storynpcs panel quests|factions|mail|transport|companions|hire|
achievements|carpentry` (permission 0 — views expose only the caller's own
progression) and `/storynpcs layout open <id> [player]` (op-gated push).

## Security/commit contract

- Session-bound commits: `openPanelSession` mints one token per player;
  `isPanelSession` gates every write; opening any other panel or role screen
  rotates the token so stale screens fail closed.
- `ServerboundPanelActionPayload` request ids are admitted through the
  bounded replay window; the custom-GUI commit treats `DUPLICATE` as a skip
  (no double event fire), while mail/transport keep canonical replay
  classification.
- Hire re-verifies the entity server-side: alive `StoryNpcEntity`, within 32
  blocks, follower role still unowned — the client names only a UUID.
- Carpentry `craft` is two-phase: verify every ingredient count, then
  consume + grant; output overflow drops at the player's feet (never voided).
- The hire/companion scans enumerate live entities at open time — parked
  companions in unloaded chunks are absent by design (matches target
  behavior of listing live entities).

## Deviations and limits (honest)

- **Companion inventory slots**: the companion panel shows carry capacity,
  stage, talents, and pause state; per-slot item drag/drop is not authored.
  Target `GuiNpcCompanionInv` maps as `EQUIVALENT` (capacity read on the
  companions panel) in `GuiParityCatalog`.
- **Follower setup/job screens**: state changes happen on the entity
  interact cycle (shift-right-click) and the companions read view — no
  dedicated setup/job GUI is authored; both map as `EQUIVALENT`.
- **Achievement view**: the target's achievement page has no vanilla
  counterpart; StoryNPCs renders earned progress (completed quests, friendly
  standings, hired companions) — not an authored-achievement catalog.
- **Custom GUI textures**: `texture` elements draw a bounded placeholder —
  authored namespaced texture refs are validated at schema load but the
  client does not resolve arbitrary images. `scroll`, `item_slot`, and
  `entity_display` elements render as labelled placeholders.
- **Verification limit**: no live-Minecraft GUI automation exists in this
  environment — evidence is headless (view builders, codec round-trips,
  session rotation/replay, model flatten/hit-test, ingredient math). The
  screens compose with `427×240`-safe row math but live render proof at
  854×480 @ GUI scale 2 is unavailable and remains an environment gap, not a
  verified pass.

## Evidence

- `PlayerPanelViewsTest` — 4 new fixtures (sort/null tolerance, earned-only
  achievements, carpentry summaries, ordering).
- `PlayerPanelPayloadsTest` — round-trips for `ServerboundPanelActionPayload`,
  `ClientboundCustomGuiOpenPayload`, `ServerboundCustomGuiActionPayload`.
- `RuntimeSessionRegistryTest` — `closePanelSession` token-match semantics.
- `CarpentryBenchTest` — required-item multiset, summary, satisfaction.
- `CustomGuiScreenModelTest` — document-order flatten, absolute coordinates,
  button hit test, input collection, unsupported-element flags, extent,
  null safety.
- `./gradlew test` — full suite green on the delivery head.
