package com.storynpcs.service;

import com.storynpcs.api.event.*;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.*;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.npc.*;
import com.storynpcs.domain.progression.PlayerProgression;
import com.storynpcs.domain.progression.QuestProgressState;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.domain.quest.QuestObjective;
import com.storynpcs.domain.quest.QuestReward;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.yaml.DefinitionRegistry;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Canonical Application Service Layer.
 * Guarantees 100% parity across GUI, Commands, API, and Scripts by routing
 * all mutations through these 15 canonical operations with authoritative validation.
 */
public class StoryNpcsApplicationService {
    private final DefinitionRegistry registry;
    private final ProgressionRepository progressionRepository;
    private final EventPublisher eventPublisher;
    private final Map<UUID, DialogueSession> activeSessions = new ConcurrentHashMap<>();

    public StoryNpcsApplicationService(DefinitionRegistry registry,
                                       ProgressionRepository progressionRepository,
                                       EventPublisher eventPublisher) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.progressionRepository = Objects.requireNonNull(progressionRepository, "progressionRepository");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    // ==========================================
    // 1. NPC Lifecycle Operations
    // ==========================================

    public void createNpc(NpcDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (definition.getId() == null) {
            throw new IllegalArgumentException("NPC definition must have an ID");
        }
        registry.registerNpc(definition);
    }

    public boolean deleteNpc(NamespacedId id) {
        Objects.requireNonNull(id, "id");
        if (registry.getNpc(id).isPresent()) {
            registry.removeNpc(id);
            return true;
        }
        return false;
    }

    public void updateNpcDisplay(NamespacedId id, NpcDisplay display) {
        NpcDefinition npc = registry.getNpc(id)
                .orElseThrow(() -> new NoSuchElementException("NPC not found: " + id));
        npc.setDisplay(Objects.requireNonNull(display, "display"));
    }

    public void updateNpcStats(NamespacedId id, NpcStats stats) {
        NpcDefinition npc = registry.getNpc(id)
                .orElseThrow(() -> new NoSuchElementException("NPC not found: " + id));
        npc.setStats(Objects.requireNonNull(stats, "stats"));
    }

    public void updateNpcAi(NamespacedId id, NpcAi ai) {
        NpcDefinition npc = registry.getNpc(id)
                .orElseThrow(() -> new NoSuchElementException("NPC not found: " + id));
        npc.setAi(Objects.requireNonNull(ai, "ai"));
    }

    public void updateNpcInventory(NamespacedId id, List<String> inventory) {
        NpcDefinition npc = registry.getNpc(id)
                .orElseThrow(() -> new NoSuchElementException("NPC not found: " + id));
        npc.setInventory(new ArrayList<>(inventory));
    }

    public void setNpcMark(NamespacedId id, NpcMark mark) {
        NpcDefinition npc = registry.getNpc(id)
                .orElseThrow(() -> new NoSuchElementException("NPC not found: " + id));
        npc.setMark(mark);
    }

    public void clearNpcMark(NamespacedId id) {
        setNpcMark(id, null);
    }

    public void assignDialogue(NamespacedId npcId, NamespacedId dialogueId) {
        NpcDefinition npc = registry.getNpc(npcId)
                .orElseThrow(() -> new NoSuchElementException("NPC not found: " + npcId));
        if (dialogueId != null && registry.getDialogue(dialogueId).isEmpty()) {
            throw new NoSuchElementException("Dialogue not found: " + dialogueId);
        }
        npc.setDialogueId(dialogueId);
    }

    // ==========================================
    // 2. Dialogue Directed-Graph Operations
    // ==========================================

    public DialogueView startDialogue(UUID playerUuid, NamespacedId dialogueId) {
        DialogueGraph graph = registry.getDialogue(dialogueId)
                .orElseThrow(() -> new NoSuchElementException("Dialogue not found: " + dialogueId));

        DialogueSession session = new DialogueSession(playerUuid, graph);
        activeSessions.put(playerUuid, session);

        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        progression.recordDialogueNodeVisit(session.getCurrentNodeId());

        eventPublisher.publish(new DialogueOpenEvent(playerUuid, dialogueId, session.getCurrentNodeId()));
        return buildDialogueView(session, progression);
    }

