# P3-2 — Stats, melee, ranged, resistances, and defeat behavior progress

Status: `IN-PROGRESS`

## Delivered locally

- Extended `NpcStats` into the authoritative combat contract: bounded `maxHealth`, `attackDamage`, `movementSpeed`, `healthRegenPerSecond`, `combatRegenPerSecond`, `respawnTimeSeconds`, `aggroRange`, `xpReward`.
- `Melee` block: `attackDelayTicks`, `attackRange`, `knockbackStrength`, `effectId`/`effectDurationTicks`/`effectAmplifier` — all bounded.
- `Ranged` block: full projectile contract — `damage`, `projectileSpeed`, `projectileSize`, `areaDamage`, `trail`, `delayTicks`, `range`, `fireRateTicks`, `shotCount`, `accuracyPercent`, `gravityAffected`, `effectId`/duration/amplifier, `impactSoundId`, `trailParticleId`.
- `NpcStats.resistances` and `NpcStats.immunities` compose the shared top-level `NpcResistances` and `NpcImmunities` value types; they are not nested stats types. These are also introduced by PR #116, so the PRs still require one shared owner/base before either is merged.
- `NpcResistances`: the four target channels `knockback`, `arrow`, `melee`, `explosion` clamped to `[0, 2]` as incoming-damage multipliers.
- `NpcImmunities`: the six target toggles — potion, fall, sunlight, fire, drowning, cobweb.
- `Defeat`: `DIE`/`HIDE`/`FLEE` modes, `fleeHealthPercent`, `dropsProfileId` hook.
- Server-authoritative wiring: `applyDefinition` now projects attack damage, attack knockback, knockback resistance, and follow range onto entity attributes; `NpcMeleeAttackGoal` reads authored attack delay and range; authored `xpReward` drives the vanilla experience field; `die()` emits `NpcDefeatedEvent` (mode, respawn seconds, XP).
- Every setter throws `IllegalArgumentException` on out-of-range/non-finite input — no partial stat mutation.
- `NpcStatsTest`: 8 fixtures — per-field bounds, clamp channels, six immunity toggles, defeat modes, null-section rejection, full JSON round-trip through `NpcDefinitionSerde` (covers YAML which flows through the same serde).
- Full suite: `BUILD SUCCESSFUL` — 57 suites, 503 tests, 0 failures. No live MC testing.

## Explicit limits

- Ranged contract is domain-authoritative; no projectile entity/goal executes it yet (P3-3 combat runtime or later).
- Defeat HIDE/FLEE modes emit the event contract; runtime hide/flee/respawn scheduling is not implemented.
- `dropsProfileId` is a reference hook; drops profiles themselves belong to P3-4.
- Health/combat regen fields are authoritative data; the regen tick loop is not wired.
- `areaDamage`, `trail`, sounds, and particles are schema-authoritative but unrendered.
- Live entity/damage verification deferred — no live MC testing.
