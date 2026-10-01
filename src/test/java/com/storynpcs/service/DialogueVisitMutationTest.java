package com.storynpcs.service;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.*;
import com.storynpcs.domain.progression.PlayerProgression;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.yaml.DefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #51 — dialogue node visits were the last session-driven progression
 * write issued by raw field mutation inside the service, with no durable save.
 * These tests pin the typed-boundary behavior: visits recorded through
 * {@link PlayerProgressionActionRequest} + authorization, durable persistence
 * on change, idempotent repeats, per-dialogue scoping, and side-effect-free
 * denials.
 */
class DialogueVisitMutationTest {
    @TempDir
    Path tempDir;

    private DefinitionRegistry registry;
    private ProgressionRepository progressionRepository;
    private StoryNpcsApplicationService service;

    @BeforeEach
    void setUp() {
        registry = new DefinitionRegistry();
        progressionRepository = new ProgressionRepository(tempDir);
        service = new StoryNpcsApplicationService(registry, progressionRepository, new EventPublisher());
    }

    private NamespacedId registerDialogue(String id) {
        NamespacedId dialogueId = NamespacedId.of(id);
        DialogueGraph graph = new DialogueGraph(dialogueId, "Visit Dialogue", "start");
        DialogueNode start = new DialogueNode("start", "Hello.");
        start.addOption(new DialogueEdge("Go on", "next"));
        graph.addNode(start);
        graph.addNode(new DialogueNode("next", "Next node."));
        registry.registerDialogue(graph);
        return dialogueId;
    }

    private PlayerProgressionActionRequest request(String actorType, UUID actorId, UUID subject, int permission) {
        return new PlayerProgressionActionRequest(
                "dialogue.visit.record", actorType, actorId, subject, UUID.randomUUID(), permission);
    }

    @Test
    void startDialogueRecordsRootVisitThroughTypedBoundaryAndPersists() {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId dialogueId = registerDialogue("storynpcs:visit_dialogue");

        service.startDialogue(playerUuid, dialogueId);

        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        assertThat(progression.hasVisitedDialogueNode(dialogueId, "start")).isTrue();

        // Durable: a fresh repository instance over the same store sees the visit.
        ProgressionRepository reloaded = new ProgressionRepository(tempDir);
        assertThat(reloaded.getOrCreate(playerUuid)
                .hasVisitedDialogueNode(dialogueId, "start")).isTrue();
    }

    @Test
    void advancingAlongEdgeRecordsTargetNodeVisit() {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId dialogueId = registerDialogue("storynpcs:visit_dialogue");

        service.startDialogue(playerUuid, dialogueId);
        service.chooseDialogueOption(playerUuid, 0);

        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        assertThat(progression.hasVisitedDialogueNode(dialogueId, "start")).isTrue();
        assertThat(progression.hasVisitedDialogueNode(dialogueId, "next")).isTrue();
    }

    @Test
    void visitsAreScopedPerDialogueSoSharedNodeIdsDoNotCollide() {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId first = registerDialogue("storynpcs:visit_one");
        NamespacedId second = registerDialogue("storynpcs:visit_two");

        service.startDialogue(playerUuid, first);
        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        assertThat(progression.hasVisitedDialogueNode(first, "start")).isTrue();
        // Same node id in a different dialogue is a distinct, unrecorded visit.
        assertThat(progression.hasVisitedDialogueNode(second, "start")).isFalse();

        service.startDialogue(playerUuid, second);
        assertThat(progression.hasVisitedDialogueNode(second, "start")).isTrue();
    }

    @Test
    void repeatVisitIsIdempotentNoOp() {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId dialogueId = registerDialogue("storynpcs:visit_dialogue");

        AuthorizedActionResult first = service.recordDialogueNodeVisit(
                request("dialogue", playerUuid, playerUuid, -1), dialogueId, "start");
        assertThat(first.applied()).isTrue();

        // Already visited — allowed actor but no mutation, no write.
        AuthorizedActionResult repeat = service.recordDialogueNodeVisit(
                request("dialogue", playerUuid, playerUuid, -1), dialogueId, "start");
        assertThat(repeat.applied()).isFalse();
        assertThat(repeat.decision().allowed()).isTrue();
    }

    @Test
    void deniedActorsCannotRecordVisitsAndProduceDiagnostics() {
        UUID subject = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        NamespacedId dialogueId = registerDialogue("storynpcs:visit_dialogue");

        // Script actors are denied outright.
        AuthorizedActionResult script = service.recordDialogueNodeVisit(
                request("script", subject, subject, -1), dialogueId, "start");
        assertThat(script.applied()).isFalse();
        assertThat(script.decision().allowed()).isFalse();
        assertThat(script.decision().code()).isEqualTo("SCRIPT_CAPABILITY_REQUIRED");

        // Dialogue actors may not act on another player's progression.
        AuthorizedActionResult crossSubject = service.recordDialogueNodeVisit(
                request("dialogue", other, subject, -1), dialogueId, "start");
        assertThat(crossSubject.applied()).isFalse();
        assertThat(crossSubject.decision().code()).isEqualTo("PLAYER_SUBJECT_MISMATCH");

        // Command actors acting on others need permission level 2.
        AuthorizedActionResult lowCommand = service.recordDialogueNodeVisit(
                request("command", other, subject, 1), dialogueId, "start");
        assertThat(lowCommand.applied()).isFalse();
        assertThat(lowCommand.decision().code()).isEqualTo("PERMISSION_DENIED");

        // Even allowed actors cannot record nodes the dialogue does not contain.
        AuthorizedActionResult unknownNode = service.recordDialogueNodeVisit(
                request("system", other, subject, -1), dialogueId, "bogus");
        assertThat(unknownNode.applied()).isFalse();
        assertThat(unknownNode.decision().code()).isEqualTo("DIALOGUE_NODE_NOT_FOUND");

        // Side-effect-free: nothing was recorded on the subject.
        assertThat(progressionRepository.getOrCreate(subject).getVisitedDialogueNodes()).isEmpty();

        // And the allowed counterparts succeed: system actor and command level 2.
        assertThat(service.recordDialogueNodeVisit(
                request("system", other, subject, -1), dialogueId, "start").applied()).isTrue();
        assertThat(service.recordDialogueNodeVisit(
                request("command", other, subject, 2), dialogueId, "next").applied()).isTrue();
        PlayerProgression progression = progressionRepository.getOrCreate(subject);
        assertThat(progression.hasVisitedDialogueNode(dialogueId, "start")).isTrue();
        assertThat(progression.hasVisitedDialogueNode(dialogueId, "next")).isTrue();
    }
}
