package com.storynpcs.service;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.api.event.CanonicalMutationEvent;
import com.storynpcs.api.event.StoryNpcsEvent;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.dialogue.*;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.npc.*;
import com.storynpcs.domain.progression.PlayerProgression;
import com.storynpcs.domain.progression.QuestProgressState;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.domain.quest.QuestObjective;
import com.storynpcs.domain.quest.QuestReward;
import com.storynpcs.editor.PayloadBoundRequestId;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.yaml.DefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    void shouldRejectInvalidRewardBeforeAnyRewardIsApplied() {
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
        QuestCompletionResult result = service.completeQuest(playerUuid, questId);

        PlayerProgression prog = progressionRepository.getOrCreate(playerUuid);
        assertThat(result.outcome()).isEqualTo(QuestCompletionResult.Outcome.REJECTED);
        assertThat(result.code()).isEqualTo("FACTION_NOT_FOUND");
        assertThat(prog.getQuestState(questId).getStatus()).isEqualTo(QuestProgressState.Status.IN_PROGRESS);
        assertThat(prog.getFactionScore(validFactionId, 0)).isEqualTo(0);
    }

    @Test
    void shouldRejectNonAtomicCommandRewardBeforeQuestCompletion() {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:command_reward");
        Quest quest = new Quest(questId, "Command Reward");
        quest.setRewards(List.of(new QuestReward(QuestReward.Type.COMMAND, "say %player%", 1)));
        registry.registerQuest(quest);

        service.startQuest(playerUuid, questId);
        QuestCompletionResult result = service.completeQuest(playerUuid, questId);

        assertThat(result.outcome()).isEqualTo(QuestCompletionResult.Outcome.REJECTED);
        assertThat(result.code()).isEqualTo("NON_ATOMIC_COMMAND_REWARD");
        assertThat(progressionRepository.getOrCreate(playerUuid).getQuestState(questId).getStatus())
                .isEqualTo(QuestProgressState.Status.IN_PROGRESS);
        assertThat(publishedEvents).noneMatch(event -> event instanceof com.storynpcs.api.event.QuestCompleteEvent);
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
    void acknowledgedNpcWriteFailureCanBeRetriedWithFreshIdWhileLostResponseRetryKeepsItsId() throws Exception {
        com.storynpcs.yaml.YamlDefinitionLoader loader =
                new com.storynpcs.yaml.YamlDefinitionLoader(registry);
        assertThat(loader.loadDirectory(tempDir).isValid()).isTrue();
        service.setLoader(loader);

        NamespacedId npcId = NamespacedId.of("storynpcs:retry_after_write_failure");
        service.createNpc(new NpcDefinition(npcId, "Before"));
        NpcDefinition replacement = new NpcDefinition(npcId, "After");
        String payload = NpcDefinitionSerde.toJson(replacement);
        PayloadBoundRequestId requestIds = new PayloadBoundRequestId();
        UUID firstId = requestIds.forPayload(payload);
        MutationRequest firstRequest = new MutationRequest(
                "npc.replace", "player:" + UUID.randomUUID(), "npc.edit", npcId, 0L, firstId, 2);

        Path blockedTypeDirectory = tempDir.resolve("npcs");
        Files.writeString(blockedTypeDirectory, "temporary write obstruction");
        CanonicalMutationResult rejected = service.replaceNpc(firstRequest, replacement);
        assertThat(rejected.applied()).isFalse();
        assertThat(rejected.diagnostics().formatReport()).contains("PERSIST_WRITE_FAILED");
        assertThat(registry.getNpc(npcId).orElseThrow().getDisplay().getName()).isEqualTo("Before");

        // A lost response retries exactly the same request, even if storage recovered meanwhile.
        Files.delete(blockedTypeDirectory);
        assertThat(requestIds.forPayload(payload)).isEqualTo(firstId);
        CanonicalMutationResult lostResponseRetry = service.replaceNpc(firstRequest, replacement);
        assertThat(lostResponseRetry.applied()).isFalse();
        assertThat(lostResponseRetry.duplicate()).isTrue();
        assertThat(registry.getNpc(npcId).orElseThrow().getDisplay().getName()).isEqualTo("Before");

        // Once that rejection is acknowledged, the next click is a new logical attempt.
        assertThat(requestIds.acknowledge(firstId)).isTrue();
        UUID retryId = requestIds.forPayload(payload);
        assertThat(retryId).isNotEqualTo(firstId);
        MutationRequest retryRequest = new MutationRequest(
                "npc.replace", firstRequest.actorType(), "npc.edit", npcId, 0L, retryId, 2);
        CanonicalMutationResult retried = service.replaceNpc(retryRequest, replacement);

        assertThat(retried.applied()).isTrue();
        assertThat(retried.duplicate()).isFalse();
        assertThat(service.currentRevision("npc", npcId)).isEqualTo(1L);
        assertThat(registry.getNpc(npcId).orElseThrow().getDisplay().getName()).isEqualTo("After");
        DefinitionRegistry diskRegistry = new DefinitionRegistry();
        assertThat(new com.storynpcs.yaml.YamlDefinitionLoader(diskRegistry).loadDirectory(tempDir).isValid()).isTrue();
        assertThat(diskRegistry.getNpc(npcId).orElseThrow().getDisplay().getName()).isEqualTo("After");
    }

    @Test
    void deletingLegacyFlatQuestClearsSourceIndexAndAllowsRecreation() throws Exception {
        NamespacedId questId = NamespacedId.of("storynpcs:legacy_flat_quest");
        Quest original = new Quest(questId, "Legacy Flat Quest");
        original.setObjectives(List.of(new QuestObjective(
                "objective_1", QuestObjective.Type.CUSTOM, "describe_the_objective", 1)));
        Path typedFile = new com.storynpcs.yaml.YamlDefinitionWriter().writeDefinition(
                tempDir, "quests", "legacy_flat_quest", original);
        Path legacyFlatFile = tempDir.resolve("quest_legacy_flat_quest.yaml");
        Files.move(typedFile, legacyFlatFile);

        com.storynpcs.yaml.YamlDefinitionLoader loader =
                new com.storynpcs.yaml.YamlDefinitionLoader(registry);
        var loaded = loader.loadDirectory(tempDir);
        assertThat(loaded.isValid()).isTrue();
        service.setLoader(loader);
        assertThat(loader.getDefinitionFiles("quests", questId))
                .containsExactly(legacyFlatFile.toAbsolutePath().normalize());

        assertThat(service.deleteQuest(questId)).isTrue();
        assertThat(legacyFlatFile).doesNotExist();
        assertThat(loader.getDefinitionFiles("quests", questId)).isEmpty();

        var recreated = service.createQuest(questId, "Recreated Quest");
        assertThat(recreated.hasErrors()).isFalse();
        Path canonicalFile = tempDir.resolve("quests").resolve("legacy_flat_quest.yaml");
        assertThat(canonicalFile).exists();
        assertThat(loader.getDefinitionFiles("quests", questId))
                .containsExactly(canonicalFile.toAbsolutePath().normalize());

        DefinitionRegistry reloadedRegistry = new DefinitionRegistry();
        var reloaded = new com.storynpcs.yaml.YamlDefinitionLoader(reloadedRegistry).loadDirectory(tempDir);
        assertThat(reloaded.isValid()).isTrue();
        assertThat(reloadedRegistry.getQuest(questId)).isPresent();
    }

    @Test
    void failedQuestFileDeleteKeepsDefinitionRegisteredAndCanonicalDeleteRejected() throws Exception {
        NamespacedId questId = NamespacedId.of("storynpcs:delete_failure_keeps_quest");
        Quest quest = new Quest(questId, "Delete Failure Quest");
        quest.setObjectives(List.of(new QuestObjective(
                "objective_1", QuestObjective.Type.CUSTOM, "describe_the_objective", 1)));
        Path questFile = new com.storynpcs.yaml.YamlDefinitionWriter().writeDefinition(
                tempDir, "quests", "delete_failure_keeps_quest", quest);

        com.storynpcs.yaml.YamlDefinitionLoader failingLoader =
                new com.storynpcs.yaml.YamlDefinitionLoader(registry) {
                    @Override
                    public synchronized boolean deleteDefinitionFile(String type, NamespacedId id)
                            throws java.io.IOException {
                        throw new java.io.IOException("forced delete failure for regression test");
                    }
                };
        assertThat(failingLoader.loadDirectory(tempDir).isValid()).isTrue();
        service.setLoader(failingLoader);
        MutationRequest request = new MutationRequest(
                "quest.delete", "command", "quest.delete", questId, 0L, UUID.randomUUID());

        CanonicalMutationResult result = service.deleteQuest(request);

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().formatReport()).contains("DEFINITION_DELETE_FAILED");
        assertThat(service.currentRevision("quest", questId)).isZero();
        assertThat(registry.getQuest(questId)).isPresent();
        assertThat(questFile).exists();
        assertThat(failingLoader.getDefinitionFiles("quests", questId))
                .containsExactly(questFile.toAbsolutePath().normalize());
    }

    @Test
    void concurrentQuestSaveAndDeleteLeaveRegistryAndYamlInAgreement() throws Exception {
        com.storynpcs.yaml.YamlDefinitionLoader loader =
                new com.storynpcs.yaml.YamlDefinitionLoader(registry);
        assertThat(loader.loadDirectory(tempDir).isValid()).isTrue();
        service.setLoader(loader);

        NamespacedId questId = NamespacedId.of("storynpcs:concurrent_service_quest");
        assertThat(service.createQuest(questId, "Initial Quest").hasErrors()).isFalse();
        Path questFile = tempDir.resolve("quests").resolve("concurrent_service_quest.yaml");

        var executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 24; iteration++) {
                if (registry.getQuest(questId).isEmpty()) {
                    assertThat(service.createQuest(questId, "Restored Quest").hasErrors()).isFalse();
                }
                Quest replacement = new Quest(questId, "Concurrent update " + iteration);
                replacement.setObjectives(List.of(new QuestObjective(
                        "objective_1", QuestObjective.Type.CUSTOM, "describe_the_objective", 1)));
                CountDownLatch start = new CountDownLatch(1);
                var deletion = executor.submit(() -> {
                    start.await();
                    return service.deleteQuest(questId);
                });
                var save = executor.submit(() -> {
                    start.await();
                    return service.saveQuest(replacement);
                });
                start.countDown();

                assertThat(deletion.get()).isTrue();
                assertThat(save.get().hasErrors()).isFalse();
                boolean registered = registry.getQuest(questId).isPresent();
                assertThat(Files.exists(questFile)).isEqualTo(registered);
                assertThat(loader.getDefinitionFiles("quests", questId)).hasSize(registered ? 1 : 0);

                DefinitionRegistry diskRegistry = new DefinitionRegistry();
                var diskLoad = new com.storynpcs.yaml.YamlDefinitionLoader(diskRegistry).loadDirectory(tempDir);
                assertThat(diskLoad.isValid()).isTrue();
                assertThat(diskRegistry.getQuest(questId).isPresent()).isEqualTo(registered);
            }
        } finally {
            executor.shutdownNow();
        }
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
    void adapterMutationsUseDetachedCopiesAndDoNotPartiallyMutateLiveState() {
        NamespacedId npcId = NamespacedId.of("storynpcs:canonical_mutation");
        service.createNpc(new NpcDefinition(npcId, "Original"));

        var rejected = service.mutateNpc(npcId, working -> {
            working.getDisplay().setName("Rejected");
            working.setDialogueId(NamespacedId.of("storynpcs:missing"));
        });

        assertThat(rejected.hasErrors()).isTrue();
        assertThat(registry.getNpc(npcId).orElseThrow().getDisplay().getName()).isEqualTo("Original");

        var accepted = service.mutateNpc(npcId, working -> working.getDisplay().setName("Committed"));
        assertThat(accepted.hasErrors()).isFalse();
        assertThat(registry.getNpc(npcId).orElseThrow().getDisplay().getName()).isEqualTo("Committed");
    }

    @Test
    void typedNpcMutationsRejectUnboundReplayAndStaleRevisions() {
        NamespacedId npcId = NamespacedId.of("storynpcs:replay_safe");
        service.createNpc(new NpcDefinition(npcId, "Original"));
        UUID requestId = UUID.randomUUID();
        MutationRequest request = new MutationRequest(
                "npc.mutate", "command", "npc.edit", npcId, 0L, requestId);

        CanonicalMutationResult first = service.mutateNpc(request,
                working -> working.getDisplay().setName("Applied once"));
        CanonicalMutationResult replay = service.mutateNpc(request,
                working -> working.getDisplay().setName("Applied twice"));

        assertThat(first.applied()).isTrue();
        assertThat(first.duplicate()).isFalse();
        assertThat(first.revision()).isEqualTo(1L);
        assertThat(first.events()).containsExactly("npc.mutate:applied");
        assertThat(first.recoveryOutcome()).isEqualTo("COMMITTED");
        assertThat(replay.applied()).isFalse();
        assertThat(replay.duplicate()).isFalse();
        assertThat(replay.diagnostics().formatReport()).contains("IDEMPOTENCY_PAYLOAD_UNBOUND");
        assertThat(registry.getNpc(npcId).orElseThrow().getDisplay().getName()).isEqualTo("Applied once");
        assertThat(publishedEvents.stream().filter(CanonicalMutationEvent.class::isInstance)).hasSize(2);
        CanonicalMutationEvent event = (CanonicalMutationEvent) publishedEvents.stream()
                .filter(CanonicalMutationEvent.class::isInstance).findFirst().orElseThrow();
        assertThat(event.requestId()).isEqualTo(requestId);
        assertThat(event.outcome()).isEqualTo("COMMITTED");

        MutationRequest staleRequest = new MutationRequest(
                "npc.mutate", "packet", "npc.edit", npcId, 0L, UUID.randomUUID());
        CanonicalMutationResult stale = service.mutateNpc(staleRequest,
                working -> working.getDisplay().setName("Must not apply"));

        assertThat(stale.applied()).isFalse();
        assertThat(stale.duplicate()).isFalse();
        assertThat(stale.diagnostics().formatReport()).contains("STALE_REVISION");
        assertThat(registry.getNpc(npcId).orElseThrow().getDisplay().getName()).isEqualTo("Applied once");
    }

    @Test
    void canonicalMutationRejectsUnprovenPlayerAuthorityBeforeOperationRuns() {
        NamespacedId npcId = NamespacedId.of("storynpcs:authorization_guard");
        service.createNpc(new NpcDefinition(npcId, "Original"));
        MutationRequest request = new MutationRequest(
                "npc.mutate", "player:without-proof", "npc.edit", npcId, 0L, UUID.randomUUID());

        CanonicalMutationResult result = service.mutateNpc(request,
                working -> working.getDisplay().setName("Must not apply"));

        assertThat(result.applied()).isFalse();
        assertThat(result.recoveryOutcome()).isEqualTo("REJECTED_AUTHORIZATION");
        assertThat(result.diagnostics().formatReport()).contains("PERMISSION_DENIED");
        assertThat(registry.getNpc(npcId).orElseThrow().getDisplay().getName()).isEqualTo("Original");
    }

    @Test
    void canonicalMutationTurnsUnknownTargetAndThrownAdapterFailureIntoDiagnostics() {
        NamespacedId missingId = NamespacedId.of("storynpcs:missing_target");
        MutationRequest request = new MutationRequest(
                "npc.mutate", "command", "npc.edit", missingId, 0L, UUID.randomUUID());

        CanonicalMutationResult result = service.mutateNpc(request,
                working -> working.getDisplay().setName("unreachable"));

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().formatReport()).contains("CANONICAL_MUTATION_REJECTED");
    }

    @Test
    void canonicalMutationBindsRequestIdAndScopesRevisionsByDefinitionKind() {
        NamespacedId sharedId = NamespacedId.of("storynpcs:shared_revision_id");
        service.createNpc(new NpcDefinition(sharedId, "NPC"));
        assertThat(service.createQuest(sharedId, "Quest").hasErrors()).isFalse();

        MutationRequest npcRequest = new MutationRequest(
                "npc.mutate", "command", "npc.edit", sharedId, 0L, UUID.randomUUID());
        assertThat(service.mutateNpc(npcRequest,
                working -> working.getDisplay().setName("NPC updated")).applied()).isTrue();

        MutationRequest reusedId = new MutationRequest(
                "npc.mutate", "packet", "npc.edit", sharedId, 1L, UUID.randomUUID());
        UUID requestId = reusedId.requestId();
        CanonicalMutationResult first = service.mutateNpc(reusedId,
                working -> working.getDisplay().setName("Second update"));
        MutationRequest conflictingRequest = new MutationRequest(
                "quest.mutate", "packet", "quest.edit", sharedId, 0L, requestId);
        CanonicalMutationResult conflicting = service.mutateQuest(
                conflictingRequest, quest -> quest.setCategory("Must not be applied"));

        assertThat(first.applied()).isTrue();
        assertThat(conflicting.applied()).isFalse();
        assertThat(conflicting.diagnostics().formatReport()).contains("REQUEST_ID_REUSE");
        assertThat(registry.getQuest(sharedId)).isPresent();
    }

    @Test
    void deletionKeepsRevisionTombstoneSoStaleRequestsCannotEditReplacement() {
        NamespacedId npcId = NamespacedId.of("storynpcs:replacement_guard");
        service.createNpc(new NpcDefinition(npcId, "First"));
        MutationRequest staleAfterReplacement = new MutationRequest(
                "npc.mutate", "packet", "npc.edit", npcId, 0L, UUID.randomUUID());

        assertThat(service.deleteNpc(npcId)).isTrue();
        service.createNpc(new NpcDefinition(npcId, "Replacement"));

        CanonicalMutationResult result = service.mutateNpc(staleAfterReplacement,
                working -> working.getDisplay().setName("Must not overwrite replacement"));

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().formatReport()).contains("STALE_REVISION");
        assertThat(registry.getNpc(npcId).orElseThrow().getDisplay().getName()).isEqualTo("Replacement");
    }

    @Test
    void typedDeleteIsReplaySafeAndRevisionChecked() {
        NamespacedId npcId = NamespacedId.of("storynpcs:typed_delete");
        service.createNpc(new NpcDefinition(npcId, "Delete me"));
        MutationRequest request = new MutationRequest(
                "npc.delete", "command", "npc.delete", npcId, 0L, UUID.randomUUID());

        CanonicalMutationResult first = service.deleteNpc(request);
        CanonicalMutationResult replay = service.deleteNpc(request);

        assertThat(first.applied()).isTrue();
        assertThat(first.revision()).isEqualTo(1L);
        assertThat(replay.duplicate()).isTrue();
        assertThat(registry.getNpc(npcId)).isEmpty();
        assertThat(publishedEvents.stream().filter(CanonicalMutationEvent.class::isInstance)).hasSize(1);
    }

    @Test
    void typedDefinitionCreationEnvelopesAllDefinitionFamilies() {
        NamespacedId npcId = NamespacedId.of("storynpcs:typed_create_npc");
        MutationRequest npcRequest = new MutationRequest(
                "npc.create", "command", "npc.mutate", npcId,
                service.currentRevision("npc", npcId), UUID.randomUUID());
        NpcDefinition npc = new NpcDefinition(npcId, "Typed NPC");
        CanonicalMutationResult npcCreated = service.createNpc(npcRequest, npc);
        CanonicalMutationResult npcReplay = service.createNpc(npcRequest, new NpcDefinition(npcId, "Typed NPC"));
        CanonicalMutationResult changedNpcReplay = service.createNpc(
                npcRequest, new NpcDefinition(npcId, "Changed Typed NPC"));
        assertThat(npcCreated.applied()).isTrue();
        assertThat(npcReplay.duplicate()).isTrue();
        assertThat(changedNpcReplay.applied()).isFalse();
        assertThat(changedNpcReplay.diagnostics().formatReport()).contains("REQUEST_PAYLOAD_MISMATCH");
        assertThat(registry.getNpc(npcId).orElseThrow().getDisplay().getName()).isEqualTo("Typed NPC");

        NamespacedId dialogueId = NamespacedId.of("storynpcs:typed_create_dialogue");
        MutationRequest dialogueRequest = new MutationRequest(
                "dialogue.create", "command", "dialogue.mutate", dialogueId,
                service.currentRevision("dialogue", dialogueId), UUID.randomUUID());
        assertThat(service.createDialogue(dialogueRequest, "Typed Dialogue").applied()).isTrue();
        CanonicalMutationResult changedDialogueReplay = service.createDialogue(dialogueRequest, "Changed Dialogue");
        assertThat(changedDialogueReplay.applied()).isFalse();
        assertThat(changedDialogueReplay.diagnostics().formatReport()).contains("REQUEST_PAYLOAD_MISMATCH");
        assertThat(registry.getDialogue(dialogueId).orElseThrow().getTitle()).isEqualTo("Typed Dialogue");
        assertThat(registry.getDialogue(dialogueId).orElseThrow().getNode("start")).isPresent();

        NamespacedId questId = NamespacedId.of("storynpcs:typed_create_quest");
        MutationRequest questRequest = new MutationRequest(
                "quest.create", "command", "quest.mutate", questId,
                service.currentRevision("quest", questId), UUID.randomUUID());
        assertThat(service.createQuest(questRequest, "Typed Quest").applied()).isTrue();
        CanonicalMutationResult changedQuestReplay = service.createQuest(questRequest, "Changed Quest");
        assertThat(changedQuestReplay.applied()).isFalse();
        assertThat(changedQuestReplay.diagnostics().formatReport()).contains("REQUEST_PAYLOAD_MISMATCH");
        assertThat(registry.getQuest(questId).orElseThrow().getTitle()).isEqualTo("Typed Quest");
        assertThat(registry.getQuest(questId)).isPresent();

        NamespacedId factionId = NamespacedId.of("storynpcs:typed_create_faction");
        MutationRequest factionRequest = new MutationRequest(
                "faction.create", "command", "faction.mutate", factionId,
                service.currentRevision("faction", factionId), UUID.randomUUID());
        assertThat(service.createFaction(factionRequest, "Typed Faction").applied()).isTrue();
        CanonicalMutationResult changedFactionReplay = service.createFaction(factionRequest, "Changed Faction");
        assertThat(changedFactionReplay.applied()).isFalse();
        assertThat(changedFactionReplay.diagnostics().formatReport()).contains("REQUEST_PAYLOAD_MISMATCH");
        assertThat(registry.getFaction(factionId).orElseThrow().getName()).isEqualTo("Typed Faction");
        assertThat(registry.getFaction(factionId)).isPresent();
    }

    @Test
    void typedReplacementReplayBindsPayloadAndRejectsDialogueTargetRedirect() throws Exception {
        com.storynpcs.yaml.YamlDefinitionLoader loader =
                new com.storynpcs.yaml.YamlDefinitionLoader(registry);
        assertThat(loader.loadDirectory(tempDir).isValid()).isTrue();
        service.setLoader(loader);

        NamespacedId npcId = NamespacedId.of("storynpcs:replace_payload_binding");
        MutationRequest createNpc = new MutationRequest(
                "npc.create", "command", "npc.mutate", npcId, 0L, UUID.randomUUID());
        assertThat(service.createNpc(createNpc, new NpcDefinition(npcId, "Original")).applied()).isTrue();
        MutationRequest replaceRequest = new MutationRequest(
                "npc.replace", "command", "npc.edit", npcId,
                service.currentRevision("npc", npcId), UUID.randomUUID());
        NpcDefinition firstReplacement = new NpcDefinition(npcId, "Replacement One");
        firstReplacement.getDisplay().setSkinPlayer("Alex");
        firstReplacement.getDisplay().setSkinSource(NpcDisplay.SkinSource.TEXTURE);
        CanonicalMutationResult first = service.replaceNpc(replaceRequest, firstReplacement);
        NpcDefinition exactNpcRetry = NpcDefinitionSerde.fromJson(NpcDefinitionSerde.toJson(firstReplacement)).orElseThrow();
        assertThat(service.replaceNpc(replaceRequest, exactNpcRetry).duplicate()).isTrue();
        firstReplacement.getDisplay().setName("Mutated after request completed");
        CanonicalMutationResult changedReplay = service.replaceNpc(
                replaceRequest, new NpcDefinition(npcId, "Replacement Two"));

        assertThat(first.applied()).isTrue();
        assertThat(changedReplay.applied()).isFalse();
        assertThat(changedReplay.diagnostics().formatReport()).contains("REQUEST_PAYLOAD_MISMATCH");
        assertThat(registry.getNpc(npcId).orElseThrow().getDisplay().getName()).isEqualTo("Replacement One");
        assertThat(registry.getNpc(npcId).orElseThrow().getDisplay().getSkinSource())
                .isEqualTo(NpcDisplay.SkinSource.TEXTURE);
        DefinitionRegistry reloaded = new DefinitionRegistry();
        assertThat(new com.storynpcs.yaml.YamlDefinitionLoader(reloaded).loadDirectory(tempDir).isValid()).isTrue();
        assertThat(reloaded.getNpc(npcId).orElseThrow().getDisplay().getName()).isEqualTo("Replacement One");
        assertThat(reloaded.getNpc(npcId).orElseThrow().getDisplay().getSkinSource())
                .isEqualTo(NpcDisplay.SkinSource.TEXTURE);

        NamespacedId dialogueId = NamespacedId.of("storynpcs:replace_dialogue_target");
        NamespacedId redirectedId = NamespacedId.of("storynpcs:redirected_dialogue_target");
        MutationRequest createDialogue = new MutationRequest(
                "dialogue.create", "command", "dialogue.mutate", dialogueId, 0L, UUID.randomUUID());
        assertThat(service.createDialogue(createDialogue, "Original Dialogue").applied()).isTrue();
        MutationRequest dialogueReplaceRequest = new MutationRequest(
                "dialogue.replace", "command", "dialogue.edit", dialogueId,
                service.currentRevision("dialogue", dialogueId), UUID.randomUUID());
        DialogueGraph redirected = new DialogueGraph(redirectedId, "Redirected", "start");
        redirected.addNode(new DialogueNode("start", "Must not be written under the other ID."));

        CanonicalMutationResult redirectedReplacement = service.replaceDialogue(dialogueReplaceRequest, redirected);

        assertThat(redirectedReplacement.applied()).isFalse();
        assertThat(redirectedReplacement.diagnostics().formatReport()).contains("TARGET_ID_MISMATCH");
        assertThat(registry.getDialogue(dialogueId).orElseThrow().getTitle()).isEqualTo("Original Dialogue");
        assertThat(registry.getDialogue(redirectedId)).isEmpty();

        MutationRequest validDialogueReplaceRequest = new MutationRequest(
                "dialogue.replace", "command", "dialogue.edit", dialogueId,
                service.currentRevision("dialogue", dialogueId), UUID.randomUUID());
        DialogueGraph firstDialogueReplacement = new DialogueGraph(dialogueId, "Replacement Dialogue One", "start");
        firstDialogueReplacement.addNode(new DialogueNode("start", "Replacement greeting."));
        assertThat(service.replaceDialogue(validDialogueReplaceRequest, firstDialogueReplacement).applied()).isTrue();
        DialogueGraph exactDialogueReplay = new DialogueGraph(dialogueId, "Replacement Dialogue One", "start");
        exactDialogueReplay.addNode(new DialogueNode("start", "Replacement greeting."));
        assertThat(service.replaceDialogue(validDialogueReplaceRequest, exactDialogueReplay).duplicate()).isTrue();
        DialogueGraph changedDialogueReplay = new DialogueGraph(dialogueId, "Replacement Dialogue Two", "start");
        changedDialogueReplay.addNode(new DialogueNode("start", "Different greeting."));
        CanonicalMutationResult changedDialogueResult =
                service.replaceDialogue(validDialogueReplaceRequest, changedDialogueReplay);
        assertThat(changedDialogueResult.applied()).isFalse();
        assertThat(changedDialogueResult.diagnostics().formatReport()).contains("REQUEST_PAYLOAD_MISMATCH");
        assertThat(registry.getDialogue(dialogueId).orElseThrow().getTitle())
                .isEqualTo("Replacement Dialogue One");

        NamespacedId questId = NamespacedId.of("storynpcs:replace_quest_payload");
        MutationRequest createQuest = new MutationRequest(
                "quest.create", "command", "quest.mutate", questId, 0L, UUID.randomUUID());
        assertThat(service.createQuest(createQuest, "Original Quest").applied()).isTrue();
        MutationRequest questReplaceRequest = new MutationRequest(
                "quest.replace", "command", "quest.edit", questId,
                service.currentRevision("quest", questId), UUID.randomUUID());
        Quest questReplacement = com.storynpcs.domain.quest.QuestSerde.fromJson(
                com.storynpcs.domain.quest.QuestSerde.toJson(registry.getQuest(questId).orElseThrow()))
                .orElseThrow();
        questReplacement.setTitle("Replacement Quest One");
        CanonicalMutationResult firstQuestReplacement = service.replaceQuest(questReplaceRequest, questReplacement);
        assertThat(firstQuestReplacement.applied()).as(firstQuestReplacement.diagnostics().formatReport()).isTrue();
        Quest exactQuestRetry = com.storynpcs.domain.quest.QuestSerde.fromJson(
                com.storynpcs.domain.quest.QuestSerde.toJson(questReplacement)).orElseThrow();
        assertThat(service.replaceQuest(questReplaceRequest, exactQuestRetry).duplicate()).isTrue();
        Quest changedQuest = com.storynpcs.domain.quest.QuestSerde.fromJson(
                com.storynpcs.domain.quest.QuestSerde.toJson(registry.getQuest(questId).orElseThrow()))
                .orElseThrow();
        changedQuest.setTitle("Replacement Quest Two");
        CanonicalMutationResult changedQuestReplay = service.replaceQuest(
                questReplaceRequest, changedQuest);
        assertThat(changedQuestReplay.applied()).isFalse();
        assertThat(changedQuestReplay.diagnostics().formatReport()).contains("REQUEST_PAYLOAD_MISMATCH");
        assertThat(registry.getQuest(questId).orElseThrow().getTitle()).isEqualTo("Replacement Quest One");

        NamespacedId factionId = NamespacedId.of("storynpcs:replace_faction_payload");
        MutationRequest createFaction = new MutationRequest(
                "faction.create", "command", "faction.mutate", factionId, 0L, UUID.randomUUID());
        assertThat(service.createFaction(createFaction, "Original Faction").applied()).isTrue();
        MutationRequest factionReplaceRequest = new MutationRequest(
                "faction.replace", "command", "faction.edit", factionId,
                service.currentRevision("faction", factionId), UUID.randomUUID());
        Faction firstFactionReplacement = new Faction(factionId, "Replacement Faction One", 1000, 500, 1500);
        assertThat(service.replaceFaction(factionReplaceRequest, firstFactionReplacement).applied()).isTrue();
        assertThat(service.replaceFaction(factionReplaceRequest,
                new Faction(factionId, "Replacement Faction One", 1000, 500, 1500)).duplicate()).isTrue();
        CanonicalMutationResult changedFactionReplay = service.replaceFaction(factionReplaceRequest,
                new Faction(factionId, "Replacement Faction Two", 1000, 500, 1500));
        assertThat(changedFactionReplay.applied()).isFalse();
        assertThat(changedFactionReplay.diagnostics().formatReport()).contains("REQUEST_PAYLOAD_MISMATCH");
        assertThat(registry.getFaction(factionId).orElseThrow().getName()).isEqualTo("Replacement Faction One");
    }

    @Test
    void callbackMutationObjectsCannotEscapeIntoCommittedDefinitions() throws Exception {
        com.storynpcs.yaml.YamlDefinitionLoader loader =
                new com.storynpcs.yaml.YamlDefinitionLoader(registry);
        assertThat(loader.loadDirectory(tempDir).isValid()).isTrue();
        service.setLoader(loader);

        NamespacedId npcId = NamespacedId.of("storynpcs:callback_alias_npc");
        NpcDefinition npc = new NpcDefinition(npcId, "Original NPC");
        registry.registerNpc(npc);
        AtomicReference<NpcDefinition> escapedNpc = new AtomicReference<>();
        CanonicalMutationResult npcResult = service.mutateNpc(
                new MutationRequest("npc.mutate", "command", "npc.mutate", npcId, 0L, UUID.randomUUID()),
                working -> {
                    working.getDisplay().setName("Committed NPC");
                    escapedNpc.set(working);
                });
        assertThat(npcResult.applied()).isTrue();
        escapedNpc.get().getDisplay().setName("Escaped NPC");
        assertThat(registry.getNpc(npcId).orElseThrow().getDisplay().getName()).isEqualTo("Committed NPC");

        NamespacedId dialogueId = NamespacedId.of("storynpcs:callback_alias_dialogue");
        DialogueGraph dialogue = new DialogueGraph(dialogueId, "Original Dialogue", "start");
        dialogue.addNode(new DialogueNode("start", "Hello"));
        registry.registerDialogue(dialogue);
        AtomicReference<DialogueGraph> escapedDialogue = new AtomicReference<>();
        CanonicalMutationResult dialogueResult = service.mutateDialogue(
                new MutationRequest("dialogue.mutate", "command", "dialogue.mutate", dialogueId,
                        0L, UUID.randomUUID()),
                working -> {
                    working.setTitle("Committed Dialogue");
                    escapedDialogue.set(working);
                });
        assertThat(dialogueResult.applied()).isTrue();
        escapedDialogue.get().setTitle("Escaped Dialogue");
        assertThat(registry.getDialogue(dialogueId).orElseThrow().getTitle()).isEqualTo("Committed Dialogue");

        NamespacedId questId = NamespacedId.of("storynpcs:callback_alias_quest");
        Quest quest = new Quest(questId, "Original Quest");
        quest.setObjectives(List.of(new QuestObjective(
                "objective_1", QuestObjective.Type.CUSTOM, "complete the task", 1)));
        registry.registerQuest(quest);
        AtomicReference<Quest> escapedQuest = new AtomicReference<>();
        CanonicalMutationResult questResult = service.mutateQuest(
                new MutationRequest("quest.mutate", "command", "quest.mutate", questId,
                        0L, UUID.randomUUID()),
                working -> {
                    working.setTitle("Committed Quest");
                    escapedQuest.set(working);
                });
        assertThat(questResult.applied()).isTrue();
        escapedQuest.get().setTitle("Escaped Quest");
        assertThat(registry.getQuest(questId).orElseThrow().getTitle()).isEqualTo("Committed Quest");

        NamespacedId factionId = NamespacedId.of("storynpcs:callback_alias_faction");
        Faction faction = new Faction(factionId, "Original Faction", 1000, 500, 1500);
        registry.registerFaction(faction);
        AtomicReference<Faction> escapedFaction = new AtomicReference<>();
        CanonicalMutationResult factionResult = service.mutateFaction(
                new MutationRequest("faction.mutate", "command", "faction.mutate", factionId,
                        0L, UUID.randomUUID()),
                working -> {
                    working.setName("Committed Faction");
                    escapedFaction.set(working);
                });
        assertThat(factionResult.applied()).isTrue();
        escapedFaction.get().setName("Escaped Faction");
        assertThat(registry.getFaction(factionId).orElseThrow().getName()).isEqualTo("Committed Faction");

        DefinitionRegistry reloaded = new DefinitionRegistry();
        assertThat(new com.storynpcs.yaml.YamlDefinitionLoader(reloaded).loadDirectory(tempDir).isValid()).isTrue();
        assertThat(reloaded.getNpc(npcId).orElseThrow().getDisplay().getName()).isEqualTo("Committed NPC");
        assertThat(reloaded.getDialogue(dialogueId).orElseThrow().getTitle()).isEqualTo("Committed Dialogue");
        assertThat(reloaded.getQuest(questId).orElseThrow().getTitle()).isEqualTo("Committed Quest");
        assertThat(reloaded.getFaction(factionId).orElseThrow().getName()).isEqualTo("Committed Faction");
    }

    @Test
    void sameRequestCannotReenterTheCanonicalExecutorFromItsCallback() {
        NamespacedId npcId = NamespacedId.of("storynpcs:reentrant_request");
        registry.registerNpc(new NpcDefinition(npcId, "Original"));
        MutationRequest request = new MutationRequest(
                "npc.mutate", "command", "npc.mutate", npcId, 0L, UUID.randomUUID());
        AtomicReference<CanonicalMutationResult> nestedResult = new AtomicReference<>();
        AtomicReference<CanonicalMutationResult> differentRequestResult = new AtomicReference<>();

        CanonicalMutationResult outerResult = service.mutateNpc(request, working -> {
            nestedResult.set(service.mutateNpc(request, nested -> nested.getDisplay().setName("Nested")));
            MutationRequest differentRequest = new MutationRequest(
                    "npc.mutate", "command", "npc.mutate", npcId, 0L, UUID.randomUUID());
            differentRequestResult.set(service.mutateNpc(
                    differentRequest, nested -> nested.getDisplay().setName("Nested with another ID")));
            working.getDisplay().setName("Outer");
        });

        assertThat(outerResult.applied()).isTrue();
        assertThat(nestedResult.get().applied()).isFalse();
        assertThat(nestedResult.get().diagnostics().formatReport()).contains("MUTATION_IN_PROGRESS");
        assertThat(differentRequestResult.get().applied()).isFalse();
        assertThat(differentRequestResult.get().diagnostics().formatReport()).contains("REENTRANT_MUTATION");
        assertThat(registry.getNpc(npcId).orElseThrow().getDisplay().getName()).isEqualTo("Outer");
        assertThat(service.currentRevision("npc", npcId)).isEqualTo(1L);
        assertThat(publishedEvents.stream().filter(CanonicalMutationEvent.class::isInstance)).hasSize(1);
    }

    @Test
    void callersCannotMutateTheCachedCanonicalMutationReceipt() {
        NamespacedId npcId = NamespacedId.of("storynpcs:immutable_mutation_receipt");
        registry.registerNpc(new NpcDefinition(npcId, "Original"));
        MutationRequest request = new MutationRequest(
                "npc.replace", "command", "npc.edit", npcId, 0L, UUID.randomUUID());
        NpcDefinition replacement = new NpcDefinition(npcId, "Replacement");

        CanonicalMutationResult first = service.replaceNpc(request, replacement);
        first.diagnostics().addError("CALLER_EDIT", "caller changed its receipt");
        CanonicalMutationResult replay = service.replaceNpc(request,
                new NpcDefinition(npcId, "Replacement"));

        assertThat(replay.applied()).isTrue();
        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.diagnostics().getErrors()).isEmpty();
    }

    @Test
    void unreferencedDialogueCompensationDeletesYamlAndRegistryEntry() throws Exception {
        com.storynpcs.yaml.YamlDefinitionLoader loader =
                new com.storynpcs.yaml.YamlDefinitionLoader(registry);
        assertThat(loader.loadDirectory(tempDir).isValid()).isTrue();
        service.setLoader(loader);

        NamespacedId dialogueId = NamespacedId.of("storynpcs:compensated_dialogue");
        MutationRequest create = new MutationRequest(
                "dialogue.create", "command", "dialogue.mutate", dialogueId,
                service.currentRevision("dialogue", dialogueId), UUID.randomUUID());
        assertThat(service.createDialogue(create, "Compensated Dialogue").applied()).isTrue();
        Path dialogueFile = loader.getDefinitionFiles("dialogues", dialogueId).getFirst();
        assertThat(dialogueFile).exists();

        MutationRequest rollback = new MutationRequest(
                "dialogue.delete", "command", "dialogue.delete", dialogueId,
                service.currentRevision("dialogue", dialogueId), UUID.randomUUID());
        CanonicalMutationResult result = service.deleteUnreferencedDialogue(rollback);

        assertThat(result.applied()).isTrue();
        assertThat(registry.getDialogue(dialogueId)).isEmpty();
        assertThat(dialogueFile).doesNotExist();
        assertThat(loader.getDefinitionFiles("dialogues", dialogueId)).isEmpty();
        assertThat(service.currentRevision("dialogue", dialogueId)).isEqualTo(2L);
        DefinitionRegistry reloaded = new DefinitionRegistry();
        assertThat(new com.storynpcs.yaml.YamlDefinitionLoader(reloaded).loadDirectory(tempDir).isValid()).isTrue();
        assertThat(reloaded.getDialogue(dialogueId)).isEmpty();
    }

    @Test
    void unreferencedDialogueCompensationRefusesReferencedGraphAndKeepsDefinition() {
        NamespacedId dialogueId = NamespacedId.of("storynpcs:still_referenced_dialogue");
        MutationRequest createDialogue = new MutationRequest(
                "dialogue.create", "command", "dialogue.mutate", dialogueId, 0L, UUID.randomUUID());
        assertThat(service.createDialogue(createDialogue, "Referenced Dialogue").applied()).isTrue();

        NamespacedId npcId = NamespacedId.of("storynpcs:dialogue_owner");
        NpcDefinition npc = new NpcDefinition(npcId, "Dialogue Owner");
        npc.setDialogueId(dialogueId);
        MutationRequest createNpc = new MutationRequest(
                "npc.create", "command", "npc.mutate", npcId, 0L, UUID.randomUUID());
        assertThat(service.createNpc(createNpc, npc).applied()).isTrue();

        MutationRequest rollback = new MutationRequest(
                "dialogue.delete", "command", "dialogue.delete", dialogueId,
                service.currentRevision("dialogue", dialogueId), UUID.randomUUID());
        CanonicalMutationResult result = service.deleteUnreferencedDialogue(rollback);

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().formatReport()).contains("DIALOGUE_REFERENCED", npcId.toString());
        assertThat(registry.getDialogue(dialogueId)).isPresent();
        assertThat(service.currentRevision("dialogue", dialogueId)).isEqualTo(1L);
    }

    @Test
    void unreferencedDialogueCompensationReportsDeleteFailureWithoutDroppingRegistryEntry() throws Exception {
        NamespacedId dialogueId = NamespacedId.of("storynpcs:delete_failure_dialogue");
        DialogueGraph graph = new DialogueGraph(dialogueId, "Delete Failure Dialogue", "start");
        graph.addNode(new DialogueNode("start", "A valid graph for the deletion failure fixture."));
        Path dialogueFile = new com.storynpcs.yaml.YamlDefinitionWriter().writeDefinition(
                tempDir, "dialogues", "delete_failure_dialogue", graph);
        com.storynpcs.yaml.YamlDefinitionLoader failingLoader =
                new com.storynpcs.yaml.YamlDefinitionLoader(registry) {
                    @Override
                    public synchronized boolean deleteDefinitionFile(String type, NamespacedId id)
                            throws java.io.IOException {
                        throw new java.io.IOException("forced delete failure for regression test");
                    }
                };
        assertThat(failingLoader.loadDirectory(tempDir).isValid()).isTrue();
        service.setLoader(failingLoader);

        MutationRequest rollback = new MutationRequest(
                "dialogue.delete", "command", "dialogue.delete", dialogueId, 0L, UUID.randomUUID());
        CanonicalMutationResult result = service.deleteUnreferencedDialogue(rollback);

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().formatReport()).contains("DEFINITION_DELETE_FAILED");
        assertThat(registry.getDialogue(dialogueId)).isPresent();
        assertThat(dialogueFile).exists();
        assertThat(service.currentRevision("dialogue", dialogueId)).isZero();
    }

    @Test
    void dialogueCompensationSerializesAgainstLegacyNpcSave() throws Exception {
        NamespacedId dialogueId = NamespacedId.of("storynpcs:dialogue_save_race");
        NamespacedId npcId = NamespacedId.of("storynpcs:dialogue_save_race_owner");
        CountDownLatch referenceScanEntered = new CountDownLatch(1);
        CountDownLatch continueCompensation = new CountDownLatch(1);
        CountDownLatch saveAttempted = new CountDownLatch(1);
        CountDownLatch saveFinished = new CountDownLatch(1);
        AtomicBoolean pauseFirstScan = new AtomicBoolean(true);

        StoryNpcsApplicationService raceService = new StoryNpcsApplicationService(
                registry, progressionRepository, eventPublisher) {
            @Override
            public List<NamespacedId> findNpcsReferencingDialogue(NamespacedId id) {
                List<NamespacedId> references = super.findNpcsReferencingDialogue(id);
                if (dialogueId.equals(id) && pauseFirstScan.compareAndSet(true, false)) {
                    referenceScanEntered.countDown();
                    try {
                        if (!continueCompensation.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting to continue dialogue compensation");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted during dialogue compensation test", interrupted);
                    }
                }
                return references;
            }

            @Override
            public ValidationResult saveNpc(NpcDefinition definition) {
                if (npcId.equals(definition.getId())) saveAttempted.countDown();
                try {
                    return super.saveNpc(definition);
                } finally {
                    if (npcId.equals(definition.getId())) saveFinished.countDown();
                }
            }
        };
        service = raceService;
        com.storynpcs.yaml.YamlDefinitionLoader loader =
                new com.storynpcs.yaml.YamlDefinitionLoader(registry);
        assertThat(loader.loadDirectory(tempDir).isValid()).isTrue();
        raceService.setLoader(loader);

        MutationRequest createDialogue = new MutationRequest(
                "dialogue.create", "command", "dialogue.mutate", dialogueId, 0L, UUID.randomUUID());
        assertThat(raceService.createDialogue(createDialogue, "Race Dialogue").applied()).isTrue();
        Path dialogueFile = loader.getDefinitionFiles("dialogues", dialogueId).getFirst();

        NpcDefinition npc = new NpcDefinition(npcId, "Race Owner");
        npc.setDialogueId(dialogueId);
        var pool = Executors.newFixedThreadPool(2);
        try {
            MutationRequest rollbackRequest = new MutationRequest(
                    "dialogue.delete", "command", "dialogue.delete", dialogueId,
                    raceService.currentRevision("dialogue", dialogueId), UUID.randomUUID());
            var rollbackFuture = pool.submit(() -> raceService.deleteUnreferencedDialogue(rollbackRequest));
            assertThat(referenceScanEntered.await(5, TimeUnit.SECONDS)).isTrue();

            var saveFuture = pool.submit(() -> raceService.saveNpc(npc));
            assertThat(saveAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(saveFinished.await(100, TimeUnit.MILLISECONDS)).isFalse();

            continueCompensation.countDown();
            CanonicalMutationResult rollback = rollbackFuture.get(5, TimeUnit.SECONDS);
            ValidationResult save = saveFuture.get(5, TimeUnit.SECONDS);

            assertThat(rollback.applied()).isTrue();
            assertThat(save.hasErrors()).isTrue();
            assertThat(registry.getDialogue(dialogueId)).isEmpty();
            assertThat(registry.getNpc(npcId)).isEmpty();
            assertThat(dialogueFile).doesNotExist();
            assertThat(tempDir.resolve("npcs").resolve("dialogue_save_race_owner.yaml")).doesNotExist();
        } finally {
            continueCompensation.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void typedNpcCreationRejectsTargetMismatchAndDuplicateWithoutOverwriting() {
        NamespacedId npcId = NamespacedId.of("storynpcs:typed_create_guard");
        MutationRequest request = new MutationRequest(
                "npc.create", "command", "npc.mutate", npcId, 0L, UUID.randomUUID());
        CanonicalMutationResult mismatch = service.createNpc(
                request, new NpcDefinition(NamespacedId.of("storynpcs:other"), "Wrong target"));
        assertThat(mismatch.applied()).isFalse();
        assertThat(mismatch.diagnostics().formatReport()).contains("TARGET_ID_MISMATCH");
        assertThat(registry.getNpc(npcId)).isEmpty();

        MutationRequest createRequest = new MutationRequest(
                "npc.create", "command", "npc.mutate", npcId, 0L, UUID.randomUUID());
        assertThat(service.createNpc(createRequest, new NpcDefinition(npcId, "Original")).applied()).isTrue();
        MutationRequest duplicateRequest = new MutationRequest(
                "npc.create", "command", "npc.mutate", npcId, 1L, UUID.randomUUID());
        CanonicalMutationResult duplicate = service.createNpc(
                duplicateRequest, new NpcDefinition(npcId, "Must not replace"));
        assertThat(duplicate.applied()).isFalse();
        assertThat(duplicate.diagnostics().formatReport()).contains("NPC_ALREADY_EXISTS");
        assertThat(registry.getNpc(npcId).orElseThrow().getDisplay().getName()).isEqualTo("Original");
    }

    @Test
    void typedNpcReplacementRejectsTargetMismatchWithoutWritingRedirectedDefinition() {
        NamespacedId targetId = NamespacedId.of("storynpcs:replace_target_guard");
        NamespacedId redirectedId = NamespacedId.of("storynpcs:replace_redirect_guard");
        service.createNpc(new NpcDefinition(targetId, "Original"));
        MutationRequest request = new MutationRequest(
                "npc.replace", "command", "npc.edit", targetId,
                service.currentRevision("npc", targetId), UUID.randomUUID());

        CanonicalMutationResult result = service.replaceNpc(request, new NpcDefinition(redirectedId, "Redirected"));

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().formatReport()).contains("TARGET_ID_MISMATCH");
        assertThat(registry.getNpc(targetId).orElseThrow().getDisplay().getName()).isEqualTo("Original");
        assertThat(registry.getNpc(redirectedId)).isEmpty();
    }

    @Test
    void typedMutationRejectsRequestFromAnotherDefinitionFamily() {
        NamespacedId npcId = NamespacedId.of("storynpcs:family_guard");
        service.createNpc(new NpcDefinition(npcId, "Unchanged"));
        MutationRequest dialogueRequest = new MutationRequest(
                "dialogue.mutate", "command", "dialogue.edit", npcId, 0L, UUID.randomUUID());

        CanonicalMutationResult result = service.mutateNpc(dialogueRequest,
                npc -> npc.getDisplay().setName("Must not be applied"));

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().formatReport()).contains("MUTATION_ROUTE_MISMATCH");
        assertThat(registry.getNpc(npcId).orElseThrow().getDisplay().getName()).isEqualTo("Unchanged");
    }

    @Test
    void typedDeleteRejectsMutationCapabilityWithoutDeletingTarget() {
        NamespacedId npcId = NamespacedId.of("storynpcs:delete_capability_guard");
        service.createNpc(new NpcDefinition(npcId, "Protected"));
        MutationRequest wrongCapability = new MutationRequest(
                "npc.delete", "command", "npc.edit", npcId, 0L, UUID.randomUUID());

        CanonicalMutationResult result = service.deleteNpc(wrongCapability);

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().formatReport()).contains("MUTATION_ROUTE_MISMATCH");
        assertThat(registry.getNpc(npcId)).isPresent();
    }

    @Test
    void typedDefinitionMutationsCannotChangeTheirTargetId() {
        NamespacedId npcId = NamespacedId.of("storynpcs:stable_npc");
        NamespacedId dialogueId = NamespacedId.of("storynpcs:stable_dialogue");
        NamespacedId questId = NamespacedId.of("storynpcs:stable_quest");
        NamespacedId factionId = NamespacedId.of("storynpcs:stable_faction");
        NamespacedId otherId = NamespacedId.of("storynpcs:mutation_redirect_target");
        service.createNpc(new NpcDefinition(npcId, "Stable NPC"));
        assertThat(service.createDialogue(dialogueId, "Stable Dialogue").hasErrors()).isFalse();
        assertThat(service.createQuest(questId, "Stable Quest").hasErrors()).isFalse();
        assertThat(service.createFaction(factionId, "Stable Faction").hasErrors()).isFalse();

        CanonicalMutationResult npcResult = service.mutateNpc(new MutationRequest(
                "npc.mutate", "command", "npc.mutate", npcId,
                service.currentRevision("npc", npcId), UUID.randomUUID()), npc -> npc.setId(otherId));
        CanonicalMutationResult dialogueResult = service.mutateDialogue(new MutationRequest(
                "dialogue.mutate", "command", "dialogue.mutate", dialogueId,
                service.currentRevision("dialogue", dialogueId), UUID.randomUUID()), graph -> graph.setId(otherId));
        CanonicalMutationResult questResult = service.mutateQuest(new MutationRequest(
                "quest.mutate", "command", "quest.mutate", questId,
                service.currentRevision("quest", questId), UUID.randomUUID()), quest -> quest.setId(otherId));
        CanonicalMutationResult factionResult = service.mutateFaction(new MutationRequest(
                "faction.mutate", "command", "faction.mutate", factionId,
                service.currentRevision("faction", factionId), UUID.randomUUID()), faction -> faction.setId(otherId));

        assertThat(List.of(npcResult, dialogueResult, questResult, factionResult))
                .allSatisfy(result -> {
                    assertThat(result.applied()).isFalse();
                    assertThat(result.diagnostics().formatReport()).contains("TARGET_ID_MISMATCH");
                });
        assertThat(registry.getNpc(npcId)).isPresent();
        assertThat(registry.getDialogue(dialogueId)).isPresent();
        assertThat(registry.getQuest(questId)).isPresent();
        assertThat(registry.getFaction(factionId)).isPresent();
        assertThat(registry.getNpc(otherId)).isEmpty();
        assertThat(registry.getDialogue(otherId)).isEmpty();
        assertThat(registry.getQuest(otherId)).isEmpty();
        assertThat(registry.getFaction(otherId)).isEmpty();
    }

    @Test
    void questAndFactionAdapterMutationsCommitOnlyThroughService() {
        NamespacedId questId = NamespacedId.of("storynpcs:canonical_quest");
        assertThat(service.createQuest(questId, "Canonical").hasErrors()).isFalse();
        assertThat(service.mutateQuest(questId, quest -> quest.setCategory("service")).hasErrors()).isFalse();
        assertThat(registry.getQuest(questId).orElseThrow().getCategory()).isEqualTo("service");

        NamespacedId factionId = NamespacedId.of("storynpcs:canonical_faction");
        assertThat(service.createFaction(factionId, "Canonical").hasErrors()).isFalse();
        assertThat(service.mutateFaction(factionId, faction -> faction.setDefaultPoints(1200)).hasErrors()).isFalse();
        assertThat(registry.getFaction(factionId).orElseThrow().getDefaultPoints()).isEqualTo(1200);
    }

    @Test
    void typedDialogueMutationUsesDetachedGraphAndRevisionBoundary() {
        NamespacedId dialogueId = NamespacedId.of("storynpcs:canonical_dialogue");
        assertThat(service.createDialogue(dialogueId, "Canonical").hasErrors()).isFalse();
        MutationRequest request = new MutationRequest(
                "dialogue.mutate", "gui", "dialogue.edit", dialogueId, 1L, UUID.randomUUID());

        CanonicalMutationResult result = service.mutateDialogue(request,
                graph -> graph.getNode("start").orElseThrow().setText("Changed through service"));

        assertThat(result.applied()).isTrue();
        assertThat(result.revision()).isEqualTo(2L);
        assertThat(registry.getDialogue(dialogueId).orElseThrow()
                .getNode("start").orElseThrow().getText()).isEqualTo("Changed through service");
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

    @Test
    void deleteFactionRejectsUnknownId() {
        assertThat(service.deleteFaction(NamespacedId.of("storynpcs:nonexistent_faction"))).isFalse();
    }

    @Test
    void deleteFactionShouldRemoveAndReportNpcReferences() {
        NamespacedId factionId = NamespacedId.of("storynpcs:deletable_faction");
        service.createFaction(factionId, "Deletable Faction");
        assertThat(registry.getFaction(factionId)).isPresent();

        NamespacedId npcId = NamespacedId.of("storynpcs:faction_ref_npc");
        NpcDefinition npc = new NpcDefinition(npcId, "Loyalist");
        npc.setFactionId(factionId);
        registry.registerNpc(npc);

        assertThat(service.findNpcsReferencingFaction(factionId)).containsExactly(npcId);
        assertThat(service.deleteFaction(factionId)).isTrue();
        assertThat(registry.getFaction(factionId)).isEmpty();
        assertThat(service.deleteFaction(factionId)).isFalse(); // idempotent-miss
    }

    @Test
    void deleteFactionCanonicalRequestReportsNotFoundForUnknownId() {
        var request = new MutationRequest("faction.delete", "command", "faction.delete",
                NamespacedId.of("storynpcs:ghost_faction"), 0L, UUID.randomUUID());
        CanonicalMutationResult result = service.deleteFaction(request);
        assertThat(result.applied()).isFalse();
        assertThat(result.formatReport()).contains("FACTION_NOT_FOUND");
    }
}
