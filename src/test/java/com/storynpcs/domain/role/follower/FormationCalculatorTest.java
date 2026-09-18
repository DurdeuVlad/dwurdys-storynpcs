package com.storynpcs.domain.role.follower;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FormationCalculator & FormationOffset Tests")
class FormationCalculatorTest {

    private static final double EPSILON = 1e-4;

    @Test
    @DisplayName("COLUMN formation offsets are strictly longitudinal behind the leader")
    void testColumnFormation() {
        double spacing = 2.0;

        FormationOffset slot0 = FormationCalculator.computeOffset(FormationType.COLUMN, 0, spacing);
        assertEquals(0.0, slot0.lateral(), EPSILON);
        assertEquals(-2.0, slot0.longitudinal(), EPSILON);

        FormationOffset slot1 = FormationCalculator.computeOffset(FormationType.COLUMN, 1, spacing);
        assertEquals(0.0, slot1.lateral(), EPSILON);
        assertEquals(-4.0, slot1.longitudinal(), EPSILON);

        FormationOffset slot2 = FormationCalculator.computeOffset(FormationType.COLUMN, 2, spacing);
        assertEquals(0.0, slot2.lateral(), EPSILON);
        assertEquals(-6.0, slot2.longitudinal(), EPSILON);
    }

    @Test
    @DisplayName("WEDGE formation alternates flanks behind leader in V-shape")
    void testWedgeFormation() {
        double spacing = 2.0;

        FormationOffset slot0 = FormationCalculator.computeOffset(FormationType.WEDGE, 0, spacing);
        assertTrue(slot0.lateral() < 0, "Slot 0 should be on left flank");
        assertEquals(-2.0, slot0.longitudinal(), EPSILON, "Slot 0 should be behind leader");

        FormationOffset slot1 = FormationCalculator.computeOffset(FormationType.WEDGE, 1, spacing);
        assertTrue(slot1.lateral() > 0, "Slot 1 should be on right flank");
        assertEquals(-2.0, slot1.longitudinal(), EPSILON, "Slot 1 should be behind leader");
        assertEquals(-slot0.lateral(), slot1.lateral(), EPSILON, "Flanks should be symmetric");

        FormationOffset slot2 = FormationCalculator.computeOffset(FormationType.WEDGE, 2, spacing);
        assertTrue(slot2.lateral() < slot0.lateral(), "Slot 2 should be wider on left");
        assertTrue(slot2.longitudinal() < slot0.longitudinal(), "Slot 2 should be further back");
    }

    @Test
    @DisplayName("ROW formation flanks side-by-side with zero longitudinal offset")
    void testRowFormation() {
        double spacing = 2.5;

        FormationOffset slot0 = FormationCalculator.computeOffset(FormationType.ROW, 0, spacing);
        assertEquals(-2.5, slot0.lateral(), EPSILON, "Slot 0 on left");
        assertEquals(0.0, slot0.longitudinal(), EPSILON, "Row has 0 longitudinal delta");

        FormationOffset slot1 = FormationCalculator.computeOffset(FormationType.ROW, 1, spacing);
        assertEquals(2.5, slot1.lateral(), EPSILON, "Slot 1 on right");
        assertEquals(0.0, slot1.longitudinal(), EPSILON);
    }

    @Test
    @DisplayName("CIRCLE formation distributes slots radially around the leader")
    void testCircleFormation() {
        double radius = 3.0;

        FormationOffset slot0 = FormationCalculator.computeOffset(FormationType.CIRCLE, 0, radius);
        // Angle 0: lateral = 0, longitudinal = -3 (directly behind/north)
        assertEquals(0.0, slot0.lateral(), EPSILON);
        assertEquals(-3.0, slot0.longitudinal(), EPSILON);

        FormationOffset slot2 = FormationCalculator.computeOffset(FormationType.CIRCLE, 2, radius);
        // Angle pi/2: lateral = +3 (right/east), longitudinal = 0
        assertEquals(3.0, slot2.lateral(), EPSILON);
        assertEquals(0.0, slot2.longitudinal(), EPSILON);
    }

    @Test
    @DisplayName("World position rotation correctly maps Minecraft yaw coordinates")
    void testWorldCoordinateRotation() {
        double leaderX = 100.0;
        double leaderY = 64.0;
        double leaderZ = 200.0;

        FormationOffset behindTwo = FormationOffset.of(0.0, -2.0);

        // Facing South (yaw = 0): -2 longitudinal is North (Z - 2)
        var posSouth = FormationCalculator.toWorldCoordinates(leaderX, leaderY, leaderZ, 0.0F, behindTwo);
        assertEquals(100.0, posSouth.x(), EPSILON);
        assertEquals(64.0, posSouth.y(), EPSILON);
        assertEquals(198.0, posSouth.z(), EPSILON);

        // Facing West (yaw = 90): -2 longitudinal is East (X + 2)
        var posWest = FormationCalculator.toWorldCoordinates(leaderX, leaderY, leaderZ, 90.0F, behindTwo);
        assertEquals(102.0, posWest.x(), EPSILON);
        assertEquals(64.0, posWest.y(), EPSILON);
        assertEquals(200.0, posWest.z(), EPSILON);

        // Facing North (yaw = 180): -2 longitudinal is South (Z + 2)
        var posNorth = FormationCalculator.toWorldCoordinates(leaderX, leaderY, leaderZ, 180.0F, behindTwo);
        assertEquals(100.0, posNorth.x(), EPSILON);
        assertEquals(64.0, posNorth.y(), EPSILON);
        assertEquals(202.0, posNorth.z(), EPSILON);

        // Facing East (yaw = 270): -2 longitudinal is West (X - 2)
        var posEast = FormationCalculator.toWorldCoordinates(leaderX, leaderY, leaderZ, 270.0F, behindTwo);
        assertEquals(98.0, posEast.x(), EPSILON);
        assertEquals(64.0, posEast.y(), EPSILON);
        assertEquals(200.0, posEast.z(), EPSILON);
    }

    @Test
    @DisplayName("computeOffset clamps excessive slotIndex and spacing safely")
    void testComputeOffsetBounds() {
        FormationOffset offsetMax = FormationCalculator.computeOffset(FormationType.COLUMN, 99999, 100.0);
        assertEquals(0.0, offsetMax.lateral(), EPSILON);
        assertEquals(-(16.0 + 64 * 16.0), offsetMax.longitudinal(), EPSILON);

        FormationOffset offsetNegative = FormationCalculator.computeOffset(FormationType.COLUMN, -5, -2.0);
        assertEquals(0.0, offsetNegative.lateral(), EPSILON);
        assertEquals(-2.0, offsetNegative.longitudinal(), EPSILON);
    }
}