    public DialogueView chooseDialogueOption(UUID playerUuid, int optionIndex) {
        DialogueSession session = activeSessions.get(playerUuid);
        if (session == null || !session.isActive()) {
            return DialogueView.closed(session != null ? session.getDialogueId() : null);
        }

        DialogueNode currentNode = session.getCurrentNode();
        if (currentNode == null) {
            session.close();
            activeSessions.remove(playerUuid);
            return DialogueView.closed(session.getDialogueId());
        }

        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        List<DialogueEdge> availableEdges = getAvailableEdges(currentNode, progression);

        if (optionIndex < 0 || optionIndex >= availableEdges.size()) {
            throw new IndexOutOfBoundsException("Invalid option index: " + optionIndex + " (available: " + availableEdges.size() + ")");
        }

        DialogueEdge chosen = availableEdges.get(optionIndex);
        String fromNodeId = currentNode.getId();
        String toNodeId = chosen.getTargetNodeId();

        // Record selection
        if (chosen.isOnceOnly()) {
            session.recordOptionSelection(fromNodeId + "->" + toNodeId);
        }

        // Execute actions authoritatively
        if (chosen.getActions() != null) {
            for (DialogueAction action : chosen.getActions()) {
                executeAction(playerUuid, action);
            }
        }

        eventPublisher.publish(new DialogueOptionSelectEvent(playerUuid, session.getDialogueId(), fromNodeId, toNodeId, optionIndex));

        // Advance graph
        session.advanceTo(toNodeId);
        progression.recordDialogueNodeVisit(toNodeId);

        DialogueView view = buildDialogueView(session, progression);
        if (view.isTerminal()) {
            activeSessions.remove(playerUuid);
            session.close();
        }
        return view;
    }

    public void closeDialogue(UUID playerUuid) {
        DialogueSession session = activeSessions.remove(playerUuid);
        if (session != null) {
            session.close();
        }
    }

    private DialogueView buildDialogueView(DialogueSession session, PlayerProgression progression) {
        DialogueNode node = session.getCurrentNode();
        if (node == null || node.isTerminal()) {
            session.close();
            activeSessions.remove(session.getPlayerUuid());
            return new DialogueView(session.getDialogueId(), node != null ? node.getId() : "",
                    node != null ? node.getText() : "", node != null ? node.getSound() : "", List.of(), true);
        }

        List<DialogueEdge> availableEdges = getAvailableEdges(node, progression);
        List<String> optionTexts = availableEdges.stream().map(DialogueEdge::getText).toList();

        return new DialogueView(session.getDialogueId(), node.getId(), node.getText(), node.getSound(), optionTexts, false);
    }

    private List<DialogueEdge> getAvailableEdges(DialogueNode node, PlayerProgression progression) {
        if (node.getOptions() == null) return List.of();
        List<DialogueEdge> result = new ArrayList<>();
        for (DialogueEdge edge : node.getOptions()) {
            if (evalConditions(edge.getConditions(), progression)) {
                result.add(edge);
            }
        }
        return result;
    }

    private boolean evalConditions(List<DialogueCondition> conditions, PlayerProgression progression) {
        if (conditions == null || conditions.isEmpty()) return true;
        for (DialogueCondition cond : conditions) {
            if (!evalCondition(cond, progression)) return false;
        }
        return true;
    }

    private boolean evalCondition(DialogueCondition cond, PlayerProgression progression) {
        if (cond.getType() == DialogueCondition.Type.QUEST_STATUS) {
            NamespacedId qid = NamespacedId.of(cond.getTarget());
            QuestProgressState state = progression.getQuestState(qid);
            return state.getStatus().name().equalsIgnoreCase(cond.getValue());
        } else if (cond.getType() == DialogueCondition.Type.FACTION_STANDING) {
            NamespacedId fid = NamespacedId.of(cond.getTarget());
            Faction faction = registry.getFaction(fid).orElse(null);
            if (faction == null) return false;
            int pts = progression.getFactionScore(fid, faction.getDefaultPoints());
            return faction.getStandingForPoints(pts).name().equalsIgnoreCase(cond.getValue());
        } else if (cond.getType() == DialogueCondition.Type.FACTION_POINTS) {
            NamespacedId fid = NamespacedId.of(cond.getTarget());
            Faction faction = registry.getFaction(fid).orElse(null);
            int defPts = faction != null ? faction.getDefaultPoints() : 1000;
            int pts = progression.getFactionScore(fid, defPts);
            int targetVal = Integer.parseInt(cond.getValue());
            return switch (cond.getOperator()) {
                case ">=" -> pts >= targetVal;
                case "<=" -> pts <= targetVal;
                case ">" -> pts > targetVal;
                case "<" -> pts < targetVal;
                default -> pts == targetVal;
            };
        }
        return true;
    }

