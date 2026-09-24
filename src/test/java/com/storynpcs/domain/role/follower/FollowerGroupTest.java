package com.storynpcs.domain.role.follower;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("FollowerGroup Dynamic Slot Allocation Tests")
class FollowerGroupTest {
    private FollowerGroup group;

    @BeforeEach
    void setUp() {
        group = new FollowerGroup();
    }

    @Test
    @DisplayName("Sequential slot allocation for multiple followers of the same leader")
    void testSequentialSlotAllocation() {
        UUID leader = UUID.randomUUID();
        UUID f1 = UUID.randomUUID();
        UUID f2 = UUID.randomUUID();
        UUID f3 = UUID.randomUUID();

        int s1 = group.getOrAssignSlot(leader, f1);
        int s2 = group.getOrAssignSlot(leader, f2);
        int s3 = group.getOrAssignSlot(leader, f3);

        assertEquals(0, s1, "First follower gets slot 0");
        assertEquals(1, s2, "Second follower gets slot 1");
        assertEquals(2, s3, "Third follower gets slot 2");

        // Requesting existing follower should return same slot
        assertEquals(0, group.getOrAssignSlot(leader, f1));
        assertEquals(1, group.getOrAssignSlot(leader, f2));
        assertEquals(3, group.getFollowerCount(leader));
    }

    @Test
    @DisplayName("Unregistering a follower removes it from group")
    void testUnregisterFollower() {
        UUID leader = UUID.randomUUID();
        UUID f1 = UUID.randomUUID();
        UUID f2 = UUID.randomUUID();

        group.getOrAssignSlot(leader, f1);
        group.getOrAssignSlot(leader, f2);
        assertEquals(2, group.getFollowerCount(leader));

        group.unregister(leader, f1);
        assertEquals(1, group.getFollowerCount(leader));
        // f2 is now the first follower (slot 0)
        assertEquals(0, group.getOrAssignSlot(leader, f2));
    }
}
