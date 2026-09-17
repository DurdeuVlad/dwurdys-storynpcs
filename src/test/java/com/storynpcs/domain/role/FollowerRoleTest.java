package com.storynpcs.domain.role;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.api.event.FollowerStateChangeEvent;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.role.follower.FollowerRole;
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

class FollowerRoleTest {

    private StoryNpcsApplicationService service;
    private List<FollowerStateChangeEvent> stateEvents;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        DefinitionRegistry registry = new DefinitionRegistry();
        ProgressionRepository repo = new ProgressionRepository(tempDir);
        EventPublisher publisher = new EventPublisher();
        stateEvents = new ArrayList<>();
        publisher.register(event -> {
            if (event instanceof FollowerStateChangeEvent fe) {
                stateEvents.add(fe);
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
}