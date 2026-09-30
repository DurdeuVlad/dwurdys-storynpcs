# P3-3 — AI, movement, targeting, and tactical behavior progress

Status: `IN-PROGRESS`

## Delivered locally

- `NpcAi` extended with the full targeting/tactical vocabulary:
  - `AnimationStance` — six stances (NORMAL/SITTING/LYING/SNEAKING/DANCING/AIMING).
  - `leapAtTarget`, `attackOnSight`, bounded `targetFactionIds` (≤64 namespaced ids, defensive copy), `targetPriority` (NEAREST/WEAKEST/STRONGEST/FIRST_THREAT), `tacticalBehavior` (NONE/RETREAT/STALK/AMBUSH/CIRCLE/HIT_AND_RUN), bounded `tacticalRadius` [1,64], bounded `allyDefenseRadius` [0,64].
- `FactionRelationshipProvider` contract: symmetric total function `source × target → HOSTILE/NEUTRAL/FRIENDLY`; unknown pairs and nulls resolve explicitly NEUTRAL, self-pairs FRIENDLY; `PairKey.of` normalizes unordered pairs; map keys normalized on insert and lookup. P6-1 supplies the full data-driven matrix against this contract.
- `ThreatManager` emits target-change reasons through an injectable `AggroEventSink`: `THREAT_PRIORITY_CHANGE`, `THREAT_DECAYED`, `TARGET_FORGIVEN`, `THREAT_CLEARED`; engagements report the new target, disengagements the released entity; no duplicate emission for unchanged targets.
- `StoryNpcEntity` wires the sink to `NpcAggroChangeEvent` server-side; `remove()` now stops navigation and clears the threat table before unload/despawn — path goals cannot outlive the entity.
- `NpcAiPolicyTest`: 6 fixtures — stance enum, policy bounds, defensive copies, provider symmetry/neutral fallback, reason emission semantics, full JSON round-trip.
- Full suite: `BUILD SUCCESSFUL` — 58 suites, 509 tests, 0 failures. No live MC testing.

## Explicit limits

- Tactical behaviors (RETREAT/STALK/AMBUSH/CIRCLE/HIT_AND_RUN) are authored contract data; dedicated movement goals for each pattern are not implemented — the melee goal runs default cadence.
- `attackOnSight`/`targetFactionIds` schema exists; a scan goal honoring `allyDefenseRadius` + the relationship provider is not wired into goals yet.
- Animation stances are contract data; renderer pose projection is not wired.
- `allyDefenseRadius` bounds the contract; WitnessProtectionManager still uses its existing witness radius wiring.
- Leap behavior contract only; no leap goal.
