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
- `ThreatManager` emits target-change reasons through `AggroEventSink`; the authored `aggroDurationTicks` now drives the calm-down timer (`addThreat` used to hardcode 400 ticks) and is applied on every definition refresh.
- `WitnessProtectionManager`: guard scans exclude hidden/defeated and dead NPCs; the effective guard↔victim radius is `min(witnessRadius, allyDefenseRadius)` — both authored bounds honored; ally protection never scans beyond the 64-block cap.
- `StoryNpcEntity` wires the sink to `NpcAggroChangeEvent` server-side; `remove()` stops navigation and clears threat before unload/despawn.
- `NpcAiPolicyTest`/`TargetingPolicyTest`/`ThreatManagerTest`/`WitnessProtectionManagerTest`: policy bounds, defensive copies, provider symmetry/neutral fallback, reason emission, authored aggro-duration decay, tighter-bound witness radius.

## Explicit limits

- The full data-driven faction relationship matrix is P6-1 scope; the provider contract + authored per-faction declarations already resolve.
- Tactical-maneuver and attack-on-sight coverage is decision-layer JUnit + goal wiring; live multi-NPC combat verification is `runGameTestServer`-only at present (the HIDE/FLEE lifecycle is covered; tactical maneuver timing fixtures are not yet GameTested).
- Animation stances project to `Pose` flags only — full timeline/animation rendering is client-runtime scope.
