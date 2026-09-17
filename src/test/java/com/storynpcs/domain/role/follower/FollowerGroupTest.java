package com.storynpcs.domain.role.follower;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("FollowerGroup Dynamic Slot Allocation Tests")
class FollowerGroupTest {

    @BeforeEach
    void setUp() {
        FollowerGroup.clearAll();
    }

    @Test
    @DisplayName("Sequential slot allocation for multiple followers of the same leader")
    void testSequentialSlotAllocation() {
        UUID leader = UUID.randomUUID();
        UUID f1 = UUID.randomUUID();
        UUID f2 = UUID.randomUUID();
        UUID f3 = UUID.randomUUID();

        int s1 = FollowerGroup.getOrAssignSlot(leader, f1);
        int s2 = FollowerGroup.getOrAssignSlot(leader, f2);
        int s3 = FollowerGroup.getOrAssignSlot(leader, f3);

        assertEquals(0, s1, "First follower gets slot 0");
        assertEquals(1, s2, "Second follower gets slot 1");
        assertEquals(2, s3, "Third follower gets slot 2");

        // Requesting existing follower should return same slot
        assertEquals(0, FollowerGroup.getOrAssignSlot(leader, f1));
        assertEquals(1, FollowerGroup.getOrAssignSlot(leader, f2));
        assertEquals(3, FollowerGroup.getFollowerCount(leader));
    }

    @Test
    @DisplayName("Unregistering a follower removes it from group")
    void testUnregisterFollower() {
        UUID leader = UUID.randomUUID();
        UUID f1 = UUID.randomUUID();
        UUID f2 = UUID.randomUUID();

        FollowerGroup.getOrAssignSlot(leader, f1);
        FollowerGroup.getOrAssignSlot(leader, f2);
        assertEquals(2, FollowerGroup.getFollowerCount(leader));

        FollowerGroup.unregister(leader, f1);
        assertEquals(1, FollowerGroup.getFollowerCount(leader));
        // f2 is now the first follower (slot 0)
        assertEquals(0, FollowerGroup.getOrAssignSlot(leader, f2));
    }
}
