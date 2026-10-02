package com.storynpcs.service;

import com.storynpcs.api.event.DialogueClosedEvent;
import com.storynpcs.api.event.DialogueOpenDeniedEvent;
import com.storynpcs.api.event.DialogueReloadedEvent;
import com.storynpcs.api.event.StoryNpcsEvent;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.dialogue.DialogueCondition;
import com.storynpcs.domain.dialogue.DialogueEdge;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.dialogue.DialogueGraphValidator;
import com.storynpcs.domain.dialogue.DialogueNode;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.api.event.EventPublisher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5-1: graph-level availability gating at open, localization-key typing, and
 * definition-reload session re-evaluation with DialogueReloadedEvent.
 */
class DialogueAvailabilityReloadTest {
    @TempDir
    Path tempDir;

    private DefinitionRegistry registry;
    private StoryNpcsApplicationService service;
    private List<StoryNpcsEvent> publishedEvents;

    @BeforeEach
    void setUp() {
        registry = new DefinitionRegistry();
        publishedEvents = new ArrayList<>();
        EventPublisher eventPublisher = new EventPublisher();
        eventPublisher.register(publishedEvents::add);
        service = new StoryNpcsApplicationService(
                registry, new ProgressionRepository(tempDir), eventPublisher);
    }

    private static DialogueGraph twoNodeGraph(NamespacedId id) {
        DialogueGraph graph = new DialogueGraph(id, "Greeting", "start");
        DialogueNode start = new DialogueNode("start", "Hello.");
        start.addOption(new DialogueEdge("Leave", "end"));
        graph.addNode(start);
        graph.addNode(new DialogueNode("end", "Bye."));
        return graph;
    }

