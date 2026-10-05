# P6 — Factions, roles, transport, jobs, companions progress (P6-1..P6-5)

Status: `IN-PROGRESS` for all five issues (local implementation; issues not yet closed on the tracker).

## P6-1 — Faction matrix + deletion

- `Faction`: `color` (0xRRGGBB bounded), `passive`, `relationships` matrix map with
  bounded setRelationship (HOSTILE/NEUTRAL/FRIENDLY) — pairwise entries consumed by
  FactionRelationshipProvider (P3-3 symmetric normalization).
- `FactionReputationChangeEvent` + `source` (request actorType / quest / system).
- `FactionDeletionPlanner`: enumerates NPC primary/AI/trader/rule references, faction
  matrix entries, dialogue conditions/actions, quest faction rewards, and transport
  unlock conditions. Primary NPC bindings repair through a fallback and matrix entries
  are removed; other definition references block deletion with diagnostics. Durable
  player reputation entries are cleared before removal and restored on failure;
  faction revisions fence stale mutations across same-ID recreation.
- Points remain clamped (no overflow).
- Team sharing: `TeamProgression.shareFactionPoints` flag (default off, opt-in),
  toggled through the canonical `team.share` op + `/storynpcs team share` command.
  A faction delta applied to a sharing team's member propagates the same delta to
  every other online-or-offline member record under the canonical mutation lock —
  propagation snapshots the member set before appending so concurrent deltas never
  mutate a list under iteration.

## P6-2 — Service/social roles

- `PostmanRole`: mailboxCapacity ≤64, deliveryRange ≤256, fee ≥0 — bound to the NPC
  definition; mail delivery rides the canonical mail ops + durable mail store.
- `HealerRole`: healAmount (0,100], cooldown, range ≤64, TargetPolicy, observable
  scanPeriodTicks — ticks inside `StoryNpcEntity.tickSocialRoles`.
- `BardRole`: songId, buffEffect, radius ≤64, cooldown, play duration ≤6000 — ticks
  in the same social cadence.
- Follower/dialogue roles route through P1-1 requests; follower owner lifecycle
  (logout/unload policies, shift-interact state cycling) is entity-wired.
- Role removal cleanup: `applyDefinition` re-binds `bardRole`/`healerRole`/
  `postmanRole`/`companionProfile` from the definition on every projection refresh —
  removing a role section from YAML detaches the behavior on the next apply; all
  tick paths null-guard. Job removal stops the prior `JobInstance` and actor unload/
  removal releases forced chunks.

## P6-3 — Transport

- `TransportCategory` + `TransportLocation` (dimension, coords, yaw, unlock
  conditions, fee, visibility, authored `dimensionPolicy`).
- `TransportEvaluator`: server-authoritative approve/reject — UNKNOWN/UNLOADED/
  UNSAFE/LOCKED/FEE_UNMET/DIMENSION_UNAVAILABLE — fee never charged on rejection;
  `visibleFor` listing semantics.
- `requestTransport` (canonical op): evaluates before charging, charges emeralds,
  teleports server-side, refunds on both exception and refused-transfer; same-dim
  and cross-dim use the same validated path. `/storynpcs transport` + transport API
  are thin adapters; durable unlock records persist replay-safe (request
  fingerprint) and the durable request journal rejects ambiguous states with
  RECOVERY_REQUIRED rather than double-charging.
- `DimensionPolicy` is consumed, not just declared: cross-dimension transfers
  enqueue a `PendingTransportVerification` drained once per server tick
  (`tickTransportVerifications` ← `StoryNpcs.onServerTick`). A player not in the
  target dimension at `transferTimeoutTicks` recovers per authored policy —
  RETURN_TO_ORIGIN teleports back to the recorded origin, ABORT logs and drops.
  A refused transfer also restores origin when the policy says so (platform
  partial-move edge). Offline-at-deadline verifications drop — nobody to relocate,
  and the durable journal already holds the outcome. In-memory queue by design:
  a restart inside the window loses the check, never the journal.

## P6-4 — Jobs

- `JobType` ×11 (BARD..SPAWNER), `JobConfig` typed bounded knobs (spawn caps, radii,
  cooldowns, chunk radius ≤4 — no unbounded entities/chunks), `validate()` at load;
  invalid configs fail closed at entity apply too.
