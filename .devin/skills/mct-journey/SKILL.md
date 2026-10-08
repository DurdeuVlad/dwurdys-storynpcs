---
name: mct-journey
description: Capture real rendered Minecraft client journeys as numbered, annotated screenshots using mc-pilot (mct) against a live server — enumerate every GUI surface, drive clicks/keys/typing/entity interaction, verify screen types, and produce an evidence pack for UI audits. Use when a mod's GUI must be proven working visually (not just unit-tested) or a full journey walkthrough is requested.
---

# mct-journey: real-client GUI journey capture for Minecraft mods

Drive a **real Minecraft client** (mc-pilot CLI `mct`) against a live server and produce
numbered, annotated screenshots of every screen and user journey. This replaces the
common "screenshots unavailable" blocker for GUI parity/UX evidence.

## Toolchain

- **mc-pilot** (`mct`): drives a real client over WebSocket — chat commands, raw input
  (click/type/key/scroll), entity interact, inventory, GUI introspection, screenshots.
  - Install: `npm install -g @kzheart_/mc-pilot` — upstream repo is
    `github.com/kzheart/mc-pilot`. For a source build instead, clone that repo and
    `pnpm install && pnpm build`.
  - CLI entry: `mct <cmd>` when installed globally, or `node <repo>/cli/bin/mct <cmd>`
    for a local build.
- **Server**: any real server. For a NeoForge mod use the project's own
  `./gradlew runServer` (dev environment is production-like: real packets, real mod).
- **Annotation**: `scripts/annotate.py` (PIL — `python -m pip install pillow`)
  extends each frame with a step chip, caption band, and click markers driven by
  a `manifest.json`.

Script paths below are relative to this skill directory (e.g. run
`python .devin/skills/mct-journey/scripts/annotate.py ...` from the repo root).

## Bring-up sequence

1. `mct client create <name> --version 1.21.1 --loader neoforge --account <offline-name>`
   then `mct client launch <name> --server localhost:<port>`.
   - Offline accounts need no Mojang login; the server must run `online-mode=false`.
2. Copy the built mod jar into `<client>/minecraft/mods/` so the client and server
   run the same build.
3. **Port discipline**: check `netstat -ano | grep LISTEN` first; do not disturb
   unrelated servers — pick a free port in `run/server.properties`.
4. **Op the tester**: write `run/ops.json` with the player's *offline* UUID
   (`UUID.nameUUIDFromBytes("OfflinePlayer:"+name)`, MD5 → v3/IETF bits), then ensure
   the server process actually restarts. `kill_shell` on a Gradle wrapper does NOT
   kill the java child — find and stop the PID that owns the port.
5. `mct client wait-ready <name>` → in-world. Verify with `mct entity list`.

## Window size

mct launches at 854×480. For readable captures resize the OS window:
`powershell scripts/resize-client.ps1 1600 940` (SetWindowPos on the Minecraft
window). GUI scale follows options.txt `guiScale` — scale 3 on a 1600px window gives
a ~528×301 logical canvas.

## Interaction semantics (learned the hard way)

| Goal | Command | Notes |
|---|---|---|
| Server command | `mct chat command "<cmd>"` | leading `/` optional; feedback arrives in `chat history`/`chat last` |
| Right-click entity | `mct entity interact --id <id>` | goes through `mobInteract` — held item only fires via `interactLivingEntity` when the entity returns `PASS` |
| Right-click block | `mct block interact <x> <y> <z>` | item `useOn` path — wand spawn works this way |
| Air right-click | `mct inventory use` | item `use()` only — `useOn`-only items do nothing |
| GUI click | `mct input click <x> <y>` | **logical GUI units** (screen.width/height from `gui info`), NOT pixels |
| Type | `mct input type "<text>"` | types into focused field — click the field first |
| Keys | `mct input key press <k>` | escape, backspace, digits work (dialogue options = keys 1-9) |
| Hotbar | `mct inventory hotbar <0-8>` | switch held item |
| Verify screen | `mct gui info` | returns screen class + title — always assert before screenshot |
| Screenshot | `mct screenshot --output <png>` | framebuffer pixels |

## Journey enumeration

Enumerate screens from the code, not memory: the parity catalog
(`editor/hub/GuiParityCatalog.java`) maps every destination screen; the client packet
dispatcher (`client/StoryNpcsClient.java`) lists every open path. Group into
journeys: player surfaces (`/storynpcs panel *`), admin (`/storynpcs hub`,
`quest gui`, `faction gui`, `dialogue edit`), and entity-driven (wand/dialogue/
trade/bank/nbt_book via `entity interact` or `block interact`).

## Capture loop

Per step: trigger → `sleep 2` → `gui info` (assert expected `type`) →
`mct screenshot --output raw/NN-<slug>.png` → close/navigate.
Record every click's logical coords into the manifest for annotation.
See `scripts/panel_capture.sh` for the batch pattern.

## Annotation

`manifest.json`: `{session, scale, gui_scale, shots:[{file, caption, clicks:[{x,y,label}]}]}` —
click coords are logical; annotate.py maps them to pixels via `gui_scale`
(default 3 if omitted). Output lands in
`screens/annotated/NN-<slug>.png` — numbered, captioned, click-marked. This is the
review artifact to hand to flux-design / flux-audit.

## Gotchas observed

- `mobInteract` consuming interactions means tool items are inert on entities unless
  the entity returns PASS for them — verify tool paths live, don't assume.
- `gui snapshot` returns null on Screen (non-container) GUIs — navigate by
  `input click` + `gui info` instead.
- Panel views can be stale after internal navigation (server re-issue needed) —
  reopen rather than assume refresh.
- `chat clear` before a probe makes `chat history` unambiguous.
- Client locale follows the OS — text may render localized; don't treat as a bug.
