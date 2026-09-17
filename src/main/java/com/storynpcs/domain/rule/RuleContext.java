package com.storynpcs.domain.rule;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.TacticalStance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Contextual state passed to conditions and actions during rule evaluation.
 */
public class RuleContext {

    private final NamespacedId npcId;
    private final String npcName;
    private double npcHealth;
    private double npcMaxHealth;
    private int strikesAgainstNpc;
    private UUID actorUuid;
    private UUID victimUuid;
    private double damageAmount;
    private long gameTime;
    private boolean playerActor;
    private final Map<String, Object> metadata = new HashMap<>();

    // Side-effect execution and recording
    private RuleActionHandler actionHandler;
    private final List<String> recordedMessages = new ArrayList<>();
    private final Map<UUID, Double> recordedThreats = new HashMap<>();
    private final List<String> recordedAlerts = new ArrayList<>();
    private boolean combatYielded = false;
    private String yieldDialogue;
    private TacticalStance newStance = null;
    private final Map<NamespacedId, Integer> recordedFactionAdjustments = new HashMap<>();

    public RuleContext(NamespacedId npcId, String npcName, double npcHealth, double npcMaxHealth) {
        this.npcId = npcId;
        this.npcName = npcName != null ? npcName : "StoryNPC";
        this.npcHealth = npcHealth;
        this.npcMaxHealth = Math.max(1.0, npcMaxHealth);
    }

    public NamespacedId getNpcId() { return npcId; }
    public String getNpcName() { return npcName; }

    public double getNpcHealth() { return npcHealth; }
    public void setNpcHealth(double npcHealth) { this.npcHealth = npcHealth; }

    public double getNpcMaxHealth() { return npcMaxHealth; }
    public void setNpcMaxHealth(double npcMaxHealth) { this.npcMaxHealth = Math.max(1.0, npcMaxHealth); }

    public double getHealthFraction() {
        return Math.max(0.0, Math.min(1.0, npcHealth / npcMaxHealth));
    }

    public int getStrikesAgainstNpc() { return strikesAgainstNpc; }
    public void setStrikesAgainstNpc(int strikesAgainstNpc) { this.strikesAgainstNpc = strikesAgainstNpc; }

    public Optional<UUID> getActorUuid() { return Optional.ofNullable(actorUuid); }
    public void setActorUuid(UUID actorUuid) { this.actorUuid = actorUuid; }

    public Optional<UUID> getVictimUuid() { return Optional.ofNullable(victimUuid); }
    public void setVictimUuid(UUID victimUuid) { this.victimUuid = victimUuid; }

    public double getDamageAmount() { return damageAmount; }
    public void setDamageAmount(double damageAmount) { this.damageAmount = damageAmount; }

    public long getGameTime() { return gameTime; }
    public void setGameTime(long gameTime) { this.gameTime = gameTime; }

    public boolean isPlayerActor() { return playerActor; }
    public void setPlayerActor(boolean playerActor) { this.playerActor = playerActor; }

    public void setMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> getMetadata(String key, Class<T> type) {
        Object val = metadata.get(key);
        if (val != null && type.isInstance(val)) {
            return Optional.of((T) val);
        }
        return Optional.empty();
    }

    public RuleActionHandler getActionHandler() { return actionHandler; }
    public void setActionHandler(RuleActionHandler actionHandler) { this.actionHandler = actionHandler; }

    public void sendMessage(UUID targetActor, String message, boolean actionBar) {
        recordedMessages.add(message);
        if (actionHandler != null) {
            actionHandler.onSendMessage(targetActor, message, actionBar);
        }
    }

    public void addThreat(UUID targetActor, double amount) {
        if (targetActor != null) {
            recordedThreats.merge(targetActor, amount, Double::sum);
        }
        if (actionHandler != null) {
            actionHandler.onAddThreat(targetActor, amount);
        }
    }

    public void shoutAlert(double radius, String message) {
        recordedAlerts.add(message);
        if (actionHandler != null) {
            actionHandler.onShoutAlert(radius, message);
        }
    }

    public void yieldCombat(double healFraction, String dialogue) {
        this.combatYielded = true;
        this.yieldDialogue = dialogue;
        if (healFraction > 0.0) {
            this.npcHealth = Math.min(this.npcMaxHealth, this.npcMaxHealth * healFraction);
        }
        if (actionHandler != null) {
            actionHandler.onYieldCombat(healFraction, dialogue);
        }
    }

    public void changeStance(TacticalStance stance) {
        this.newStance = stance;
        if (actionHandler != null) {
            actionHandler.onChangeStance(stance);
        }
    }

    public void adjustFaction(NamespacedId factionId, int delta) {
        if (factionId != null) {
            recordedFactionAdjustments.merge(factionId, delta, Integer::sum);
        }
        if (actionHandler != null) {
            actionHandler.onAdjustFaction(factionId, delta);
        }
    }

    public List<String> getRecordedMessages() { return Collections.unmodifiableList(recordedMessages); }
    public Map<UUID, Double> getRecordedThreats() { return Collections.unmodifiableMap(recordedThreats); }
    public List<String> getRecordedAlerts() { return Collections.unmodifiableList(recordedAlerts); }
    public boolean isCombatYielded() { return combatYielded; }
    public String getYieldDialogue() { return yieldDialogue; }
    public TacticalStance getNewStance() { return newStance; }
    public Map<NamespacedId, Integer> getRecordedFactionAdjustments() { return Collections.unmodifiableMap(recordedFactionAdjustments); }

    public void clearRecordings() {
        recordedMessages.clear();
        recordedThreats.clear();
        recordedAlerts.clear();
        combatYielded = false;
        yieldDialogue = null;
        newStance = null;
        recordedFactionAdjustments.clear();
    }
}