- `JobInstance` lifecycle: RUNNING/PAUSED/STOPPED, observable tick budget, unload →
  PAUSE or STOP per config — every job stops on actor removal.
- All eleven types have runtime handlers in `NpcJobRuntime`:
  - `ITEM_GIVER` — interact-driven handout with per-player cooldown (mobInteract).
  - `HEALER` / `BARD` — bounded AoE heal / regeneration pulse in `effectRadius`.
  - `GUARD` — nearest-hostile acquisition feeding the canonical threat table.
  - `FARMER` — bounded bonemeal application (≤4 crops/cycle) in `workRadius`.
  - `CHUNK_LOADER` — force-loads a clamped (≤3) chunk radius; releases only chunks
    no other loaded loader still claims (bounded neighbor scan); releases all on
    actor removal.
  - `SPAWNER` — bounded spawns of an authored definition, per-spawner ownership
    stamped into persistent data so the alive-cap survives reloads.
  - `CONVERSATION` — dispatches the configured `scriptId` through the budgeted
    `ScriptScheduler` (quarantine applies).
  - `PUPPET` — drives the synced emote lifecycle: a `scriptId` naming an emote
    loops that one animation, another scriptId falls through to the scheduler,
    no scriptId cycles the emote vocabulary; stints bounded by
    `MAX_DURATION_TICKS`.
  - `BUILDER` — keeps one tagged schematic build draining via
    `SchematicBuildService` (`buildMaintain` re-submits, else single-shot);
    schematic resolves once per RUNNING stint.
  - `FOLLOWER` — asserts the follower state machine; formation/navigation runs in
    `NpcFollowFormationGoal`.

## P6-5 — Companions

- `CompanionProfile`: wage interval/amount, InsufficientFundsPolicy, UnloadPolicy,
  TacticalStance, stages (≤16, bounded multiplier [0.1,10], per-stage talentSlots ≤8),
  talents (≤32, rank ≤ maxRank ≤10, optional `CompanionEffectType`),
  `NpcInventory` container (P3-4), `activeStage(age)`.
- `WageLedger`: exactly-once per period — charged period never replays,
  insufficient funds does NOT consume the period; `periodFor` deterministic.
  Ledger + hire tick + paused flag persist in entity NBT; charged periods also
  mirror into `PlayerProgression` (bounded FIFO, dismissed entries evict first).
- Entity tick: hire edge = first observed owner tick; `chargeCompanionWage`
  outcomes drive pause/dismiss/keep/despawn per policy with transition-only
  owner messaging; paused companions do not run jobs.
- Stage + talent effects now APPLY at runtime: `CompanionEffects.summarize`
  resolves a bounded `Projection` (stage multiplier, flat damage/armor bonuses,
  speed fraction, carry-slot bonus — flavor talents never consume slots) and the
  entity applies it as five signature-gated transient attribute modifiers on the
  20-tick social cadence. Profile removal or stage change flips the signature and
  strips/replaces modifiers — nothing stacks across refreshes. Health clamps when
  a lowered multiplier shrinks max-health.
- `companionCarryCapacity()` exposes `BASE(4)+bonus` capped at the P3-4 drop
  container bound — the interactive companion inventory UI is wave-2 scope (#150),
  so capacity is a projection, not yet a live container.
- Companion death rides the authored defeat contract (HIDE = downed/revive
  countdown, FLEE, transform, or true death) — no separate auto-respawn channel.
- Separate runtime-equip contract: `CompanionStage` enum ladder (RECRUIT..ELITE,
  1–4 slots) + `CompanionTalent`/`CompanionTalentLoadout` (equip semantics,
  duplicate rejection) model the player-facing equip surface; the authored
  profile path is the live one today.

## Explicit limits

- Trader/banker roles are M7 scope (separate issue family).
- Interactive companion inventory container UI → #150 (M12); only the bounded
  capacity projection exists today.
- Companion auto-respawn-at-owner is not a dedicated channel — authored defeat
  modes cover the "not permanently dead" semantics.
- Transport deadline verification is exercised headlessly at the
  domain/serde level only; live cross-dimension arrival behavior is verified
  in-server, not in the unit suite.

## Verification

`./gradlew test`: 1229 tests, 0 failures, 1 skipped (post-P6-5 runtime effects
+ all-11 job handlers). `git diff --check` clean. No live MC testing this pass.
