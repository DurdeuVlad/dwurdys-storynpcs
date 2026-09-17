package com.storynpcs.service;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.api.event.StoryNpcsEvent;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StoryNpcsApplicationServiceTest {
    @TempDir
    Path tempDir;

    private DefinitionRegistry registry;
    private ProgressionRepository progressionRepository;
    private EventPublisher eventPublisher;
    private StoryNpcsApplicationService service;
    private List<StoryNpcsEvent> publishedEvents;

    @BeforeEach
    void setUp() {
        registry = new DefinitionRegistry();
        progressionRepository = new ProgressionRepository(tempDir);
        eventPublisher = new EventPublisher();
        publishedEvents = new ArrayList<>();
        eventPublisher.register(publishedEvents::add);

        service = new StoryNpcsApplicationService(registry, progressionRepository, eventPublisher);
    }

    @Test
    void shouldExecuteNpcLifecycleOperationsWithParity() {
        NamespacedId npcId = NamespacedId.of("storynpcs:blacksmith");
        NpcDefinition def = new NpcDefinition(npcId, "Goran");
        def.getStats().setMaxHealth(40.0);

        // 1. Create NPC
        service.createNpc(def);
        assertThat(registry.getNpc(npcId)).isPresent();

        // 2. Update Display
        NpcDisplay display = new NpcDisplay();
        display.setName("Master Goran");
        display.setTitle("Weaponsmith");
        service.updateNpcDisplay(npcId, display);
        assertThat(registry.getNpc(npcId).get().getDisplay().getName()).isEqualTo("Master Goran");

        // 3. Update Stats
        NpcStats stats = new NpcStats();
        stats.setMaxHealth(100.0);
        stats.setAttackDamage(12.0);
        service.updateNpcStats(npcId, stats);
        assertThat(registry.getNpc(npcId).get().getStats().getMaxHealth()).isEqualTo(100.0);

        // 4. Update AI
        NpcAi ai = new NpcAi();
        ai.setMovementType(NpcAi.MovementType.WANDERING);
        ai.setWalkingRange(20);
        service.updateNpcAi(npcId, ai);
        assertThat(registry.getNpc(npcId).get().getAi().getMovementType()).isEqualTo(NpcAi.MovementType.WANDERING);

        // 5. Update Inventory
        service.updateNpcInventory(npcId, List.of("minecraft:iron_sword", "minecraft:shield"));
        assertThat(registry.getNpc(npcId).get().getInventory()).containsExactly("minecraft:iron_sword", "minecraft:shield");

        // 6. Set and Clear Mark
        service.setNpcMark(npcId, new NpcMark(1, 0xFFFF00, "!"));
        assertThat(registry.getNpc(npcId).get().getMark()).isNotNull();
        assertThat(registry.getNpc(npcId).get().getMark().getType()).isEqualTo(1);

        service.clearNpcMark(npcId);
        assertThat(registry.getNpc(npcId).get().getMark()).isNull();

        // 7. Delete NPC
        boolean deleted = service.deleteNpc(npcId);
        assertThat(deleted).isTrue();
        assertThat(registry.getNpc(npcId)).isEmpty();
    }

    @Test
    void shouldDriveInteractiveDirectedDialogueGraphWithConditionsAndActions() {
        UUID playerUuid = UUID.randomUUID();

        // Set up Faction & Quest
        NamespacedId factionId = NamespacedId.of("storynpcs:knights");
        registry.registerFaction(new Faction(factionId, "Knights", 1000, 500, 1500));

        NamespacedId questId = NamespacedId.of("storynpcs:kill_dragons");
        Quest quest = new Quest(questId, "Kill Dragons");
        quest.setObjectives(List.of(new QuestObjective("kill_drake", QuestObjective.Type.KILL_ENTITY, "minecraft:ender_dragon", 1)));
        registry.registerQuest(quest);

        // Set up Dialogue Graph with cycle and condition gating
        NamespacedId dialogueId = NamespacedId.of("storynpcs:commander_dialogue");
        DialogueGraph graph = new DialogueGraph(dialogueId, "Commander Dialogue", "greeting");

        DialogueNode greeting = new DialogueNode("greeting", "Greetings, soldier. What report do you bring?");
        DialogueNode questNode = new DialogueNode("quest_node", "A dangerous dragon lurks nearby.");
        DialogueNode farewell = new DialogueNode("farewell", "Dismissed!");

        // Edge to questNode triggers START_QUEST and gives +200 reputation
        DialogueEdge toQuestEdge = new DialogueEdge("Ask for orders", "quest_node");
        toQuestEdge.getActions().add(new DialogueAction(DialogueAction.Type.START_QUEST, questId.toString(), ""));
        toQuestEdge.getActions().add(new DialogueAction(DialogueAction.Type.ADJUST_FACTION, factionId.toString(), "200"));
        greeting.addOption(toQuestEdge);

        // Option only visible if player has Friendly standing with Knights (>= 1500)
        DialogueEdge secretMissionEdge = new DialogueEdge("Report for secret mission", "farewell");
        secretMissionEdge.getConditions().add(new DialogueCondition(
                DialogueCondition.Type.FACTION_STANDING, factionId.toString(), "==", "FRIENDLY"));
        greeting.addOption(secretMissionEdge);

        // Return cycle from questNode back to greeting
        questNode.addOption(new DialogueEdge("Understood, returning to briefing.", "greeting"));

        graph.addNode(greeting);
        graph.addNode(questNode);
        graph.addNode(farewell);
        registry.registerDialogue(graph);

        // 1. Player initiates dialogue
        DialogueView view1 = service.startDialogue(playerUuid, dialogueId);
        assertThat(view1.nodeId()).isEqualTo("greeting");
        // Secret mission option should be HIDDEN because faction points = 1000 (Neutral)
        assertThat(view1.options()).containsExactly("Ask for orders");

        // 2. Player chooses "Ask for orders" (index 0)
        DialogueView view2 = service.chooseDialogueOption(playerUuid, 0);
        assertThat(view2.nodeId()).isEqualTo("quest_node");

        // Verify side effects: Quest started and faction points adjusted!
        PlayerProgression prog = progressionRepository.getOrCreate(playerUuid);
        assertThat(prog.getQuestState(questId).getStatus()).isEqualTo(QuestProgressState.Status.IN_PROGRESS);
        assertThat(prog.getFactionScore(factionId, 1000)).isEqualTo(1200);

        // 3. Player takes cycle edge back to greeting
        DialogueView view3 = service.chooseDialogueOption(playerUuid, 0);
        assertThat(view3.nodeId()).isEqualTo("greeting");

        // Now adjust faction to 1600 (Friendly) and re-open dialogue
        service.adjustFactionPoints(playerUuid, factionId, 400); // 1200 + 400 = 1600
        DialogueView view4 = service.startDialogue(playerUuid, dialogueId);
        // Both options should now be visible!
        assertThat(view4.options()).containsExactly("Ask for orders", "Report for secret mission");

        // Choose secret mission -> terminal node farewell
        DialogueView view5 = service.chooseDialogueOption(playerUuid, 1);
        assertThat(view5.nodeId()).isEqualTo("farewell");
        assertThat(view5.isTerminal()).isTrue();
    }

    @Test
    void shouldManageQuestProgressionAndRewards() {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId factionId = NamespacedId.of("storynpcs:merchants");
        registry.registerFaction(new Faction(factionId, "Merchants", 500, 200, 800));

        NamespacedId questId = NamespacedId.of("storynpcs:fetch_wood");
        Quest quest = new Quest(questId, "Fetch Wood");
        quest.setObjectives(List.of(new QuestObjective("collect_logs", QuestObjective.Type.COLLECT_ITEM, "minecraft:oak_log", 3)));
        quest.setRewards(List.of(new QuestReward(QuestReward.Type.FACTION_POINTS, factionId.toString(), 150)));
        registry.registerQuest(quest);

        // Start Quest
        service.startQuest(playerUuid, questId);
        PlayerProgression prog = progressionRepository.getOrCreate(playerUuid);
        assertThat(prog.getQuestState(questId).getStatus()).isEqualTo(QuestProgressState.Status.IN_PROGRESS);

        // Progress partially
        service.progressQuest(playerUuid, questId, "collect_logs", 2);
        assertThat(prog.getQuestState(questId).getCount("collect_logs")).isEqualTo(2);
        assertThat(prog.getQuestState(questId).getStatus()).isEqualTo(QuestProgressState.Status.IN_PROGRESS);

        // Complete by delivering remaining log
        service.progressQuest(playerUuid, questId, "collect_logs", 1);
        assertThat(prog.getQuestState(questId).getStatus()).isEqualTo(QuestProgressState.Status.COMPLETED);

        // Reward delivered!
        assertThat(prog.getFactionScore(factionId, 500)).isEqualTo(650);
    }
}
