package com.storynpcs.ai.combat;

import com.storynpcs.StoryNpcsAccess;
import com.storynpcs.api.event.AssaultWitnessedEvent;
import com.storynpcs.api.event.NpcAggroChangeEvent;
import com.storynpcs.api.event.NpcToleranceWarnEvent;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcAi;
import com.storynpcs.domain.npc.TacticalStance;
import com.storynpcs.entity.StoryNpcEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.List;

/**
 * Global combat listener that monitors player assaults, manages accidental hit tolerance,
 * and alerts town guards to defend innocent victims.
 */
public class WitnessProtectionManager {

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Pre event) {
        LivingEntity victim = event.getEntity();
        Entity attackerEntity = event.getSource().getEntity();

        if (victim == null || attackerEntity == null || victim == attackerEntity) {
            return;
        }

        if (!(attackerEntity instanceof ServerPlayer attackerPlayer)) {
            return;
        }

        // Creative and spectator players are outside the combat targeting
        // model — guards cannot act on them anyway, so assault bookkeeping
        // and warnings for their hits would be unenforceable noise.
        if (attackerPlayer.isCreative() || attackerPlayer.isSpectator()) {
            return;
        }

        long gameTime = victim.level().getGameTime();

        // 1. Direct hit on an NPC
        if (victim instanceof StoryNpcEntity npcVictim) {
            handleDirectNpcHit(npcVictim, attackerPlayer, gameTime);
            return;
        }

        // 2. Witnessed assault on an innocent entity/player
        if (isInnocentVictim(victim)) {
            handleWitnessedAssault(victim, attackerPlayer, gameTime);
        }
    }

    /**
     * Whether a damaged entity counts as an "innocent victim" town guards
     * will defend. {@link Enemy} is the vanilla hostile-mob marker — zombies,
     * creepers, slimes, ghasts, shulkers, and the neutral-hostile
     * {@code Monster} subclasses (endermen, zombified piglins) — and
     * {@link ArmorStand} is a decorative {@code LivingEntity}, not a
     * creature. Guards do not punish players for slaying monsters or
     * adjusting armor stands; everyone else — players, villagers, animals,
     * golems, pets — is protected.
     */
    static boolean isInnocentVictim(LivingEntity victim) {
        return victim != null && isInnocentVictimClass(victim.getClass());
    }

    /** Class-level form of {@link #isInnocentVictim(LivingEntity)}. */
    static boolean isInnocentVictimClass(Class<?> victimClass) {
        if (victimClass == null) return false;
        if (ArmorStand.class.isAssignableFrom(victimClass)) return false;
        return !Enemy.class.isAssignableFrom(victimClass);
    }

    public static void handleDirectNpcHit(StoryNpcEntity npc, ServerPlayer attacker, long gameTime) {
        // A hidden-defeat statue is not provocable — bypass-invulnerability
        // hits reach this handler below the hurt() threat guard, so strikes,
        // rules, and threat bookkeeping must all be suppressed here too.
        if (npc.isHiddenDefeat()) {
            return;
        }
        var defOpt = npc.getDefinition();
        NpcAi ai = defOpt.map(d -> d.getAi()).orElse(null);
        if (ai == null || ai.getTacticalStance() == TacticalStance.PASSIVE) {
            return;
        }

        NamespacedId npcId = null;
        try {
            npcId = NamespacedId.of(npc.getDefinitionId());
        } catch (Exception ignored) {}

        String npcName = npc.getName().getString();

        // Check if data-driven BehaviorRules are defined on the NPC
        if (defOpt.isPresent() && defOpt.get().getRules() != null && !defOpt.get().getRules().isEmpty()) {
            com.storynpcs.domain.rule.RuleContext ctx = new com.storynpcs.domain.rule.RuleContext(
                    npcId, npcName, npc.getHealth(), npc.getMaxHealth());
            ctx.setActorUuid(attacker.getUUID());
            ctx.setGameTime(gameTime);
            ctx.setPlayerActor(true);
            ctx.setStrikesAgainstNpc(npc.getThreatManager().getStrikes(attacker.getUUID()) + 1);

            final NamespacedId finalNpcId = npcId;
            final com.storynpcs.domain.npc.NpcDefinition def = defOpt.get();

            ctx.setActionHandler(new com.storynpcs.domain.rule.RuleActionHandler() {
                @Override
                public void onSendMessage(java.util.UUID targetActor, String message, boolean actionBar) {
                    if (targetActor != null && targetActor.equals(attacker.getUUID())) {
                        attacker.sendSystemMessage(Component.literal(message), actionBar);
                    } else {
                        npc.level().players().forEach(p -> {
                            if (p.distanceToSqr(npc) <= 16 * 16) {
                                p.sendSystemMessage(Component.literal(message));
                            }
                        });
                    }
                }

                @Override
                public void onAddThreat(java.util.UUID targetActor, double amount) {
                    if (targetActor != null) {
                        npc.getThreatManager().addThreat(targetActor, (int) amount);
                    }
                }

                @Override
                public void onShoutAlert(double radius, String message) {
                    double safeRadius = Math.max(1.0, Math.min(64.0, radius));
                    npc.level().getEntitiesOfClass(
                            StoryNpcEntity.class,
                            npc.getBoundingBox().inflate(safeRadius),
                            other -> other != npc && !other.isHiddenDefeat()
                    ).forEach(other -> other.getThreatManager().addThreat(attacker.getUUID(), 100));
                    npc.level().players().forEach(p -> {
                        if (p.distanceToSqr(npc) <= safeRadius * safeRadius) {
                            p.sendSystemMessage(Component.literal("§e[" + npcName + "]§c " + message));
                        }
                    });
                }

                @Override
                public void onYieldCombat(double healFraction, String dialogue) {
                    npc.getThreatManager().forgive(attacker.getUUID());
                    if (healFraction > 0.0) {
                        npc.setHealth((float) (npc.getMaxHealth() * healFraction));
                    }
                    if (dialogue != null && !dialogue.isBlank()) {
                        attacker.sendSystemMessage(Component.literal("§e[" + npcName + "]§a " + dialogue));
                    }
                }

                @Override
                public void onChangeStance(TacticalStance newStance) {
                    // VULN-55: store per-entity stance override on the entity's state — NOT on the shared NpcDefinition singleton
                    npc.getState().setTacticalStanceOverride(newStance);
                }

                @Override
                public void onAdjustFaction(NamespacedId factionId, int delta) {
                    var mod = StoryNpcsAccess.mod(attacker);
                    if (mod == null || mod.getApplicationService() == null) {
                        return;
                    }
                    try {
                        mod.getApplicationService().adjustFactionReputation(attacker.getUUID(), factionId, delta);
                    } catch (RuntimeException rejected) {
                        // A rule targeting a missing faction (or a rejected mutation) is
                        // content/config feedback — it must not crash the damage pipeline.
                        System.err.println("[StoryNPCs] rule faction adjustment rejected for "
                                + npcName + ": " + rejected.getMessage());
                    }
                }
            });

            com.storynpcs.domain.rule.RuleEngine engine = new com.storynpcs.domain.rule.RuleEngine();
            var summary = engine.evaluate(def.getRules(), com.storynpcs.domain.rule.TriggerType.ON_DAMAGED, ctx);
            if (summary.hasTriggered()) {
                // Advance hit count tracking
                npc.getThreatManager().evaluateHit(attacker.getUUID(), gameTime, 9999, 100);
                return;
            }
        }

        // Default stance-based tolerance logic
        ThreatManager.CombatReaction reaction = npc.getThreatManager().evaluateHit(
                attacker.getUUID(),
                gameTime,
                ai.getStrikeTolerance(),
                ai.getToleranceWindowTicks()
        );

        if (reaction == ThreatManager.CombatReaction.TOLERATED_WARN) {
            int strikes = npc.getThreatManager().getStrikes(attacker.getUUID());
            attacker.sendSystemMessage(Component.literal("§e[" + npcName + "]§6 Watch your weapon, citizen! (" + strikes + "/" + (ai.getStrikeTolerance() + 1) + " warnings)"), true);
            if (StoryNpcsAccess.mod(attacker) != null && npcId != null) {
                StoryNpcsAccess.mod(attacker).getEventPublisher().publish(new NpcToleranceWarnEvent(npcId, attacker.getUUID(), strikes, ai.getStrikeTolerance()));
            }
        } else {
            attacker.sendSystemMessage(Component.literal("§e[" + npcName + "]§4 That is enough! Defend yourself!"), true);
            if (StoryNpcsAccess.mod(attacker) != null && npcId != null) {
                StoryNpcsAccess.mod(attacker).getEventPublisher().publish(new NpcAggroChangeEvent(npcId, attacker.getUUID(), true, "RETALIATION_THRESHOLD_EXCEEDED"));
            }
        }
    }

    public static void handleWitnessedAssault(LivingEntity victim, ServerPlayer attacker, long gameTime) {
        // Scan bound: allyDefenseRadius is authored-bounded to <= 64, so the
        // world query never exceeds it; each guard then honors its own radius.
        int maxScanRadius = 64;
        List<StoryNpcEntity> guards = victim.level().getEntitiesOfClass(
                StoryNpcEntity.class,
                victim.getBoundingBox().inflate(maxScanRadius),
                npc -> {
                    if (!npc.isAlive() || npc.isHiddenDefeat()) return false;
                    var def = npc.getDefinition();
                    if (def.isEmpty() || def.get().getAi() == null) return false;
                    var ai = def.get().getAi();
                    if (!ai.isDefendAllies()) return false;
                    TacticalStance stance = npc.getState().getEffectiveTacticalStance(
                            com.storynpcs.StoryNpcsAccess.mod(attacker) != null
                                    ? com.storynpcs.StoryNpcsAccess.mod(attacker).getRegistry() : null);
                    if (stance == null) stance = ai.getTacticalStance();
                    if (stance != TacticalStance.GUARD && stance != TacticalStance.DEFENSIVE) return false;
                    // Honor the authored bounded radius — never the scan bound
                    // itself. Both authored bounds apply: the assault must fall
                    // inside the guard's witness detection radius AND inside its
                    // ally-defense radius (0 in either disables the response).
                    double radius = guardWitnessRadius(ai);
                    return radius > 0 && npc.distanceToSqr(victim) <= radius * radius;
                }
        );

        for (StoryNpcEntity guard : guards) {
            if (guard.hasLineOfSight(victim) || guard.hasLineOfSight(attacker)) {
                var defOpt = guard.getDefinition();
                NamespacedId guardId = null;
                try {
                    guardId = NamespacedId.of(guard.getDefinitionId());
                } catch (Exception ignored) {}

                String guardName = guard.getName().getString();

                if (defOpt.isPresent() && defOpt.get().getRules() != null && !defOpt.get().getRules().isEmpty()) {
                    com.storynpcs.domain.rule.RuleContext ctx = new com.storynpcs.domain.rule.RuleContext(
                            guardId, guardName, guard.getHealth(), guard.getMaxHealth());
                    ctx.setActorUuid(attacker.getUUID());
                    ctx.setVictimUuid(victim.getUUID());
                    ctx.setGameTime(gameTime);
                    ctx.setPlayerActor(true);

                    final NamespacedId finalGuardId = guardId;
                    ctx.setActionHandler(new com.storynpcs.domain.rule.RuleActionHandler() {
                        @Override
                        public void onSendMessage(java.util.UUID targetActor, String message, boolean actionBar) {
                            if (targetActor != null && targetActor.equals(attacker.getUUID())) {
                                attacker.sendSystemMessage(Component.literal(message), actionBar);
                            }
                        }

                        @Override
                        public void onAddThreat(java.util.UUID targetActor, double amount) {
                            if (targetActor != null) {
                                guard.getThreatManager().addThreat(targetActor, (int) amount);
                            }
                        }

                        @Override
                        public void onShoutAlert(double radius, String message) {
                            attacker.sendSystemMessage(Component.literal("§e[" + guardName + "]§c " + message), true);
                        }
                    });

                    com.storynpcs.domain.rule.RuleEngine engine = new com.storynpcs.domain.rule.RuleEngine();
                    var summary = engine.evaluate(defOpt.get().getRules(), com.storynpcs.domain.rule.TriggerType.ON_WITNESS_ASSAULT, ctx);
                    if (summary.hasTriggered()) {
                        if (StoryNpcsAccess.mod(attacker) != null && guardId != null) {
                            StoryNpcsAccess.mod(attacker).getEventPublisher().publish(new AssaultWitnessedEvent(guardId, attacker.getUUID(), victim.getUUID()));
                        }
                        continue;
                    }
                }

                guard.getThreatManager().addThreat(attacker.getUUID(), 150);

                attacker.sendSystemMessage(Component.literal("§e[" + guardName + "]§4 Halt! Unlawful assault witnessed in town!"), true);

                if (StoryNpcsAccess.mod(attacker) != null && guardId != null) {
                    StoryNpcsAccess.mod(attacker).getEventPublisher().publish(new AssaultWitnessedEvent(guardId, attacker.getUUID(), victim.getUUID()));
                    StoryNpcsAccess.mod(attacker).getEventPublisher().publish(new NpcAggroChangeEvent(guardId, attacker.getUUID(), true, "UNLAWFUL_ASSAULT_WITNESSED"));
                }
            }
        }
    }

    /**
     * Effective guard↔victim distance for an assault response: the tighter of
     * the authored witness radius (assault detection range) and the authored
     * ally-defense radius (defense engagement range). Either bound at 0
     * disables the response entirely.
     */
    static double guardWitnessRadius(NpcAi ai) {
        if (ai == null) return 0;
        return Math.min(ai.getAllyDefenseRadius(), ai.getWitnessRadius());
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity deceased = event.getEntity();
        if (deceased == null || deceased.level().isClientSide) return;
        java.util.UUID deadUuid = deceased.getUUID();
        // Clear threat across all nearby StoryNPCs within 64 blocks
        deceased.level().getEntitiesOfClass(
                StoryNpcEntity.class,
                deceased.getBoundingBox().inflate(64.0)
        ).forEach(npc -> npc.getThreatManager().forgive(deadUuid));
    }
}
