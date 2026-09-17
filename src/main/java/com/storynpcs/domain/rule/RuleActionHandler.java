package com.storynpcs.domain.rule;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.TacticalStance;

import java.util.UUID;

/**
 * Handler interface for dispatching rule actions to live game entities or simulation environments.
 */
public interface RuleActionHandler {
    default void onSendMessage(UUID targetActor, String message, boolean actionBar) {}
    default void onAddThreat(UUID targetActor, double amount) {}
    default void onShoutAlert(double radius, String message) {}
    default void onYieldCombat(double healFraction, String dialogue) {}
    default void onChangeStance(TacticalStance newStance) {}
    default void onAdjustFaction(NamespacedId factionId, int delta) {}
}
