# P3-3 — AI, movement, targeting, and tactical behavior progress

Status: `IN-PROGRESS`

## Delivered locally

- `NpcAi` carries the full targeting/tactical vocabulary:
  - `AnimationStance` — six stances (NORMAL/SITTING/LYING/SNEAKING/DANCING/AIMING), projected onto the entity pose.
  - `leapAtTarget`, `attackOnSight`, bounded `targetFactionIds` (≤64 namespaced ids, defensive copy), `targetPriority` (NEAREST/WEAKEST/STRONGEST/FIRST_THREAT), `tacticalBehavior` (NONE/RETREAT/STALK/AMBUSH/CIRCLE/HIT_AND_RUN), bounded `tacticalRadius` [1,64], bounded `allyDefenseRadius` [0,64], `strikeTolerance`, `toleranceWindowTicks`, `witnessRadius`, `aggroDurationTicks` [≥40].
  - `MovementType` STANDING/WANDERING/PATHING gates stroll/patrol/return goals; door-open and water-avoidance project onto navigation; `returnToStart` honored by `NpcReturnToStartGoal`.
- `FactionRelationshipProvider` contract: symmetric total function `source × target → HOSTILE/NEUTRAL/FRIENDLY`; unknown pairs and nulls resolve explicitly NEUTRAL, self-pairs FRIENDLY; `PairKey.of` normalizes unordered pairs; merge picks the more hostile declaration deterministically. P6-1 supplies the full data-driven matrix against this contract.
- `TargetingPolicy` (headless): attack-on-sight eligibility (same-faction never engages; authored faction list OR provider-HOSTILE; factionless candidates only via caller-resolved hostile standing; passive own faction never initiates) and deterministic priority selection — ties always break distance-then-UUID.
- `NpcAttackOnSightGoal`: bounded 10-tick sight scans, factioned/player candidate resolution, player standing from durable progression vs faction hostile thresholds; feeds the threat table so engagement stays canonical.
- `TacticalManeuver` (headless) + `NpcMeleeAttackGoal` execute all five tactical behaviors: RETREAT with hysteresis latch, HIT_AND_RUN post-strike backoff window, STALK creep-hold window, AMBUSH hold-inside-radius, CIRCLE orbit with direction swaps; `leapAtTarget` closes distance on the ground.
- `ThreatManager` emits target-change reasons through `AggroEventSink`; the authored `aggroDurationTicks` drives the calm-down timer in real game ticks (`tick(decayRate, elapsedTicks)` consumes the elapsed game ticks — the entity pulses it on the sensing grid, throttled to ≥20-tick spacing by the elapsed-time marker itself, so sensing-budget skips can't stretch the window and per-entity `tickCount` phases can't deadlock against the scheduler's global sensing grid); `addThreat` previously hardcoded 400 invocations (~400 s, not 400 ticks). A shorter authored duration clamps a running timer on refresh; a longer one applies to the next threat write.
- `WitnessProtectionManager`: guard scans exclude hidden/defeated and dead NPCs; the effective guard↔victim radius is `min(witnessRadius, allyDefenseRadius)` — both authored bounds honored; ally protection never scans beyond the 64-block cap.
- `StoryNpcEntity` wires the sink to `NpcAggroChangeEvent` server-side; `remove()` stops navigation and clears threat before unload/despawn.
- `NpcAiPolicyTest`/`TargetingPolicyTest`/`ThreatManagerTest`/`WitnessProtectionManagerTest`: policy bounds, defensive copies, provider symmetry/neutral fallback, reason emission, authored aggro-duration decay, tighter-bound witness radius.

- Sixth pass — B6/B7 vocabulary completion + startup projection fix (issue #60):
  - `NpcAi` gains the remaining target vocabulary: `doorBust` (B6 door bust), `seekShade` (B6 find shade), `shelterIndoors` (B6 move indoors), `watchClosest` (B6 watch-closest, default-on), `sprintToTarget`, `panicOnHurt`, `avoidTargets`, `defendOwner` (B7), `defeatTransformId` (B7 transform — nullable namespaced ref).
  - `NpcAuthoredBehaviorGoals`: authored-flag-gated vanilla primitives — `PanicOnHurtGoal` (priority 1, outranks melee via MOVE-flag order), `DoorBustGoal` (BreakDoorGoal), `SeekShadeGoal`/`ShelterIndoorsGoal` (bounded sheltered-block scan inside walkingRange, idle-only — suppressed while a threat target exists), `WatchClosestGoal` (gates the vanilla look-at).
  - `NpcAvoidTargetsGoal`: flees selector-matched entities through `TargetingPolicy.isEligibleForAvoidance` — mirrors `isEligible` minus the attack-on-sight/passive gates; reuses the extracted static `playerHostileStanding`; bounded 10-tick scan in a fixed 12-block radius; per-instance relationship-provider cache (no statics).
  - `WitnessProtectionManager`: panic/avoid NPCs suppress retaliation bookkeeping like PASSIVE; `handleOwnerDefense` retaliates for follower-owned NPCs when `defendOwner` is authored — scan bounded by the outer 64-block box AND each NPC's authored `allyDefenseRadius`.
  - `die()`: `defeatTransformId` rebinds the projection at full health on lethal defeat (transform replaces the corpse path; HIDE/FLEE modes keep their own semantics; unresolvable ids fall back to authored death). `transformInto` clears threat/target and resets `deathDropsResolved` for the new identity.
  - Melee goal sprints while closing when `sprintToTarget` is authored; sprint clears on goal stop.
  - **Fixed (pre-existing gap above):** disk-loaded entities that deserialized before the registry populated now re-apply definitions — `onEntityJoinLevel` and the post-`ServerStartedEvent` sweep both call `applyDefinition` after actor reconciliation. `applyDefinition` no longer heals disk-loaded entities to authored max: a `loadedFromDisk` flag preserves persisted health (fixes both halves of the bug — early entities missed projections AND late entities silently healed on every reload).
  - Fixtures: `TargetingPolicyTest` avoidance-eligibility cases (flag gate, attack-on-sight independence, same-faction exemption, provider hostility, hostile standing, null fail-closed); `NpcAiPolicyTest` B6/B7 defaults + serde round-trip.

## Explicit limits

- The full data-driven faction relationship matrix is P6-1 scope; the provider contract + authored per-faction declarations already resolve.
- Tactical-maneuver and attack-on-sight coverage is decision-layer JUnit + goal wiring; live multi-NPC combat verification is `runGameTestServer`-only at present (the HIDE/FLEE lifecycle is covered; tactical maneuver timing fixtures are not yet GameTested). The new vocabulary goals are likewise decision-layer + wiring verified.
- Animation stances project to `Pose` flags only — full timeline/animation rendering is client-runtime scope.
- `NpcAttackOnSightGoal` scans on its own 10-tick `scanDelay` and ignores the scheduler's SENSING budget — DISTANT-tier NPCs still pay full acquisition cost (pre-existing inversion, noted for the sim-tier workstream).
