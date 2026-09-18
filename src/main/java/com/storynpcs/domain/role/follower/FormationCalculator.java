package com.storynpcs.domain.role.follower;

/**
 * Computes tactical formation positions and rotates them into Minecraft world coordinates
 * according to the leader's heading.
 */
public final class FormationCalculator {

    private FormationCalculator() {}

    public record WorldPosition(double x, double y, double z) {}

    /**
     * Computes the relative displacement in the leader's local frame.
     *
     * @param type      The tactical formation pattern
     * @param slotIndex The zero-indexed position within the formation
     * @param spacing   Base distance between formation slots (in blocks)
     * @return Local formation offset
     */
    public static FormationOffset computeOffset(FormationType type, int slotIndex, double spacing) {
        if (slotIndex < 0) {
            slotIndex = 0;
        }
        if (slotIndex > 64) {
            slotIndex = 64;
        }
        if (spacing <= 0.0) {
            spacing = 2.0;
        }
        if (spacing > 16.0) {
            spacing = 16.0;
        }
        if (type == null) {
            type = FormationType.WEDGE;
        }

        switch (type) {
            case COLUMN -> {
                // Single-file: slot 0 is 1*spacing behind, slot 1 is 2*spacing behind, etc.
                double longitudinal = -(spacing + slotIndex * spacing);
                return FormationOffset.of(0.0, longitudinal);
            }
            case WEDGE -> {
                // V-formation: slot 0 left-rear, slot 1 right-rear, etc.
                int tier = (slotIndex / 2) + 1;
                double side = (slotIndex % 2 == 0) ? -1.0 : 1.0;
                double lateral = side * tier * (spacing * 0.85);
                double longitudinal = -tier * spacing;
                return FormationOffset.of(lateral, longitudinal);
            }
            case ROW -> {
                // Line abreast: flanking left and right at same longitudinal depth
                int tier = (slotIndex / 2) + 1;
                double side = (slotIndex % 2 == 0) ? -1.0 : 1.0;
                double lateral = side * tier * spacing;
                return FormationOffset.of(lateral, 0.0);
            }
            case CIRCLE -> {
                // Perimeter ring: 8 positions around leader
                int maxSlots = 8;
                int normalizedSlot = slotIndex % maxSlots;
                double angleRad = (2.0 * Math.PI * normalizedSlot) / maxSlots;
                double lateral = spacing * Math.sin(angleRad);
                double longitudinal = -spacing * Math.cos(angleRad);
                return FormationOffset.of(lateral, longitudinal);
            }
            case ECHELON_LEFT -> {
                int step = slotIndex + 1;
                return FormationOffset.of(-step * spacing, -step * spacing);
            }
            case ECHELON_RIGHT -> {
                int step = slotIndex + 1;
                return FormationOffset.of(step * spacing, -step * spacing);
            }
            default -> {
                return FormationOffset.of(0.0, -spacing);
            }
        }
    }

    /**
     * Converts a local formation offset into absolute Minecraft world coordinates
     * rotated by the leader's yaw angle.
     *
     * In Minecraft's coordinate system:
     * - +X is East, -X is West
     * - +Z is South, -Z is North
     * - yaw = 0 is South (+Z)
     * - yaw = 90 is West (-X)
     * - yaw = 180 is North (-Z)
     * - yaw = 270 is East (+X)
     */
    public static WorldPosition toWorldCoordinates(
            double leaderX, double leaderY, double leaderZ,
            float leaderYaw,
            FormationOffset offset
    ) {
        double yawRad = Math.toRadians(leaderYaw);
        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);

        // Delta X = -longitudinal * sin(yaw) - lateral * cos(yaw)
        // Delta Z = +longitudinal * cos(yaw) - lateral * sin(yaw)
        double dx = -offset.longitudinal() * sin - offset.lateral() * cos;
        double dz = offset.longitudinal() * cos - offset.lateral() * sin;
        double dy = offset.vertical();

        return new WorldPosition(leaderX + dx, leaderY + dy, leaderZ + dz);
    }
}