    private void executeAction(UUID playerUuid, DialogueAction action) {
        switch (action.getType()) {
            case START_QUEST -> startQuest(playerUuid, NamespacedId.of(action.getTarget()));
            case COMPLETE_QUEST -> completeQuest(playerUuid, NamespacedId.of(action.getTarget()));
            case ADJUST_FACTION -> {
                int delta = Integer.parseInt(action.getValue());
                adjustFactionPoints(playerUuid, NamespacedId.of(action.getTarget()), delta);
            }
            case CLOSE_DIALOGUE -> closeDialogue(playerUuid);
            default -> {}
        }
    }

    // ==========================================
    // 3. Faction Operations
    // ==========================================

    public void setFactionPoints(UUID playerUuid, NamespacedId factionId, int points) {
        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        Faction faction = registry.getFaction(factionId)
                .orElseThrow(() -> new NoSuchElementException("Faction not found: " + factionId));
        int oldPoints = progression.getFactionScore(factionId, faction.getDefaultPoints());
        progression.setFactionScore(factionId, points);

        eventPublisher.publish(new FactionReputationChangeEvent(playerUuid, factionId, oldPoints, points));
    }

    public void adjustFactionPoints(UUID playerUuid, NamespacedId factionId, int delta) {
        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        Faction faction = registry.getFaction(factionId)
                .orElseThrow(() -> new NoSuchElementException("Faction not found: " + factionId));
        int oldPoints = progression.getFactionScore(factionId, faction.getDefaultPoints());
        int newPoints = oldPoints + delta;
        progression.setFactionScore(factionId, newPoints);

        eventPublisher.publish(new FactionReputationChangeEvent(playerUuid, factionId, oldPoints, newPoints));
    }

    // ==========================================
    // 4. Quest Operations
    // ==========================================

    public void startQuest(UUID playerUuid, NamespacedId questId) {
        Quest quest = registry.getQuest(questId)
                .orElseThrow(() -> new NoSuchElementException("Quest not found: " + questId));

        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        QuestProgressState state = progression.getQuestState(questId);

        // Check prerequisites
        if (quest.getPrerequisites() != null) {
            for (NamespacedId prereq : quest.getPrerequisites()) {
                if (progression.getQuestState(prereq).getStatus() != QuestProgressState.Status.COMPLETED) {
                    throw new IllegalStateException("Prerequisite quest not completed: " + prereq);
                }
            }
        }

        state.setStatus(QuestProgressState.Status.IN_PROGRESS);
        eventPublisher.publish(new QuestStartEvent(playerUuid, questId));
    }

