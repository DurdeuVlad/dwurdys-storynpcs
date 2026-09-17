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

        ThreatManager.CombatReaction reaction = npc.getThreatManager().evaluateHit(
                attacker.getUUID(),
                gameTime,
                ai.getStrikeTolerance(),
                ai.getToleranceWindowTicks()
        );

        String npcName = npc.getName().getString();

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
                guard.getThreatManager().addThreat(attacker.getUUID(), 150);

                NamespacedId guardId = null;
                try {
                    guardId = NamespacedId.of(guard.getDefinitionId());
                } catch (Exception ignored) {}

                String guardName = guard.getName().getString();
                attacker.sendSystemMessage(Component.literal("§e[" + guardName + "]§4 Halt! Unlawful assault witnessed in town!"), true);

                if (StoryNpcs.getInstance() != null && guardId != null) {
                    StoryNpcs.getInstance().getEventPublisher().publish(new AssaultWitnessedEvent(guardId, attacker.getUUID(), victim.getUUID()));
                    StoryNpcs.getInstance().getEventPublisher().publish(new NpcAggroChangeEvent(guardId, attacker.getUUID(), true, "UNLAWFUL_ASSAULT_WITNESSED"));
                }
            }
        }
    }
}
