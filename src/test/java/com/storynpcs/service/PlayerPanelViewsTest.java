package com.storynpcs.service;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.panel.PlayerPanels;
import com.storynpcs.domain.progression.MailMessage;
import com.storynpcs.domain.progression.PendingQuestCompletion;
import com.storynpcs.domain.progression.PlayerProgression;
import com.storynpcs.domain.progression.QuestProgressState;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.domain.transport.TransportLocation;
import com.storynpcs.yaml.DefinitionRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Player-panel view builders (issue #150): the views the client renders are
 * built server-side from the durable progression record + definition registry
 * — these tests pin the projection rules (status filtering, standing
 * classification, ordering, unlocked flags).
 */
class PlayerPanelViewsTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private static PlayerProgression progression() {
        return new PlayerProgression(PLAYER);
    }

    @Test
    void questLogHidesNotStartedAndSurfacesPendingCompletions() {
        var progression = progression();
        var started = NamespacedId.of("storynpcs:rescue");
        var done = NamespacedId.of("storynpcs:errand");
        var untouched = NamespacedId.of("storynpcs:unseen");

        var inProgress = new QuestProgressState(started);
        inProgress.setStatus(QuestProgressState.Status.IN_PROGRESS);
        inProgress.getObjectiveCounts().put("rescue_hostage", 2);
        var completed = new QuestProgressState(done);
        completed.setStatus(QuestProgressState.Status.COMPLETED);
        var untouchedState = new QuestProgressState(untouched); // NOT_STARTED

        progression.getQuests().put(started, inProgress);
        progression.getQuests().put(done, completed);
        progression.getQuests().put(untouched, untouchedState);
        progression.getPendingQuestCompletions().put(started,
                new PendingQuestCompletion(UUID.randomUUID(), started,
                        "sha256:" + "0".repeat(64), 3L));

        var registry = new DefinitionRegistry();
        registry.registerQuest(new Quest(started, "Rescue the Hostage"));
        registry.registerQuest(new Quest(done, "Run the Errand"));

        PlayerPanels.QuestLogView view = PlayerPanelViews.questLog(registry, progression);

        assertThat(view.quests()).hasSize(2); // NOT_STARTED filtered
        assertThat(view.quests()).extracting(PlayerPanels.QuestRow::status)
                .containsExactly("COMPLETED", "IN_PROGRESS"); // status-sorted
        var rescue = view.quests().stream()
                .filter(r -> r.questId().equals("storynpcs:rescue")).findFirst().orElseThrow();
        assertThat(rescue.title()).isEqualTo("Rescue the Hostage");
        assertThat(rescue.objectives()).containsExactly("rescue_hostage: 2");
        assertThat(rescue.pendingCompletion()).isTrue();
        assertThat(view.pending()).extracting(PlayerPanels.PendingQuestRow::questId)
                .containsExactly("storynpcs:rescue");
        assertThat(view.pending().get(0).stateRevision()).isEqualTo(3L);
    }

    @Test
    void questLogResolvesMissingDefinitionToId() {
        var progression = progression();
        var id = NamespacedId.of("storynpcs:ghost");
        var state = new QuestProgressState(id);
        state.setStatus(QuestProgressState.Status.FAILED);
        progression.getQuests().put(id, state);

        var view = PlayerPanelViews.questLog(new DefinitionRegistry(), progression);
        assertThat(view.quests()).hasSize(1);
        assertThat(view.quests().get(0).title()).isEqualTo("storynpcs:ghost");
    }

    @Test
    void factionPanelClassifiesStandingFromAuthoredThresholds() {
        var registry = new DefinitionRegistry();
        Faction friendly = new Faction(NamespacedId.of("storynpcs:allies"), "Allies",
                0, -200, 500);
        Faction hostile = new Faction(NamespacedId.of("storynpcs:raiders"), "Raiders",
                0, -200, 500);
        registry.registerFaction(friendly);
        registry.registerFaction(hostile);

        var progression = progression();
        progression.getFactionPoints().put(friendly.getId(), 700);
        progression.getFactionPoints().put(hostile.getId(), -350);

        var view = PlayerPanelViews.factionPanel(registry, progression);
        assertThat(view.factions()).hasSize(2);
        var allies = view.factions().stream()
                .filter(r -> r.name().equals("Allies")).findFirst().orElseThrow();
        assertThat(allies.points()).isEqualTo(700);
        assertThat(allies.standing()).isEqualTo("FRIENDLY");
        var raiders = view.factions().stream()
                .filter(r -> r.name().equals("Raiders")).findFirst().orElseThrow();
        assertThat(raiders.standing()).isEqualTo("HOSTILE");
    }

    @Test
    void mailViewPresentsNewestFirstWithAllFields() {
        var mailbox = List.of(
                new MailMessage(UUID.randomUUID(), "Guild", "First", "old body", 1000L),
                new MailMessage(UUID.randomUUID(), "King", "Second", "new body", 2000L));
        mailbox.get(1).setRead(true);

        var view = PlayerPanelViews.mail(mailbox);
        assertThat(view.messages()).hasSize(2);
        assertThat(view.messages().get(0).subject()).isEqualTo("Second");
        assertThat(view.messages().get(0).read()).isTrue();
        assertThat(view.messages().get(1).subject()).isEqualTo("First");
        assertThat(view.messages().get(1).body()).isEqualTo("old body");
    }

    @Test
    void transportViewFlagsUnlockedRows() {
        var unlockedId = NamespacedId.of("storynpcs:harbor");
        var lockedId = NamespacedId.of("storynpcs:summit");
        TransportLocation open = new TransportLocation(unlockedId, "Harbor",
                "minecraft:overworld", 10, 64, 10);
        TransportLocation locked = new TransportLocation(lockedId, "Summit",
                "minecraft:overworld", 100, 90, 100);
        locked.setFee(50);

        var view = PlayerPanelViews.transport(List.of(locked, open), Set.of(unlockedId));
        assertThat(view.destinations()).hasSize(2);
        var harbor = view.destinations().stream()
                .filter(r -> r.name().equals("Harbor")).findFirst().orElseThrow();
        assertThat(harbor.unlocked()).isTrue();
        var summit = view.destinations().stream()
                .filter(r -> r.name().equals("Summit")).findFirst().orElseThrow();
        assertThat(summit.unlocked()).isFalse();
        assertThat(summit.fee()).isEqualTo(50);
    }

    @Test
    void companionAndHireViewsSortRowsAndTolerateNulls() {
        var hired = List.of(
                new PlayerPanels.CompanionRow(UUID.randomUUID().toString(), "Zed",
                        18.0, 20.0, "storynpcs:guard", 2, 27, false),
                new PlayerPanels.CompanionRow(UUID.randomUUID().toString(), "Ada",
                        20.0, 20.0, "", 0, 9, true),
                new PlayerPanels.CompanionRow(UUID.randomUUID().toString(), null,
                        1.0, 1.0, "", 0, 9, false));
        var view = PlayerPanelViews.companions(hired);
        assertThat(view.companions()).hasSize(3);
        // null names sort last via nullsLast ordering.
        assertThat(view.companions().get(2).name()).isNull();
        assertThat(view.companions().get(0).name()).isEqualTo("Ada");

        assertThat(PlayerPanelViews.companions(null).companions()).isEmpty();

        var hire = PlayerPanelViews.followerHire(List.of(
                new PlayerPanels.HireRow(UUID.randomUUID().toString(), "B", 5, 600),
                new PlayerPanels.HireRow(UUID.randomUUID().toString(), "A", 0, 0)));
        assertThat(hire.candidates()).extracting(PlayerPanels.HireRow::name)
                .containsExactly("A", "B");
        assertThat(PlayerPanelViews.followerHire(null).candidates()).isEmpty();
    }

    @Test
    void achievementViewSurfacesEarnedProgressOnly() {
        var registry = new DefinitionRegistry();
        var done = NamespacedId.of("storynpcs:errand");
        var open = NamespacedId.of("storynpcs:rescue");
        registry.registerQuest(new Quest(done, "Run the Errand"));
        registry.registerQuest(new Quest(open, "Rescue"));
        Faction friendly = new Faction(NamespacedId.of("storynpcs:allies"), "Allies",
                0, -200, 500);
        Faction neutral = new Faction(NamespacedId.of("storynpcs:tribes"), "Tribes",
                0, -200, 500);
        registry.registerFaction(friendly);
        registry.registerFaction(neutral);

        var progression = progression();
        var doneState = new QuestProgressState(done);
        doneState.setStatus(QuestProgressState.Status.COMPLETED);
        var openState = new QuestProgressState(open);
        openState.setStatus(QuestProgressState.Status.IN_PROGRESS);
        progression.getQuests().put(done, doneState);
        progression.getQuests().put(open, openState);
        progression.getFactionPoints().put(friendly.getId(), 700);
        progression.getFactionPoints().put(neutral.getId(), 10);

        var view = PlayerPanelViews.achievements(registry, progression, 2);
        // Earned rows only: completed quest + friendly standing + 2 companions.
        // The in-progress quest and neutral faction contribute nothing.
        assertThat(view.rows()).hasSize(4);
        assertThat(view.rows()).extracting(PlayerPanels.AchievementRow::id)
                .contains("quest:storynpcs:errand", "faction:storynpcs:allies");
        assertThat(view.rows()).extracting(PlayerPanels.AchievementRow::id)
                .doesNotContain("quest:storynpcs:rescue", "faction:storynpcs:tribes");
        assertThat(view.rows()).filteredOn(r -> r.id().startsWith("companion:"))
                .hasSize(2);
    }

    @Test
    void carpentryViewSummarizesIngredientsAndSorts() {
        var shaped = new com.storynpcs.creator.recipe.CarpentryRecipe();
        shaped.setId(NamespacedId.of("storynpcs:shield"));
        shaped.setGroupId(NamespacedId.of("storynpcs:bench"));
        shaped.setGrid(List.of("minecraft:oak_planks", "minecraft:iron_ingot",
                "minecraft:oak_planks", "minecraft:oak_planks",
                "minecraft:iron_ingot", "minecraft:oak_planks",
                "", "minecraft:oak_planks", ""));
        shaped.setOutputItemId("minecraft:shield");
        shaped.setOutputCount(1);
        var shapeless = new com.storynpcs.creator.recipe.CarpentryRecipe();
        shapeless.setId(NamespacedId.of("storynpcs:bundle"));
        shapeless.setGroupId(NamespacedId.of("storynpcs:bench"));
        shapeless.setShapeless(true);
        shapeless.setOutputItemId("minecraft:bundle");

        var view = PlayerPanelViews.carpentry(List.of(shaped, shapeless));
        assertThat(view.recipes()).hasSize(2);
        assertThat(view.recipes()).extracting(PlayerPanels.CarpentryRow::id)
                .containsExactly("storynpcs:bundle", "storynpcs:shield");
        var shield = view.recipes().get(1);
        assertThat(shield.shapeless()).isFalse();
        assertThat(shield.ingredientSummary())
                .containsExactlyInAnyOrder("minecraft:oak_planks x5",
                        "minecraft:iron_ingot x2");
        assertThat(PlayerPanelViews.carpentry(null).recipes()).isEmpty();
    }

    @Test
    void viewRecordsJsonRoundTrip() {
        var progression = progression();
        var id = NamespacedId.of("storynpcs:rescue");
        var state = new QuestProgressState(id);
        state.setStatus(QuestProgressState.Status.IN_PROGRESS);
        progression.getQuests().put(id, state);
        var registry = new DefinitionRegistry();
        registry.registerQuest(new Quest(id, "Rescue"));

        var json = com.storynpcs.domain.role.RoleSerde.toJson(
                PlayerPanelViews.questLog(registry, progression));
        var decoded = com.storynpcs.domain.role.RoleSerde.fromJson(
                json, PlayerPanels.QuestLogView.class);
        assertThat(decoded).isPresent();
        assertThat(decoded.get().quests()).hasSize(1);
        assertThat(decoded.get().quests().get(0).title()).isEqualTo("Rescue");
    }
}
