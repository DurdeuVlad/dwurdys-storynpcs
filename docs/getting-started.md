# Getting started — Dwurdy's StoryNPCs

From a fresh install to a working NPC in about five minutes. This guide is
for **players and server owners**; beta testers should also read
[beta-testing.md](beta-testing.md), and content authors should continue to
[docs/creator](creator/).

## Requirements

- Minecraft **1.21.1**
- NeoForge **21.1.x** (built against 21.1.248)
- Java 21
- The `storynpcs-<version>.jar` from [Releases](https://github.com/DurdeuVlad/dwurdys-storynpcs/releases)

Drop the jar into `mods/` on the server (or in your single-player
NeoForge instance) and start it.

## First launch

On first world load, StoryNPCs seeds starter content into
`world/storynpcs/` and announces it to operators in chat. Nothing is
required of you — the mod works out of the box.

Your content lives here, all human-readable YAML:

```
world/storynpcs/
  definitions/     <- npcs/, dialogues/, quests/, factions/, ... (editable)
  progression/     <- player state (managed by the mod — don't hand-edit)
  beta/            <- beta tester reports and feedback
```

Edit anything under `definitions/` while the server runs, then
`/storynpcs reload`. Malformed YAML is reported in-game in plain language —
it tells you the file, line, and what to fix.

## Your first NPC (30 seconds)

As an operator:

```
/storynpcs quickstart
```

That spawns a demo NPC and gives you all five authoring wands. Right-click
the NPC to talk to it. Then:

- **Wand → Shift+Right-click an NPC** — the editor GUI (display, stats,
  movement, stance, dialogue assignment, behavior rules).
- `/storynpcs npc create storynpcs:my_npc Optional Name` — scaffold a new
  NPC definition; it writes YAML and spawns it.
- `/storynpcs help` — the full command surface; `/sn` is the short alias.

## For players (no op needed)

- `/storynpcs me` — your quests and faction standing
- `/storynpcs team ...` — shared-party quest progression
- `/storynpcs transport` — unlocked travel points
- `/storynpcs beta` — report issues or leave feedback during the beta

## Where to go next

- **Authoring content**: [docs/creator](creator/) — YAML references, GUI
  walkthroughs, and the AI patch-plan workflow.
- **Server admin**: `/storynpcs admin` commands; diagnostics and tunables
  are runtime-adjustable.
- **Mod developers**: the typed public API (`StoryNpcsApi`) with
  capability sessions, and the bounded scripting host — see
  [Decision.md](../Decision.md) ADRs for the contract.

## If something goes wrong

- Definition won't load → `/storynpcs reload` prints the exact YAML error.
- Suspicious behavior → `/storynpcs beta report <what happened>` captures
  everything needed and tells you where the file is.
- Check [RELEASE-NOTES.md](RELEASE-NOTES.md) "Known limitations" — a few
  things are honestly unverified in this beta.
