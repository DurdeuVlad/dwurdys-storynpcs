package com.storynpcs.ai.combat;

import com.storynpcs.domain.ability.AbilityTrigger;
import com.storynpcs.domain.ability.AbilityType;
import com.storynpcs.domain.ability.NpcAbility;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.rule.RuleContext;
import com.storynpcs.domain.rule.condition.FactionStandingCondition;
import com.storynpcs.domain.rule.condition.RuleCondition;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/**
 * Server-side dispatcher for authored NPC combat abilities (issue #147).
 *
 * <p>Pure logic: it consumes geometric state and emits {@link AbilityOutcome}
 * records; the entity applies them to real Minecraft objects. Conditions gate
 * through the shared {@link RuleCondition}/{@link RuleContext} machinery —
 * no parallel trigger DSL.
 *
 * <p>Tick budget: UPDATE abilities evaluate at most once per
 * {@link #MIN_UPDATE_PERIOD_TICKS} game ticks, and every ability is
 * individually cooldown-gated. State is per-entity; it dies with the entity,
 * so unload/removal needs no cleanup path.
 */
public class NpcAbilityController {

    /** Minimum ticks between UPDATE evaluations — bounds per-tick cost. */
    public static final int MIN_UPDATE_PERIOD_TICKS = 10;
    /** UPDATE teleport only fires when the target is at least this far. */
    public static final double TELEPORT_MIN_DISTANCE = 4.0;
    /** UPDATE teleport lands this many blocks from the target. */
    public static final double TELEPORT_APPROACH_OFFSET = 3.0;

    public enum OutcomeKind {
        /** Add (dx,dy,dz) to the victim's velocity. */
        TARGET_VELOCITY,
        /** Teleport the NPC to absolute (dx,dy,dz). */
        SELF_TELEPORT,
        /** Slowness on the victim for durationTicks at amplifier. */
        TARGET_SLOWNESS,
        /** Multiply incoming damage by amount (block). */
        DAMAGE_MULTIPLIER,
        /** Deal amount extra damage to the victim (smash). */
        BONUS_DAMAGE
    }

    public record AbilityOutcome(OutcomeKind kind, double dx, double dy, double dz,
                                 double amount, int durationTicks, int amplifier,
                                 AbilityType source) {

        static AbilityOutcome velocity(double dx, double dy, double dz, AbilityType src) {
            return new AbilityOutcome(OutcomeKind.TARGET_VELOCITY, dx, dy, dz, 0, 0, 0, src);
        }
        static AbilityOutcome teleport(double x, double y, double z) {
            return new AbilityOutcome(OutcomeKind.SELF_TELEPORT, x, y, z, 0, 0, 0, AbilityType.TELEPORT);
        }
        static AbilityOutcome slowness(int ticks, int amplifier) {
            return new AbilityOutcome(OutcomeKind.TARGET_SLOWNESS, 0, 0, 0, 0, ticks, amplifier, AbilityType.SNARE);
        }
        static AbilityOutcome damageMultiplier(double multiplier) {
            return new AbilityOutcome(OutcomeKind.DAMAGE_MULTIPLIER, 0, 0, 0, multiplier, 0, 0, AbilityType.BLOCK);
        }
        static AbilityOutcome bonusDamage(double amount) {
            return new AbilityOutcome(OutcomeKind.BONUS_DAMAGE, 0, 0, 0, amount, 0, 0, AbilityType.SMASH);
        }
    }

