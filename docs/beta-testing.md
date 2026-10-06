# Beta testing — v0.11.0-beta.1

This build is a **beta**: everything is implemented and headlessly tested
(1,400+ JUnit cases), but live-server and CustomNPCs-runtime parity are
*not* certified. Testing on a real server is exactly what moves those two
claims forward — see "what helps most" below.

## For testers (players)

You need nothing but the game. Three commands cover the whole loop:

| Command | What it does |
|---|---|
| `/storynpcs beta` | Shows this short orientation in-game |
| `/storynpcs beta report [note]` | Saves a diagnostic snapshot — mod/MC/NeoForge versions, loaded content inventory, your position and quest state — to `world/storynpcs/beta/reports/`. Run it **when something looks wrong**, optionally with a one-line note. No permission needed. |
| `/storynpcs beta feedback <text>` | One-line note for the devs, appended to `world/storynpcs/beta/feedback.log` |

First time in? `/storynpcs quickstart` spawns a demo NPC and all five wands
(needs op). `/storynpcs me` shows your quests and faction standing.
`/storynpcs help` lists everything.

When you report a problem: run `/storynpcs beta report <what happened>`
right where it happened — the snapshot captures position, versions, and
state automatically. Then tell your server owner; they forward the file.
A screenshot of the problem plus the report file is a perfect bug report.

## For server owners

Install the `storynpcs-0.11.0-beta.1` jar on a NeoForge 21.1.x / Minecraft
1.21.1 server. Definitions live under `world/storynpcs/definitions/` as
YAML — editable live, `/storynpcs reload` re-reads them.

### Chores the beta surface already automates

- Tester evidence collects itself into `world/storynpcs/beta/`:
  `reports/*.md` snapshots plus the `feedback.log` stream. Forward the
  whole directory when reporting.
- `/storynpcs admin diagnostics` / loader diagnostics already log definition
  problems at startup and reload.

### What helps most (feeds the release gate)

The release gate is `BLOCKED` on exactly two kinds of live evidence. Real
servers are the only place it can come from:

1. **Live performance**: if you can run a population/siege scenario, capture
   MSPT/TPS during the load (spark, `/forge tps`, or timings) and note the
   NPC count, hardware, and view distance. That turns
   `HEADLESS_PASS_LIVE_RUNTIME_UNVERIFIED` into real data.
2. **CustomNPCs target observations** (optional, high value): if you also
   run actual CustomNPCs, record what a real NPC does for a specific
   feature (dialogue flow, trade, job, combat ability) — version, world
   setup, and the observed behavior. These import through the
   `target-runtime-import.json` mechanism (issue #121) as
   `VERIFIED_TARGET_RUNTIME` evidence.
3. **Rendered screens**: open the GUIs (wand editor, quest/faction/dialogue
   GUIs, player screens) and screenshot anything broken or ugly — layout
   was only verified headlessly.

### File a report

GitHub issues: `DurdeuVlad/dwurdys-storynpcs` — attach the
`beta/reports/*.md` file, screenshots, and say what you expected vs. what
happened.

## Known honest gaps (don't re-report)

- Target-runtime parity vs. CustomNPCs: **uncertified** — behavioral claims
  are `UNVERIFIED_TARGET_RUNTIME` by design.
- CustomNPCs world/NBT import: **unsupported** — the importer says so
  rather than guessing; supplying a real export sample unblocks it.
- Live-screen rendering and live MSPT: unverified until the evidence above
  exists.
