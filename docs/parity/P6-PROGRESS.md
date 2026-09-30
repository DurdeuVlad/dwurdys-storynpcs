# P6 — Factions, roles, transport, jobs, companions progress (P6-1..P6-5)

Status: `IN-PROGRESS` for all five issues (local implementation).

## P6-1 — Faction matrix + deletion

- `Faction`: + `color` (0xRRGGBB bounded), `passive`, `relationships` matrix map with
  bounded setRelationship (HOSTILE/NEUTRAL/FRIENDLY) — pairwise entries consumed by
  FactionRelationshipProvider (P3-3 symmetric normalization).
- `FactionReputationChangeEvent` + `source` (request actorType / quest / system).
- `FactionDeletionPlanner`: enumerate references (NPC factionId + other factions' matrix
  entries), plan with fallback faction, fail-with-diagnostics naming blockers, `apply`
  re-points NPCs and strips matrix entries — non-viable plans throw.
- Points remain clamped (no overflow); team sharing stays explicit via TeamProgression.

## P6-2 — Service/social roles

- `PostmanRole`: mailboxCapacity ≤64, deliveryRange ≤256, fee ≥0.
- `HealerRole`: healAmount (0,100], cooldown, range ≤64, TargetPolicy, observable scanPeriodTicks.
- `BardRole`: songId, buffEffect, radius ≤64, cooldown, play duration ≤6000.
- Follower/dialogue roles already exist and route through P1-1 requests.

## P6-3 — Transport

- `TransportCategory` + `TransportLocation` (dimension, coords, yaw, unlock conditions, fee, visibility).
- `TransportEvaluator`: server-authoritative approve/reject — UNKNOWN/UNLOADED/UNSAFE/LOCKED/
  FEE_UNMET/DIMENSION_UNAVAILABLE — fee never charged on rejection; `DimensionPolicy` with
  timeout + RETURN_TO_ORIGIN/ABORT recovery; `visibleFor` listing semantics.

## P6-4 — Jobs

- `JobType` ×11 (BARD..SPAWNER), `JobConfig` typed bounded knobs (spawn caps, radii,
  cooldowns, chunk radius ≤4 — no unbounded entities/chunks), `validate()` at load.
- `JobInstance` lifecycle: RUNNING/PAUSED/STOPPED, observable tick budget, unload →
  PAUSE or STOP per config — every job stops on actor removal.

## P6-5 — Companions

- `CompanionProfile`: wage interval/amount, InsufficientFundsPolicy, UnloadPolicy,
  TacticalStance, stages (≤16, bounded multiplier [0.1,10]), talents (≤32, rank ≤ maxRank ≤10),
  `NpcInventory` container (P3-4), `activeStage(age)`.
- `WageLedger`: exactly-once per period — charged period never replays, insufficient
  funds explicit and does NOT consume the period; `periodFor` deterministic.

## Explicit limits

- No wiring into NPC entity/role subclasses yet — role configs are domain-level;
  trader/banker already exist separately (M7 completes them).
- Transport execution is wired: `requestTransport` evaluates unlocks/dimension/chunk/safety, charges the emerald fee, teleports, and refunds on transfer failure; `/storynpcs transport` and the API expose it. Cross-dimension timeout/recovery and unlock persistence remain open.
- Implemented job types (`ITEM_GIVER`, `HEALER`, `GUARD`, `FOLLOWER`, `FARMER`, `SPAWNER`, `CONVERSATION`) run via `NpcJobRuntime` on bounded `tickPeriod` from entity tick; `BUILDER`/`CHUNK_LOADER`/`PUPPET`/`BARD`-job fail validation as unimplemented.
- Companion profile binds via `companion:` YAML; wages charge exactly-once through `WageLedger`+`chargeCompanionWage` with entity-tick integration.

## Verification

`./gradlew test`: 69 suites, 570 tests, 0 failures. `git diff --check` clean. No live MC testing.
