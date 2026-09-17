package com.storynpcs.ai;

import com.storynpcs.domain.role.follower.FollowerGroup;
import com.storynpcs.domain.role.follower.FollowerRole;
import com.storynpcs.domain.role.follower.FormationCalculator;
import com.storynpcs.domain.role.follower.FormationOffset;
import com.storynpcs.domain.role.follower.FormationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Follower Formation Logic & Goal Tests")
class FollowerFormationGoalTest {

    @Test
    @DisplayName("FormationType fromString parses valid strings and defaults gracefully")
    void testFormationTypeParsing() {
        assertEquals(FormationType.WEDGE, FormationType.fromString("wedge"));
        assertEquals(FormationType.COLUMN, FormationType.fromString("COLUMN"));
        assertEquals(FormationType.ROW, FormationType.fromString("Row"));
        assertEquals(FormationType.CIRCLE, FormationType.fromString("circle"));
        assertEquals(FormationType.ECHELON_LEFT, FormationType.fromString("echelon_left"));
        assertEquals(FormationType.ECHELON_RIGHT, FormationType.fromString("ECHELON_RIGHT"));
        assertEquals(FormationType.WEDGE, FormationType.fromString("unknown_formation"));
        assertEquals(FormationType.WEDGE, FormationType.fromString(null));
    }

    @Test
    @DisplayName("FollowerRole default values match expected formation escort settings")
    void testFollowerRoleDefaults() {
        UUID owner = UUID.randomUUID();
        FollowerRole role = new FollowerRole(owner);

        assertEquals(FollowerRole.State.FOLLOWING, role.getState());
        assertEquals(FormationType.WEDGE, role.getFormation());
        assertEquals(-1, role.getFormationSlot(), "Default slot should be -1 for auto-assignment");
        assertEquals(2.5, role.getFormationSpacing(), 1e-4);
        assertTrue(role.isOwnedBy(owner));
        assertFalse(role.isOwnedBy(UUID.randomUUID()));
    }

    @Test
    @DisplayName("Follower in auto slot mode dynamically resolves unique position in formation")
    void testAutoSlotFormationTarget() {
        UUID leader = UUID.randomUUID();
        UUID npc1 = UUID.randomUUID();
        UUID npc2 = UUID.randomUUID();

        FollowerGroup.clearAll();

        FollowerRole role1 = new FollowerRole(leader, FormationType.WEDGE, -1, 2.0);
        FollowerRole role2 = new FollowerRole(leader, FormationType.WEDGE, -1, 2.0);

        int slot1 = (role1.getFormationSlot() < 0) ? FollowerGroup.getOrAssignSlot(leader, npc1) : role1.getFormationSlot();
        int slot2 = (role2.getFormationSlot() < 0) ? FollowerGroup.getOrAssignSlot(leader, npc2) : role2.getFormationSlot();

        assertEquals(0, slot1);
        assertEquals(1, slot2);

        FormationOffset offset1 = FormationCalculator.computeOffset(role1.getFormation(), slot1, role1.getFormationSpacing());
        FormationOffset offset2 = FormationCalculator.computeOffset(role2.getFormation(), slot2, role2.getFormationSpacing());

        // Left and right flanks in wedge
        assertTrue(offset1.lateral() < 0.0, "Slot 0 should be left flank");
        assertTrue(offset2.lateral() > 0.0, "Slot 1 should be right flank");
        assertEquals(offset1.longitudinal(), offset2.longitudinal(), 1e-4, "Both should be at same tier depth");
    }
}
