package com.storynpcs.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.progression.PlayerProgression;
import com.storynpcs.domain.role.follower.FollowerRole;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.yaml.YamlDefinitionLoader;

/** P9-3: service ops backing the new command-parity leaves. */
class P93CommandParityTest {

    @TempDir
    Path tempDir;

    private static final NamespacedId FACTION = NamespacedId.of("storynpcs:villagers");
    private static final NamespacedId DIALOGUE = NamespacedId.of("storynpcs:innkeeper");
    private static final NamespacedId NPC = NamespacedId.of("storynpcs:guard");

    // ── faction remove (target /noppes faction remove) ───────────────────────

    @Test
    void factionRemoveDeletesTheStandingEntry() throws Exception {
        var service = service();
        var player = UUID.randomUUID();

        var set = service.mutateFactionProgression(FactionProgressionMutationRequest.set(
                "command", null, player, FACTION, 500,
                service.currentFactionProgressionRevision(player), UUID.randomUUID(), 2));
        assertThat(set.applied()).isTrue();

        var remove = service.mutateFactionProgression(FactionProgressionMutationRequest.remove(
                "command", null, player, FACTION,
                service.currentFactionProgressionRevision(player), UUID.randomUUID(), 2));
        assertThat(remove.applied()).isTrue();

        // Entry gone — read-back falls through to the faction default again.
        var progression = progressionRepository.getOrCreate(player);
        assertThat(progression.getFactionScore(FACTION, 1000)).isEqualTo(1000);
        assertThat(progression.getFactionPoints()).doesNotContainKey(FACTION);
    }

    @Test
    void factionRemoveIsReplaySafeAndActorScoped() throws Exception {
        var service = service();
        var player = UUID.randomUUID();
        var requestId = UUID.randomUUID();

        service.mutateFactionProgression(FactionProgressionMutationRequest.set(
                "command", null, player, FACTION, 500,
                service.currentFactionProgressionRevision(player), UUID.randomUUID(), 2));
        var request = FactionProgressionMutationRequest.remove(
                "command", null, player, FACTION,
                service.currentFactionProgressionRevision(player), requestId, 2);
        var first = service.mutateFactionProgression(request);
        assertThat(first.applied()).isTrue();
        // Replay: same requestId resolves to the recorded outcome, not a second apply.
        var replay = service.mutateFactionProgression(request);
        assertThat(replay.applied()).isTrue();
        assertThat(replay.duplicate()).isTrue();
    }

    // ── dialogue read markers (target /noppes dialog read|unread) ────────────

    @Test
    void markDialogueReadThenClearRoundTrips() throws Exception {
        var registry = registryWithDialogue();
        var service = service(registry);
        var player = UUID.randomUUID();

        var mark = service.markDialogueRead(actionRequest("dialogue.mark.read", player), DIALOGUE);
        assertThat(mark.applied()).isTrue();
        var progression = progressionRepository.getOrCreate(player);
        assertThat(progression.hasVisitedDialogueNode(DIALOGUE, "start")).isTrue();
        assertThat(progression.hasVisitedDialogueNode(DIALOGUE, "end")).isTrue();

        var clear = service.clearDialogueReadMarkers(
                actionRequest("dialogue.mark.clear", player), DIALOGUE);
        assertThat(clear.applied()).isTrue();
        progression = progressionRepository.getOrCreate(player);
        assertThat(progression.hasVisitedDialogueNode(DIALOGUE, "start")).isFalse();
    }

    @Test
    void markReadRejectsUnknownDialogue() throws Exception {
        var service = service();
        var result = service.markDialogueRead(
                actionRequest("dialogue.mark.read", UUID.randomUUID()), DIALOGUE);
        assertThat(result.applied()).isFalse();
        assertThat(result.decision().code()).isEqualTo("DIALOGUE_NOT_FOUND");
    }

    @Test
    void clearVisitsOnlyRemovesTheTargetDialogueScope() {
        var progression = new PlayerProgression(UUID.randomUUID());
        progression.recordDialogueNodeVisit(DIALOGUE, "start");
        progression.recordDialogueNodeVisit(NamespacedId.of("storynpcs:other"), "start");
        assertThat(progression.clearDialogueVisits(DIALOGUE)).isEqualTo(1);
        assertThat(progression.hasVisitedDialogueNode(DIALOGUE, "start")).isFalse();
        assertThat(progression.hasVisitedDialogueNode(
                NamespacedId.of("storynpcs:other"), "start")).isTrue();
    }

    // ── follower owner (target /noppes owner) ────────────────────────────────

    @Test
    void followerOwnerSetReassignsThroughCanonicalMutation() throws Exception {
        var service = service();
        var owner = UUID.randomUUID();
        var newOwner = UUID.randomUUID();
        var role = new FollowerRole(owner);

        var request = FollowerStateMutationRequest.setOwner(
                "player", owner, owner, NPC, newOwner, UUID.randomUUID(), 0);
        var result = service.mutateFollowerState(request, role);
        assertThat(result.applied()).isTrue();
        assertThat(result.events()).contains("FollowerOwnerChangeEvent");
        assertThat(role.isOwnedBy(newOwner)).isTrue();
    }

    @Test
    void followerOwnerSetDeniedForNonOwnerSubject() throws Exception {
        var service = service();
        var owner = UUID.randomUUID();
        var stranger = UUID.randomUUID();
        var role = new FollowerRole(owner);

        var request = FollowerStateMutationRequest.setOwner(
                "player", stranger, stranger, NPC, UUID.randomUUID(), UUID.randomUUID(), 0);
        var result = service.mutateFollowerState(request, role);
        assertThat(result.applied()).isFalse();
        assertThat(result.formatReport()).contains("FOLLOWER_NOT_OWNED");
        assertThat(role.isOwnedBy(owner)).isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static PlayerProgressionActionRequest actionRequest(String operation, UUID player) {
        return new PlayerProgressionActionRequest(
                operation, "command", null, player, UUID.randomUUID(), 2);
    }

    private DefinitionRegistry registryWithDialogue() throws Exception {
        var registry = new DefinitionRegistry();
        var graph = new com.storynpcs.domain.dialogue.DialogueGraph(DIALOGUE, "Innkeeper", "start");
        graph.addNode(new com.storynpcs.domain.dialogue.DialogueNode("start", "Hello."));
        graph.addNode(new com.storynpcs.domain.dialogue.DialogueNode("end", "Bye."));
        registry.registerDialogue(graph);
        return registry;
    }

    private ProgressionRepository progressionRepository;

    private StoryNpcsApplicationService service() throws Exception {
        return service(new DefinitionRegistry());
    }

    private StoryNpcsApplicationService service(DefinitionRegistry registry) throws Exception {
        Files.createDirectories(tempDir.resolve("progression"));
        var loader = new YamlDefinitionLoader(registry);
        loader.loadDirectory(tempDir);
        registry.registerFaction(new Faction(FACTION, "Villagers", 1000, 0, 2000));
        progressionRepository = new ProgressionRepository(tempDir.resolve("progression"));
        var service = new StoryNpcsApplicationService(
                registry, progressionRepository, new EventPublisher());
        service.setLoader(loader);
        return service;
    }
}
