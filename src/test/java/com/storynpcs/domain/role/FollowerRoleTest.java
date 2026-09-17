package com.storynpcs.domain.role;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.api.event.FollowerFormationChangeEvent;
import com.storynpcs.api.event.FollowerStateChangeEvent;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.role.follower.FollowerRole;
import com.storynpcs.domain.role.follower.FormationType;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FollowerRole & Formation Operations Tests")
class FollowerRoleTest {

    private StoryNpcsApplicationService service;
    private List<FollowerStateChangeEvent> stateEvents;
    private List<FollowerFormationChangeEvent> formationEvents;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        DefinitionRegistry registry = new DefinitionRegistry();
        ProgressionRepository repo = new ProgressionRepository(tempDir);
        EventPublisher publisher = new EventPublisher();
        stateEvents = new ArrayList<>();
        formationEvents = new ArrayList<>();
        publisher.register(event -> {
            if (event instanceof FollowerStateChangeEvent fe) {
                stateEvents.add(fe);
            } else if (event instanceof FollowerFormationChangeEvent ffe) {
                formationEvents.add(ffe);
            }
        });

        service = new StoryNpcsApplicationService(registry, repo, publisher);
    }

    @Test
    @DisplayName("FollowerRole ownership check allows owner to change state and denies strangers")
    void testFollowerOwnership() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        NamespacedId npcId = NamespacedId.of("storynpcs:mercenary");

        FollowerRole role = new FollowerRole(owner);
        assertEquals(FollowerRole.State.FOLLOWING, role.getState());

        // Stranger tries to command
        boolean strangerResult = service.setFollowerState(stranger, npcId, role, FollowerRole.State.STAYING);
        assertFalse(strangerResult, "Stranger should not be able to command follower");
        assertEquals(FollowerRole.State.FOLLOWING, role.getState());
        assertEquals(0, stateEvents.size());

        // Owner commands follower
        boolean ownerResult = service.setFollowerState(owner, npcId, role, FollowerRole.State.GUARDING);
        assertTrue(ownerResult, "Owner should be able to command follower");
        assertEquals(FollowerRole.State.GUARDING, role.getState());
        assertEquals(1, stateEvents.size());
        assertEquals(FollowerRole.State.FOLLOWING, stateEvents.get(0).previousState());
        assertEquals(FollowerRole.State.GUARDING, stateEvents.get(0).newState());
    }

    @Test
    @DisplayName("Owner can change formation pattern, slot, and spacing with event published")
    void testChangeFormation() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        NamespacedId npcId = NamespacedId.of("storynpcs:guard");

        FollowerRole role = new FollowerRole(owner);
        assertEquals(FormationType.WEDGE, role.getFormation());

        // Stranger denied
        boolean strangerResult = service.setFollowerFormation(stranger, npcId, role, FormationType.COLUMN, 1, 3.0);
        assertFalse(strangerResult);
        assertEquals(FormationType.WEDGE, role.getFormation());
        assertEquals(0, formationEvents.size());

        // Owner succeeds
        boolean ownerResult = service.setFollowerFormation(owner, npcId, role, FormationType.COLUMN, 2, 3.5);
        assertTrue(ownerResult);
        assertEquals(FormationType.COLUMN, role.getFormation());
        assertEquals(2, role.getFormationSlot());
        assertEquals(3.5, role.getFormationSpacing());

        assertEquals(1, formationEvents.size());
        FollowerFormationChangeEvent event = formationEvents.get(0);
        assertEquals(owner, event.playerUuid());
        assertEquals(npcId, event.npcId());
        assertEquals(FormationType.WEDGE, event.previousFormation());
        assertEquals(FormationType.COLUMN, event.newFormation());
        assertEquals(2, event.slotIndex());
        assertEquals(3.5, event.spacing());
    }
}