    public void progressQuest(UUID playerUuid, NamespacedId questId, String objectiveId, int amount) {
        Quest quest = registry.getQuest(questId)
                .orElseThrow(() -> new NoSuchElementException("Quest not found: " + questId));

        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        QuestProgressState state = progression.getQuestState(questId);

        if (state.getStatus() != QuestProgressState.Status.IN_PROGRESS) {
            return;
        }

        QuestObjective objective = quest.getObjectives().stream()
                .filter(o -> o.getId().equals(objectiveId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Objective not found: " + objectiveId));

        state.incrementCount(objectiveId, amount);
        int currentCount = state.getCount(objectiveId);

        eventPublisher.publish(new QuestObjectiveProgressEvent(
                playerUuid, questId, objectiveId, currentCount, objective.getRequiredCount()));

        // Auto check if all objectives satisfied
        boolean allComplete = quest.getObjectives().stream()
                .allMatch(o -> state.getCount(o.getId()) >= o.getRequiredCount());

        if (allComplete) {
            completeQuest(playerUuid, questId);
        }
    }

    public void completeQuest(UUID playerUuid, NamespacedId questId) {
        Quest quest = registry.getQuest(questId)
                .orElseThrow(() -> new NoSuchElementException("Quest not found: " + questId));

        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        QuestProgressState state = progression.getQuestState(questId);

        state.setStatus(QuestProgressState.Status.COMPLETED);

        // Deliver rewards
        if (quest.getRewards() != null) {
            for (QuestReward reward : quest.getRewards()) {
                if (reward.getType() == QuestReward.Type.FACTION_POINTS) {
                    adjustFactionPoints(playerUuid, NamespacedId.of(reward.getTarget()), reward.getAmount());
                }
            }
        }

        eventPublisher.publish(new QuestCompleteEvent(playerUuid, questId));
    }

    // ==========================================
    // 5. Role & Subsystem Operations
    // ==========================================

    public boolean executeTrade(UUID playerUuid, NamespacedId npcId, com.storynpcs.domain.role.trader.TradeListing trade) {
        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        int currentFactionScore = 0;
        if (trade.getRequiredFaction() != null) {
            var factionOpt = registry.getFaction(trade.getRequiredFaction());
            int defaultPts = factionOpt.map(com.storynpcs.domain.faction.Faction::getDefaultPoints).orElse(0);
            currentFactionScore = progression.getFactionScore(trade.getRequiredFaction(), defaultPts);
        }

        if (!trade.isAvailable(currentFactionScore)) {
            return false;
        }

        boolean recorded = trade.recordTrade();
        if (recorded) {
            eventPublisher.publish(new com.storynpcs.api.event.TradeExecutedEvent(playerUuid, npcId, trade));
        }
        return recorded;
    }

    public boolean setFollowerState(UUID playerUuid, NamespacedId npcId, com.storynpcs.domain.role.follower.FollowerRole role, com.storynpcs.domain.role.follower.FollowerRole.State newState) {
        if (!role.isOwnedBy(playerUuid)) {
            return false;
        }
        var oldState = role.getState();
        role.setState(newState);
        eventPublisher.publish(new com.storynpcs.api.event.FollowerStateChangeEvent(playerUuid, npcId, oldState, newState));
        return true;
    }

    public boolean setFollowerFormation(
            UUID playerUuid,
            NamespacedId npcId,
            com.storynpcs.domain.role.follower.FollowerRole role,
            com.storynpcs.domain.role.follower.FormationType newFormation,
            int slotIndex,
            double spacing
    ) {
        if (role == null || !role.isOwnedBy(playerUuid)) {
            return false;
        }
        var oldFormation = role.getFormation();
        role.setFormation(newFormation);
        role.setFormationSlot(slotIndex);
        if (spacing > 0.0) {
            role.setFormationSpacing(spacing);
        }
        eventPublisher.publish(new com.storynpcs.api.event.FollowerFormationChangeEvent(
                playerUuid, npcId, oldFormation, newFormation, slotIndex, role.getFormationSpacing()));
        return true;
    }

    public boolean depositToBank(UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo, int tab, int slot, String itemId, int count) {
        if (bankRepo == null) return false;
        var vault = bankRepo.getOrCreate(playerUuid);
        boolean success = vault.deposit(tab, slot, itemId, count);
        if (success) {
            eventPublisher.publish(new com.storynpcs.api.event.BankTransactionEvent(
                    playerUuid, com.storynpcs.api.event.BankTransactionEvent.Type.DEPOSIT, tab, itemId, count));
        }
        return success;
    }

    public java.util.Optional<com.storynpcs.domain.role.banker.BankVault.VaultItem> withdrawFromBank(
            UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo, int tab, int slot, int count) {
        if (bankRepo == null) return java.util.Optional.empty();
        var vault = bankRepo.getOrCreate(playerUuid);
        var itemOpt = vault.withdraw(tab, slot, count);
        itemOpt.ifPresent(item -> {
            eventPublisher.publish(new com.storynpcs.api.event.BankTransactionEvent(
                    playerUuid, com.storynpcs.api.event.BankTransactionEvent.Type.WITHDRAW, tab, item.getItemId(), item.getCount()));
        });
        return itemOpt;
    }
}