    /**
     * Geometric + combat context for one ability event. {@code actorStanding}
     * is the attacker's resolved faction standing toward this NPC's faction —
     * {@code null} when unresolvable (factionless actor/NPC), in which case
     * {@link FactionStandingCondition} keeps its authored default.
     */
    public record EventInput(NamespacedId npcId, String npcName,
                             double npcHealth, double npcMaxHealth,
                             double npcX, double npcY, double npcZ,
                             double targetX, double targetY, double targetZ,
                             double damageAmount, long gameTime,
                             UUID actorUuid, boolean playerActor,
                             int strikesAgainstNpc,
                             FactionStandingCondition.Standing actorStanding) {
        /** Convenience ctor for events without strike/standing context. */
        public EventInput(NamespacedId npcId, String npcName,
                          double npcHealth, double npcMaxHealth,
                          double npcX, double npcY, double npcZ,
                          double targetX, double targetY, double targetZ,
                          double damageAmount, long gameTime,
                          UUID actorUuid, boolean playerActor) {
            this(npcId, npcName, npcHealth, npcMaxHealth, npcX, npcY, npcZ,
                    targetX, targetY, targetZ, damageAmount, gameTime,
                    actorUuid, playerActor, 0, null);
        }
    }

    private final DoubleSupplier chanceRoll;
    private final Map<NpcAbility, Long> lastFiredTick = new IdentityHashMap<>();
    private long lastUpdateEvalTick = Long.MIN_VALUE;

    /** @param chanceRoll supplies [0,1) rolls — injected for deterministic tests. */
    public NpcAbilityController(DoubleSupplier chanceRoll) {
        this.chanceRoll = chanceRoll;
    }

    /** DAMAGED: block scales incoming damage; pull/push/snare/teleport react to the attacker. */
    public List<AbilityOutcome> onDamaged(List<NpcAbility> abilities, EventInput in) {
        return evaluate(abilities, AbilityTrigger.DAMAGED, in);
    }

    /** ATTACK: smash (and any future ATTACK-triggered ability) reacts to a landed hit. */
    public List<AbilityOutcome> onAttack(List<NpcAbility> abilities, EventInput in) {
        return evaluate(abilities, AbilityTrigger.ATTACK, in);
    }

    /** UPDATE: rate-limited periodic abilities while a target is active. */
    public List<AbilityOutcome> onUpdate(List<NpcAbility> abilities, EventInput in) {
        if (lastUpdateEvalTick != Long.MIN_VALUE
                && in.gameTime() - lastUpdateEvalTick < MIN_UPDATE_PERIOD_TICKS) {
            return List.of();
        }
        lastUpdateEvalTick = in.gameTime();
        return evaluate(abilities, AbilityTrigger.UPDATE, in);
    }

    private List<AbilityOutcome> evaluate(List<NpcAbility> abilities, AbilityTrigger trigger, EventInput in) {
        if (abilities == null || abilities.isEmpty()) {
            return List.of();
        }
        List<AbilityOutcome> outcomes = new ArrayList<>();
        RuleContext ctx = null;
        for (NpcAbility ability : abilities) {
            if (ability == null || ability.getTrigger() != trigger) {
                continue;
            }
            // Fail-closed: definition entry paths validate, but the controller
            // re-checks bounds/trigger-compatibility so an unvalidated ability
            // can never execute unbounded effects.
            try {
                ability.validate();
            } catch (RuntimeException invalid) {
                continue;
            }
            Long last = lastFiredTick.get(ability);
            if (last != null && in.gameTime() - last < ability.getCooldownTicks()) {
                continue;
            }
            if (ability.getChance() < 1.0 && chanceRoll.getAsDouble() >= ability.getChance()) {
                continue;
            }
            if (!ability.getConditions().isEmpty()) {
                if (ctx == null) {
                    ctx = buildContext(in);
                }
                if (ctx == null) {
                    continue;
                }
                if (!conditionsMet(ability.getConditions(), ctx)) {
                    continue;
                }
            }
            List<AbilityOutcome> fired = computeOutcome(ability, trigger, in);
            if (!fired.isEmpty()) {
                lastFiredTick.put(ability, in.gameTime());
                outcomes.addAll(fired);
            }
        }
        return outcomes;
    }

