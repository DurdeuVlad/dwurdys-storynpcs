package com.storynpcs.ai.combat;

import com.storynpcs.StoryNpcs;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

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

        long gameTime = victim.level().getGameTime();

        // 1. Direct hit on an NPC
        if (victim instanceof StoryNpcEntity npcVictim) {
            handleDirectNpcHit(npcVictim, attackerPlayer, gameTime);
            return;
        }

        // 2. Witnessed assault on an innocent entity/player
        handleWitnessedAssault(victim, attackerPlayer, gameTime);
    }

    public static void handleDirectNpcHit(StoryNpcEntity npc, ServerPlayer attacker, long gameTime) {
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
                    npc.level().getEntitiesOfClass(
                            StoryNpcEntity.class,
                            npc.getBoundingBox().inflate(radius),
                            other -> other != npc
                    ).forEach(other -> other.getThreatManager().addThreat(attacker.getUUID(), 100));
                    npc.level().players().forEach(p -> {
                        if (p.distanceToSqr(npc) <= radius * radius) {
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
                    if (def.getAi() != null) {
                        def.getAi().setTacticalStance(newStance);
                    }
                }

                @Override
                public void onAdjustFaction(NamespacedId factionId, int delta) {
                    if (StoryNpcs.getInstance() != null && StoryNpcs.getInstance().getApplicationService() != null) {
                        StoryNpcs.getInstance().getApplicationService().adjustFactionReputation(attacker.getUUID(), factionId, delta);
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
            if (StoryNpcs.getInstance() != null && npcId != null) {
                StoryNpcs.getInstance().getEventPublisher().publish(new NpcToleranceWarnEvent(npcId, attacker.getUUID(), strikes, ai.getStrikeTolerance()));
            }
        } else {
            attacker.sendSystemMessage(Component.literal("§e[" + npcName + "]§4 That is enough! Defend yourself!"), true);
            if (StoryNpcs.getInstance() != null && npcId != null) {
                StoryNpcs.getInstance().getEventPublisher().publish(new NpcAggroChangeEvent(npcId, attacker.getUUID(), true, "RETALIATION_THRESHOLD_EXCEEDED"));
            }
        }
    }

    public static void handleWitnessedAssault(LivingEntity victim, ServerPlayer attacker, long gameTime) {
        int scanRadius = 24;
        List<StoryNpcEntity> guards = victim.level().getEntitiesOfClass(
                StoryNpcEntity.class,
                victim.getBoundingBox().inflate(scanRadius),
                npc -> {
                    var def = npc.getDefinition();
                    if (def.isEmpty() || def.get().getAi() == null) return false;
                    TacticalStance stance = def.get().getAi().getTacticalStance();
                    return stance == TacticalStance.GUARD || stance == TacticalStance.DEFENSIVE;
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
                        if (StoryNpcs.getInstance() != null && guardId != null) {
                            StoryNpcs.getInstance().getEventPublisher().publish(new AssaultWitnessedEvent(guardId, attacker.getUUID(), victim.getUUID()));
                        }
                        continue;
                    }
                }

                guard.getThreatManager().addThreat(attacker.getUUID(), 150);

                attacker.sendSystemMessage(Component.literal("§e[" + guardName + "]§4 Halt! Unlawful assault witnessed in town!"), true);

                if (StoryNpcs.getInstance() != null && guardId != null) {
                    StoryNpcs.getInstance().getEventPublisher().publish(new AssaultWitnessedEvent(guardId, attacker.getUUID(), victim.getUUID()));
                    StoryNpcs.getInstance().getEventPublisher().publish(new NpcAggroChangeEvent(guardId, attacker.getUUID(), true, "UNLAWFUL_ASSAULT_WITNESSED"));
                }
            }
        }
    }
}
