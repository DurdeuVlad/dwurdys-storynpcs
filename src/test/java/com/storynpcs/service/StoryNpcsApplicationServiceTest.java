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
    void shouldExposeSpeakerNameAndOptionHintsInDialogueView() {
        UUID playerUuid = UUID.randomUUID();

        NamespacedId factionId = NamespacedId.of("storynpcs:knights");
        registry.registerFaction(new Faction(factionId, "Knights", 1000, 500, 1500));

        NamespacedId questId = NamespacedId.of("storynpcs:kill_dragons");
        Quest quest = new Quest(questId, "Kill Dragons");
        quest.setObjectives(List.of(new QuestObjective("kill_drake", QuestObjective.Type.KILL_ENTITY, "minecraft:ender_dragon", 1)));
        registry.registerQuest(quest);

        NamespacedId dialogueId = NamespacedId.of("storynpcs:commander_dialogue");
        DialogueGraph graph = new DialogueGraph(dialogueId, "Commander Dialogue", "greeting");

        DialogueNode greeting = new DialogueNode("greeting", "What report do you bring?");
        DialogueNode end = new DialogueNode("end", "Dismissed!");

        DialogueEdge orders = new DialogueEdge("Ask for orders", "end");
        orders.getActions().add(new DialogueAction(DialogueAction.Type.START_QUEST, questId.toString(), ""));
        orders.getActions().add(new DialogueAction(DialogueAction.Type.ADJUST_FACTION, factionId.toString(), "-50"));
        greeting.addOption(orders);

        DialogueEdge gift = new DialogueEdge("Hand over supplies", "end");
        gift.getActions().add(new DialogueAction(DialogueAction.Type.GIVE_ITEM, "minecraft:golden_apple", "1"));
        greeting.addOption(gift);

        greeting.addOption(new DialogueEdge("Nothing to report", "end")); // no actions -> empty hint

        graph.addNode(greeting);
        graph.addNode(end);
        registry.registerDialogue(graph);

        DialogueView view = service.startDialogue(playerUuid, dialogueId);

        // No entity in unit tests -> speaker falls back to graph title
        assertThat(view.npcName()).isEqualTo("Commander Dialogue");
        // Hints are parallel to options, derived from edge actions
        assertThat(view.optionHints()).containsExactly(
                "Quest: Kill Dragons, Reputation: Knights -50",
                "Item: golden apple",
                "");
    }

    @Test
    void shouldPreferPerNodeSpeakerOverrideInDialogueView() {
        UUID playerUuid = UUID.randomUUID();

        NamespacedId dialogueId = NamespacedId.of("storynpcs:mara_dialogue");
        DialogueGraph graph = new DialogueGraph(dialogueId, "Fallback Title", "greeting");

        DialogueNode greeting = new DialogueNode("greeting", "Welcome in, traveler.");
        greeting.setSpeaker("Innkeeper Mara");
        DialogueNode next = new DialogueNode("next", "Need a room?");
        greeting.addOption(new DialogueEdge("Go on", "next"));
        graph.addNode(greeting);
        graph.addNode(next);
        registry.registerDialogue(graph);

        DialogueView view = service.startDialogue(playerUuid, dialogueId);
        assertThat(view.npcName()).isEqualTo("Innkeeper Mara");

        // Advancing to a node without an override falls back to the title again
        DialogueView view2 = service.chooseDialogueOption(playerUuid, 0);
        assertThat(view2.nodeId()).isEqualTo("next");
        assertThat(view2.npcName()).isEqualTo("Fallback Title");
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

    @Test
    void shouldSafelyHandleOutOfBoundsDialogueOptionWithoutException() {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId dialogueId = NamespacedId.of("storynpcs:simple_dialogue");
        DialogueGraph graph = new DialogueGraph(dialogueId, "Simple", "start");
        DialogueNode start = new DialogueNode("start", "Choose wisely.");
        start.addOption(new DialogueEdge("Option 1", "end"));
        graph.addNode(start);
        graph.addNode(new DialogueNode("end", "Done."));
        registry.registerDialogue(graph);

        service.startDialogue(playerUuid, dialogueId);

        // Negative index
        DialogueView viewNeg = service.chooseDialogueOption(playerUuid, -1);
        assertThat(viewNeg.isTerminal()).isTrue();

        // Restart and try out of bounds positive index
        service.startDialogue(playerUuid, dialogueId);
        DialogueView viewHigh = service.chooseDialogueOption(playerUuid, 999);
        assertThat(viewHigh.isTerminal()).isTrue();
    }

    @Test
    void shouldSafelyHandleCorruptActionOrConditionWithoutCrashing() {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId dialogueId = NamespacedId.of("storynpcs:faulty_dialogue");
        DialogueGraph graph = new DialogueGraph(dialogueId, "Faulty", "start");
        DialogueNode start = new DialogueNode("start", "Here is your task.");
        DialogueEdge edge = new DialogueEdge("Proceed", "end");
        // Malformed action with nonexistent quest and non-numeric faction delta
        edge.setActions(List.of(
                new DialogueAction(DialogueAction.Type.START_QUEST, "storynpcs:ghost_quest", ""),
                new DialogueAction(DialogueAction.Type.ADJUST_FACTION, "storynpcs:ghost_faction", "not_a_number")
        ));
        // Condition with malformed value
        edge.setConditions(List.of(
                new DialogueCondition(DialogueCondition.Type.FACTION_POINTS, "storynpcs:unknown", ">=", "not_int")
        ));
        start.addOption(edge);
        graph.addNode(start);
        graph.addNode(new DialogueNode("end", "End."));
        registry.registerDialogue(graph);

        service.startDialogue(playerUuid, dialogueId);
        // Condition fails safely without throwing NumberFormatException
        DialogueView view = service.chooseDialogueOption(playerUuid, 0);
        // Since condition failed, available options was 0, so option 0 is out of bounds and safely closes
        assertThat(view.isTerminal()).isTrue();
    }

    @Test
    void shouldNotDuplicateQuestRewardsOnRepeatedCompletion() {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId factionId = NamespacedId.of("storynpcs:town_guard");
        Faction faction = new Faction(factionId, "Town Guard", 0, -500, 500);
        registry.registerFaction(faction);

        NamespacedId questId = NamespacedId.of("storynpcs:one_time_bounty");
        Quest quest = new Quest(questId, "Bounty");
        quest.setRepeatType(Quest.RepeatType.ONCE);
        quest.setRewards(List.of(new QuestReward(QuestReward.Type.FACTION_POINTS, factionId.toString(), 100)));
        registry.registerQuest(quest);

        // First start and complete
        service.startQuest(playerUuid, questId);
        PlayerProgression prog = progressionRepository.getOrCreate(playerUuid);
        assertThat(prog.getQuestState(questId).getStatus()).isEqualTo(QuestProgressState.Status.IN_PROGRESS);

        service.completeQuest(playerUuid, questId);
        assertThat(prog.getQuestState(questId).getStatus()).isEqualTo(QuestProgressState.Status.COMPLETED);
        assertThat(prog.getFactionScore(factionId, 0)).isEqualTo(100);

        // Repeated completeQuest call must not duplicate rewards (VULN-22)
        service.completeQuest(playerUuid, questId);
        assertThat(prog.getFactionScore(factionId, 0)).isEqualTo(100);

        // Non-repeatable quest cannot be restarted once completed
        service.startQuest(playerUuid, questId);
        assertThat(prog.getQuestState(questId).getStatus()).isEqualTo(QuestProgressState.Status.COMPLETED);
    }

    @Test
    void shouldClampFactionPointsOnArithmeticOverflow() {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId factionId = NamespacedId.of("storynpcs:town_guard");
        Faction faction = new Faction(factionId, "Town Guard", 1000, 500, 1500);
        registry.registerFaction(faction);

        // Adjust by huge number -> clamped to 100,000, not overflowed to negative
        service.adjustFactionPoints(playerUuid, factionId, 2_000_000_000);
        PlayerProgression prog = progressionRepository.getOrCreate(playerUuid);
        assertThat(prog.getFactionScore(factionId, 1000)).isEqualTo(100_000);

        // Adjust negatively -> clamped to -100,000
        service.adjustFactionPoints(playerUuid, factionId, -500_000);
        assertThat(prog.getFactionScore(factionId, 1000)).isEqualTo(-100_000);
    }

    @Test
    void shouldCloseDialogueImmediatelyWhenActionIsCloseDialogue() {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId dialogueId = NamespacedId.of("storynpcs:close_action_test");
        DialogueGraph graph = new DialogueGraph(dialogueId, "Close Test", "start");
        DialogueNode start = new DialogueNode("start", "Goodbye!");
        DialogueEdge closeEdge = new DialogueEdge("Leave", "next_node");
        closeEdge.setActions(List.of(new DialogueAction(DialogueAction.Type.CLOSE_DIALOGUE, "", "")));
        start.addOption(closeEdge);
        graph.addNode(start);
        graph.addNode(new DialogueNode("next_node", "Should not see this"));
        registry.registerDialogue(graph);

        service.startDialogue(playerUuid, dialogueId);
        DialogueView view = service.chooseDialogueOption(playerUuid, 0);

        assertThat(view.isTerminal()).isTrue();
        assertThat(service.getActiveSession(playerUuid)).isEmpty();
    }

    @Test
    void shouldDeliverRemainingRewardsEvenIfOneFails() {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId validFactionId = NamespacedId.of("storynpcs:valid_faction");
        Faction faction = new Faction(validFactionId, "Valid", 0, -100, 100);
        registry.registerFaction(faction);

        NamespacedId questId = NamespacedId.of("storynpcs:mixed_rewards");
        Quest quest = new Quest(questId, "Mixed Rewards");
        quest.setRewards(List.of(
                new QuestReward(QuestReward.Type.FACTION_POINTS, "storynpcs:unknown_faction", 50),
                new QuestReward(QuestReward.Type.FACTION_POINTS, validFactionId.toString(), 100)
        ));
        registry.registerQuest(quest);

        service.startQuest(playerUuid, questId);
        service.completeQuest(playerUuid, questId);

        PlayerProgression prog = progressionRepository.getOrCreate(playerUuid);
        assertThat(prog.getQuestState(questId).getStatus()).isEqualTo(QuestProgressState.Status.COMPLETED);
        assertThat(prog.getFactionScore(validFactionId, 0)).isEqualTo(100);
    }

    @Test
    void saveDialogueShouldValidatePersistAndUpdateRegistry() {
        NamespacedId dialogueId = NamespacedId.of("storynpcs:editor_saved");
        DialogueGraph original = new DialogueGraph(dialogueId, "Original", "a");
        original.addNode(new DialogueNode("a", "Old text."));
        registry.registerDialogue(original);

        // Admin edits: new text on 'a' plus a new node + edge
        DialogueGraph edited = new DialogueGraph(dialogueId, "Edited", "a");
        DialogueNode a = new DialogueNode("a", "New text.");
        a.addOption(new DialogueEdge("Continue", "b"));
        edited.addNode(a);
        edited.addNode(new DialogueNode("b", "Second node."));

        var result = service.saveDialogue(dialogueId, edited);

        assertThat(result.hasErrors()).isFalse();
        DialogueGraph live = registry.getDialogue(dialogueId).orElseThrow();
        assertThat(live.getTitle()).isEqualTo("Edited");
        assertThat(live.getNode("a").orElseThrow().getText()).isEqualTo("New text.");
        assertThat(live.getNode("b")).isPresent();
    }

    @Test
    void saveDialogueShouldRejectDanglingEdgeTargetAndNotMutateRegistry() {
        NamespacedId dialogueId = NamespacedId.of("storynpcs:broken_save");
        DialogueGraph original = new DialogueGraph(dialogueId, "Original", "a");
        original.addNode(new DialogueNode("a", "Old text."));
        registry.registerDialogue(original);

        DialogueGraph broken = new DialogueGraph(dialogueId, "Broken", "a");
        DialogueNode a = new DialogueNode("a", "Text.");
        a.addOption(new DialogueEdge("Nowhere", "missing_node"));
        broken.addNode(a);

        var result = service.saveDialogue(dialogueId, broken);

        assertThat(result.hasErrors()).isTrue();
        // Live registry untouched — still the original graph
        DialogueGraph live = registry.getDialogue(dialogueId).orElseThrow();
        assertThat(live.getTitle()).isEqualTo("Original");
        assertThat(live.getNode("a").orElseThrow().getOptions()).isEmpty();
    }

    @Test
    void saveDialogueShouldRejectIdMismatchAndMissingEntryNode() {
        NamespacedId dialogueId = NamespacedId.of("storynpcs:mismatch");
        DialogueGraph wrong = new DialogueGraph(NamespacedId.of("storynpcs:other"), "Wrong", "a");
        wrong.addNode(new DialogueNode("a", "Text."));

        var mismatch = service.saveDialogue(dialogueId, wrong);
        assertThat(mismatch.hasErrors()).isTrue();
        assertThat(registry.getDialogue(dialogueId)).isEmpty();

        DialogueGraph noEntry = new DialogueGraph(dialogueId, "NoEntry", "ghost");
        noEntry.addNode(new DialogueNode("a", "Text."));
        var entryResult = service.saveDialogue(dialogueId, noEntry);
        assertThat(entryResult.hasErrors()).isTrue();
        assertThat(registry.getDialogue(dialogueId)).isEmpty();
    }

    @Test
    void saveDialogueShouldWriteYamlFileWhenLoaderIsBound() throws Exception {
        com.storynpcs.yaml.YamlDefinitionLoader loader =
                new com.storynpcs.yaml.YamlDefinitionLoader(registry);
        loader.loadDirectory(tempDir); // binds root path for persistence
        service.setLoader(loader);

        NamespacedId dialogueId = NamespacedId.of("storynpcs:persisted_dialogue");
        DialogueGraph graph = new DialogueGraph(dialogueId, "Persisted", "a");
        DialogueNode a = new DialogueNode("a", "Saved from editor.");
        a.addOption(new DialogueEdge("Go", "b"));
        graph.addNode(a);
        graph.addNode(new DialogueNode("b", "End."));

        var result = service.saveDialogue(dialogueId, graph);
        assertThat(result.hasErrors()).isFalse();

        Path expected = tempDir.resolve("dialogues").resolve("persisted_dialogue.yaml");
        assertThat(expected).exists();
        // And it loads back cleanly
        DefinitionRegistry fresh = new DefinitionRegistry();
        var loadResult = new com.storynpcs.yaml.YamlDefinitionLoader(fresh).loadDirectory(tempDir);
        assertThat(loadResult.isValid()).isTrue();
        assertThat(fresh.getDialogue(dialogueId)).isPresent();
    }

    @Test
    void saveNpcShouldRegisterDefinitionAndWriteLoadableYaml() throws Exception {
        com.storynpcs.yaml.YamlDefinitionLoader loader =
                new com.storynpcs.yaml.YamlDefinitionLoader(registry);
        loader.loadDirectory(tempDir); // binds root path for persistence
        service.setLoader(loader);

        NamespacedId npcId = NamespacedId.of("storynpcs:new_recruit");
        NpcDefinition def = new NpcDefinition(npcId, "New Recruit");

        var result = service.saveNpc(def);

        assertThat(result.hasErrors()).isFalse();
        assertThat(registry.getNpc(npcId)).isPresent();
        assertThat(registry.getNpc(npcId).get().getDisplay().getName()).isEqualTo("New Recruit");

        Path expected = tempDir.resolve("npcs").resolve("new_recruit.yaml");
        assertThat(expected).exists();
        // The scaffolded file is itself valid YAML that loads back cleanly
        DefinitionRegistry fresh = new DefinitionRegistry();
        var loadResult = new com.storynpcs.yaml.YamlDefinitionLoader(fresh).loadDirectory(tempDir);
        assertThat(loadResult.isValid()).isTrue();
        assertThat(fresh.getNpc(npcId)).isPresent();
        assertThat(fresh.getNpc(npcId).get().getDisplay().getName()).isEqualTo("New Recruit");
    }

    @Test
    void saveNpcShouldRejectMissingIdAndDanglingReferences() {
        var noId = service.saveNpc(new NpcDefinition(null, "No Id"));
        assertThat(noId.hasErrors()).isTrue();
        assertThat(noId.formatReport()).contains("NPC_ID_MISSING");

        NamespacedId npcId = NamespacedId.of("storynpcs:dangling");
        NpcDefinition def = new NpcDefinition(npcId, "Dangling");
        def.setDialogueId(NamespacedId.of("storynpcs:ghost_dialogue"));

        var result = service.saveNpc(def);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.formatReport()).contains("REF_NPC_DIALOGUE_MISSING");
        assertThat(registry.getNpc(npcId)).isEmpty();
    }

    @Test
    void saveFactionShouldPersistAndRegister() {
        NamespacedId id = NamespacedId.of("storynpcs:river_pirates");
        var result = service.saveFaction(new Faction(id, "River Pirates", 800, 300, 1200));

        assertThat(result.hasErrors()).isFalse();
        assertThat(registry.getFaction(id)).isPresent();
        assertThat(registry.getFaction(id).get().getName()).isEqualTo("River Pirates");
    }

    @Test
    void saveFactionShouldRejectMissingId() {
        Faction noId = new Faction();
        noId.setName("No Id");
        var result = service.saveFaction(noId);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.formatReport()).contains("FACTION_ID_MISSING");
    }

    @Test
    void saveFactionShouldRejectInvertedThresholds() {
        NamespacedId id = NamespacedId.of("storynpcs:bad_thresholds");
        var result = service.saveFaction(new Faction(id, "Broken", 1000, 1500, 500));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.formatReport()).contains("FACTION_THRESHOLDS_INCONSISTENT");
        assertThat(registry.getFaction(id)).isEmpty();
    }

    @Test
    void createFactionShouldScaffoldDefaultsAndRejectDuplicates() {
        NamespacedId id = NamespacedId.of("storynpcs:new_faction");

        var created = service.createFaction(id, "The Syndicate");
        assertThat(created.hasErrors()).isFalse();
        Faction f = registry.getFaction(id).orElseThrow();
        assertThat(f.getDefaultPoints()).isEqualTo(1000);
        assertThat(f.getHostileThreshold()).isEqualTo(500);
        assertThat(f.getFriendlyThreshold()).isEqualTo(1500);

        var dup = service.createFaction(id, "Again");
        assertThat(dup.hasErrors()).isTrue();
        assertThat(dup.formatReport()).contains("FACTION_ALREADY_EXISTS");
    }

    @Test
    void deleteQuestShouldRemoveAndReportDialogueReferences() {
        NamespacedId questId = NamespacedId.of("storynpcs:deletable");
        service.createQuest(questId, "Deletable");
        assertThat(registry.getQuest(questId)).isPresent();

        // dialogue with a START_QUEST action targeting the quest
        NamespacedId dlgId = NamespacedId.of("storynpcs:quest_ref_dlg");
        DialogueGraph g = new DialogueGraph(dlgId, "Ref Dlg", "start");
        DialogueNode node = new DialogueNode("start", "hi");
        DialogueEdge edge = new DialogueEdge("opt", "start");
        edge.getActions().add(new com.storynpcs.domain.dialogue.DialogueAction(
                com.storynpcs.domain.dialogue.DialogueAction.Type.START_QUEST, questId.toString(), ""));
        node.getOptions().add(edge);
        g.getNodes().put("start", node);
        registry.registerDialogue(g);

        assertThat(service.findDialoguesStartingQuest(questId)).containsExactly(dlgId);
        assertThat(service.deleteQuest(questId)).isTrue();
        assertThat(registry.getQuest(questId)).isEmpty();
        assertThat(service.deleteQuest(questId)).isFalse(); // idempotent-miss
    }
}