    private RuleContext buildContext(EventInput in) {
        if (in.npcId() == null) {
            return null;
        }
        RuleContext ctx = new RuleContext(in.npcId(), in.npcName(), in.npcHealth(), in.npcMaxHealth());
        ctx.setGameTime(in.gameTime());
        ctx.setDamageAmount(in.damageAmount());
        ctx.setPlayerActor(in.playerActor());
        ctx.setStrikesAgainstNpc(in.strikesAgainstNpc());
        if (in.actorUuid() != null) {
            ctx.setActorUuid(in.actorUuid());
            ctx.setVictimUuid(in.actorUuid());
        }
        if (in.actorStanding() != null) {
            ctx.setMetadata("Standing", in.actorStanding());
        }
        return ctx;
    }

    private boolean conditionsMet(List<RuleCondition> conditions, RuleContext ctx) {
        for (RuleCondition condition : conditions) {
            if (condition == null) {
                return false;
            }
            try {
                if (!condition.evaluate(ctx)) {
                    return false;
                }
            } catch (RuntimeException malformed) {
                // A malformed authored condition must fail closed — never let
                // it unwind the entity tick or damage pipeline with an NPE.
                return false;
            }
        }
        return true;
    }

    private List<AbilityOutcome> computeOutcome(NpcAbility ability, AbilityTrigger trigger, EventInput in) {
        double dxTarget = in.targetX() - in.npcX();
        double dzTarget = in.targetZ() - in.npcZ();
        double dist = Math.sqrt(dxTarget * dxTarget + dzTarget * dzTarget);

        switch (ability.getType()) {
            case BLOCK:
                return List.of(AbilityOutcome.damageMultiplier(ability.effectiveDamageMultiplier()));

            case PULL:
            case PUSH: {
                if (trigger == AbilityTrigger.UPDATE && dist > ability.effectiveRange()) {
                    return List.of(); // UPDATE trigger must be in range
                }
                double horizontal = Math.max(dist, 1.0e-4);
                double dir = ability.getType() == AbilityType.PULL ? -1.0 : 1.0;
                double s = ability.effectiveStrength();
                return List.of(AbilityOutcome.velocity(
                        dir * dxTarget / horizontal * s,
                        Math.min(0.4 * s, 1.0), // small lift so the victim actually moves
                        dir * dzTarget / horizontal * s, ability.getType()));
            }

            case SMASH: {
                List<AbilityOutcome> out = new ArrayList<>();
                out.add(AbilityOutcome.velocity(0, ability.effectiveStrength(), 0, AbilityType.SMASH));
                if (ability.effectiveBonusDamage() > 0) {
                    out.add(AbilityOutcome.bonusDamage(ability.effectiveBonusDamage()));
                }
                return out;
            }

            case SNARE: {
                if (trigger == AbilityTrigger.UPDATE && dist > ability.effectiveRange()) {
                    return List.of();
                }
                return List.of(AbilityOutcome.slowness(
                        ability.effectiveDurationTicks(), ability.effectiveAmplifier()));
            }

            case TELEPORT: {
                double range = ability.effectiveRange();
                if (trigger == AbilityTrigger.DAMAGED) {
                    // DAMAGED: blink away from the attacker.
                    double nx = in.npcX() - in.targetX(), nz = in.npcZ() - in.targetZ();
                    double h = Math.max(Math.hypot(nx, nz), 1.0e-4);
                    return List.of(AbilityOutcome.teleport(
                            in.npcX() + nx / h * range, in.npcY(), in.npcZ() + nz / h * range));
                }
                // UPDATE: approach — land TELEPORT_APPROACH_OFFSET blocks from the target.
                if (dist < TELEPORT_MIN_DISTANCE || dist > range) {
                    return List.of();
                }
                double nx = -dxTarget / dist, nz = -dzTarget / dist;
                return List.of(AbilityOutcome.teleport(
                        in.targetX() + nx * TELEPORT_APPROACH_OFFSET,
                        in.targetY(),
                        in.targetZ() + nz * TELEPORT_APPROACH_OFFSET));
            }
        }
        return List.of();
    }
}