    private <T extends StoryNpcsEvent> List<T> eventsOf(Class<T> type) {
        return publishedEvents.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    void availabilityDeniedOpenPublishesDenyAndCreatesNoSession() {
        NamespacedId factionId = NamespacedId.of("storynpcs:knights");
        registry.registerFaction(new Faction(factionId, "Knights", 1000, 500, 1500));

        NamespacedId dialogueId = NamespacedId.of("storynpcs:gated");
        DialogueGraph graph = twoNodeGraph(dialogueId);
        // Friendly requires >=1500; the default score of 1000 fails.
        graph.getAvailability().add(new DialogueCondition(
                DialogueCondition.Type.FACTION_STANDING, factionId.toString(), "==", "FRIENDLY"));
        registry.registerDialogue(graph);

        UUID player = UUID.randomUUID();
        DialogueView view = service.startDialogue(player, dialogueId);

        assertThat(view.isTerminal()).isTrue();
        assertThat(service.getActiveSession(player)).isEmpty();
        assertThat(eventsOf(DialogueOpenDeniedEvent.class))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.dialogueId()).isEqualTo(dialogueId);
                    assertThat(e.reason()).isEqualTo("AVAILABILITY");
                });
        assertThat(eventsOf(com.storynpcs.api.event.DialogueOpenEvent.class)).isEmpty();
    }

    @Test
    void availabilityPassOpensNormally() {
        NamespacedId factionId = NamespacedId.of("storynpcs:knights");
        registry.registerFaction(new Faction(factionId, "Knights", 1000, 500, 1500));

        NamespacedId dialogueId = NamespacedId.of("storynpcs:gated");
        DialogueGraph graph = twoNodeGraph(dialogueId);
        graph.getAvailability().add(new DialogueCondition(
                DialogueCondition.Type.FACTION_STANDING, factionId.toString(), "==", "NEUTRAL"));
        registry.registerDialogue(graph);

        UUID player = UUID.randomUUID();
        DialogueView view = service.startDialogue(player, dialogueId);

        assertThat(view.nodeId()).isEqualTo("start");
        assertThat(service.getActiveSession(player)).isPresent();
    }

    @Test
    void unchangedMutationRebindsSessionAndReportsAffectedNotClosed() {
        NamespacedId dialogueId = NamespacedId.of("storynpcs:stable");
        DialogueGraph graph = twoNodeGraph(dialogueId);
        registry.registerDialogue(graph);

        UUID player = UUID.randomUUID();
        service.startDialogue(player, dialogueId);
        DialogueGraph before = service.getActiveSession(player).get().getGraph();

        service.mutateDialogue(dialogueId, g -> g.setTitle("Renamed"));

        var session = service.getActiveSession(player);
        assertThat(session).isPresent();
        assertThat(session.get().getGraph()).isNotSameAs(before);
        assertThat(session.get().getGraph().getTitle()).isEqualTo("Renamed");
        assertThat(eventsOf(DialogueReloadedEvent.class))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.dialogueId()).isEqualTo(dialogueId);
                    assertThat(e.affectedSessions()).isEqualTo(1);
                    assertThat(e.closedSessions()).isZero();
                });
        assertThat(eventsOf(DialogueClosedEvent.class)).isEmpty();
    }

    @Test
    void mutationRemovingCurrentNodeClosesSession() {
        NamespacedId dialogueId = NamespacedId.of("storynpcs:unstable");
        DialogueGraph graph = twoNodeGraph(dialogueId);
        // Move the session onto "end"… but keep it alive: a terminal node
        // closes on advance, so instead walk a non-terminal second node.
        DialogueNode mid = new DialogueNode("mid", "Middle.");
        mid.addOption(new DialogueEdge("Done", "end"));
        graph.getNode("start").get().getOptions().add(new DialogueEdge("Next", "mid"));
        graph.addNode(mid);
        registry.registerDialogue(graph);

        UUID player = UUID.randomUUID();
        service.startDialogue(player, dialogueId);
        service.chooseDialogueOption(player, 1); // -> mid
        assertThat(service.getActiveSession(player).get().getCurrentNodeId()).isEqualTo("mid");

        // Delete the node the session is parked on — and the edge into it, so
        // the mutated graph stays valid (a dangling target would fail save).
        service.mutateDialogue(dialogueId, g -> {
            g.getNodes().remove("mid");
            g.getNode("start").ifPresent(n ->
                    n.getOptions().removeIf(e -> "mid".equals(e.getTargetNodeId())));
        });

        assertThat(service.getActiveSession(player)).isEmpty();
        assertThat(eventsOf(DialogueReloadedEvent.class))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.affectedSessions()).isEqualTo(1);
                    assertThat(e.closedSessions()).isEqualTo(1);
                });
        assertThat(eventsOf(DialogueClosedEvent.class))
                .last()
                .satisfies(e -> assertThat(e.reason())
                        .isEqualTo(DialogueClosedEvent.Reason.SERVER_CLOSE));
    }

    @Test
    void tightenedAvailabilityOnMutationClosesSession() {
        NamespacedId factionId = NamespacedId.of("storynpcs:knights");
        registry.registerFaction(new Faction(factionId, "Knights", 1000, 500, 1500));

        NamespacedId dialogueId = NamespacedId.of("storynpcs:tightening");
        registry.registerDialogue(twoNodeGraph(dialogueId));

        UUID player = UUID.randomUUID();
        service.startDialogue(player, dialogueId);
        assertThat(service.getActiveSession(player)).isPresent();

        service.mutateDialogue(dialogueId, g -> g.getAvailability().add(new DialogueCondition(
                DialogueCondition.Type.FACTION_STANDING, factionId.toString(), "==", "FRIENDLY")));

        assertThat(service.getActiveSession(player)).isEmpty();
        assertThat(eventsOf(DialogueReloadedEvent.class))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.affectedSessions()).isEqualTo(1);
                    assertThat(e.closedSessions()).isEqualTo(1);
                });
    }

    @Test
    void deleteDialogueClosesSessionsAndEmitsReloadEvent() {
        NamespacedId dialogueId = NamespacedId.of("storynpcs:doomed");
        registry.registerDialogue(twoNodeGraph(dialogueId));

        UUID player = UUID.randomUUID();
        service.startDialogue(player, dialogueId);

        var result = service.deleteDialogue(new MutationRequest(
                "dialogue.delete", "command", "dialogue.delete", dialogueId, 0L, UUID.randomUUID()));

        assertThat(result.applied()).isTrue();
        assertThat(service.getActiveSession(player)).isEmpty();
        assertThat(eventsOf(DialogueReloadedEvent.class))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.affectedSessions()).isEqualTo(1);
                    assertThat(e.closedSessions()).isEqualTo(1);
                });
    }

    @Test
    void definitionsReloadedHookReEvaluatesAllLiveSessions() {
        NamespacedId kept = NamespacedId.of("storynpcs:kept");
        NamespacedId removed = NamespacedId.of("storynpcs:removed");
        registry.registerDialogue(twoNodeGraph(kept));
        registry.registerDialogue(twoNodeGraph(removed));

        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        service.startDialogue(p1, kept);
        service.startDialogue(p2, removed);

        registry.removeDialogue(removed); // wholesale-swap equivalent
        service.notifyDialogueDefinitionsReloaded();

        assertThat(service.getActiveSession(p1)).isPresent();
        assertThat(service.getActiveSession(p2)).isEmpty();
        assertThat(eventsOf(DialogueReloadedEvent.class))
                .hasSize(2)
                .anySatisfy(e -> {
                    assertThat(e.dialogueId()).isEqualTo(kept);
                    assertThat(e.closedSessions()).isZero();
                })
                .anySatisfy(e -> {
                    assertThat(e.dialogueId()).isEqualTo(removed);
                    assertThat(e.closedSessions()).isEqualTo(1);
                });
    }

    @Test
    void validatorRejectsMalformedLocalizationKeysAndBadAvailability() {
        DialogueGraph graph = twoNodeGraph(NamespacedId.of("storynpcs:keys"));
        graph.setTitleKey("not a key!");
        graph.getNode("start").get().setTextKey("UPPER:CASE");
        graph.getNode("start").get().getOptions().get(0).setTextKey("ok:key/path");
        graph.getAvailability().add(new DialogueCondition(null, "", "==", ""));
        graph.getAvailability().add(new DialogueCondition(
                DialogueCondition.Type.QUEST_STATUS, "storynpcs:q", "==", "IN_PROGRESS"));

        ValidationResult result = new DialogueGraphValidator().validate(graph);

        assertThat(result.getErrors().stream().map(Object::toString).toList())
                .anySatisfy(e -> assertThat(e).contains("DIALOGUE_BAD_LOCALIZATION_KEY").contains("titleKey"))
                .anySatisfy(e -> assertThat(e).contains("DIALOGUE_BAD_LOCALIZATION_KEY").contains("textKey"))
                .anySatisfy(e -> assertThat(e).contains("DIALOGUE_BAD_AVAILABILITY_CONDITION"));
        // "ok:key/path" is valid — only two localization errors.
        assertThat(result.getErrors().stream()
                .filter(e -> e.toString().contains("DIALOGUE_BAD_LOCALIZATION_KEY")).count())
                .isEqualTo(2);
    }
}
