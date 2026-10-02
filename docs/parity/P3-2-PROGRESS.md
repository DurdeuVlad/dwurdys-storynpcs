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

## Defeat runtime pass

- `DefeatResolution` (headless, domain package) maps the authored `Defeat` contract to an executable decision; `die()` now honors all three modes:
  - `DIE` — unchanged vanilla removal path (drops/XP/corpse all vanilla).
  - `HIDE` — the projection becomes an invisible, invulnerable, non-physical, non-interactable statue; threat table cleared, navigation stopped, boss bar dropped, pose/use state reset, goals and portal transfer suppressed, and hidden NPCs excluded from other NPCs' attack-on-sight scans. `respawnTimeSeconds > 0` → countdown in `aiStep` restores the NPC at its start position at full health — restoring the *authored* visibility flag, not forcing visible — and emits `NpcRespawnedEvent`; `<= 0` → stays hidden until `discard()`/despawn. The countdown is persisted in entity NBT so a world save mid-hide restores the hidden posture on reload. `die()` is re-entry safe while hidden — bypass-invulnerability damage (void, `/kill`) cannot reset the countdown or republish the event.
  - `FLEE` — survives the fatal hit at `max(1%, fleeHealthPercent)` of max health, clears the threat table + target, and returns to `startPosition` — pathing normally, teleporting on bypass-invulnerability sources (void, `/kill`) where pathing cannot reach. FLEE is an authored escape guarantee: the NPC cannot be killed by damage while the mode is authored; removal is via despawn/`discard()`, not `/kill`.
  - Fail-safe: an entity forced to 0 HP without resolving `die()` (external `setHealth`, corrupt NBT) resolves through the defeat contract on the next `aiStep` instead of becoming an unkillable husk.
- `NpcRespawnedEvent` added to the public event surface (`api.event`).
- `DefeatResolutionTest`: 6 fixtures — each mode, HIDE timer edge (non-positive → indefinite), FLEE threshold clamp, null-stats default-death.

## Explicit limits

- Ranged contract is domain-authoritative; no projectile entity/goal executes it yet (P3-3 combat runtime or later).
- `dropsProfileId` is a reference hook; drops profiles themselves belong to P3-4.
- `areaDamage`, `trail`, sounds, and particles are schema-authoritative but unrendered.
- Hidden-defeat tick gating currently freezes `aiStep` wholesale (statue semantics) — effects/fire/breath bookkeeping also pause; acceptable for the authored "vanish" behavior.
- FLEE NPCs can re-aggro on continued attack (normal aggression rules apply after the flee) — deeper disengage/retreat policy is P3-3 targeting scope.
- Totem-of-undying holders bypass the defeat contract via vanilla `checkTotemDeathProtection` (intercepted before `die()`); `LivingDeathEvent` does not fire for HIDE/FLEE (consistent with no-corpse intent).
- Live entity/damage verification deferred — no live MC testing (entity `die()`/`aiStep` paths are runtime-only; the decision layer is JUnit-pinned). A headless GameTest for die→hide→reappear is feasible once the P4/infra harness lands.
