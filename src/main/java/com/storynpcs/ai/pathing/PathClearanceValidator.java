package com.storynpcs.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Validates 3D spatial clearance for entity paths to prevent corner-cutting wedging and block clipping.
 */
public final class PathClearanceValidator {

    private PathClearanceValidator() {}

    /**
     * Checks whether an entity with given dimensions can traverse directly between two points
     * without colliding with walls, corners, or stepping into hazardous or open air drops.
     *
     * @param level  The Minecraft level
     * @param start  Starting point
     * @param target Target point
     * @param width  Entity bounding box width
     * @param height Entity bounding box height
     * @return true if the full swept box volume is unobstructed
     */
    public static boolean isPathClear(Level level, Vec3 start, Vec3 target, float width, float height) {
        if (level == null || start == null || target == null) {
            return false;
        }

        double dx = target.x - start.x;
        double dy = target.y - start.y;
        double dz = target.z - start.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist < 0.25) {
            return true;
        }

        // Check step height delta (cannot step up more than 1.1 blocks in a single direct segment)
        if (dy > 1.15) {
            return false;
        }

        double halfW = (width / 2.0F) + 0.05F; // 5cm safety padding
        int steps = Math.max(2, (int) Math.ceil(dist / 0.45)); // Sample every ~0.45 blocks

        for (int i = 0; i <= steps; i++) {
            double fraction = (double) i / (double) steps;
            double cx = start.x + dx * fraction;
            double cy = start.y + dy * fraction;
            double cz = start.z + dz * fraction;

            // Bounding box at sample point
            AABB sampleBox = new AABB(
                    cx - halfW, cy, cz - halfW,
                    cx + halfW, cy + height, cz + halfW
            );

            // 1. Collision check
            if (!level.noCollision(sampleBox)) {
                return false;
            }

            // 2. Ground check (ensure there is solid footing beneath within 1.25 blocks)
            BlockPos groundPos = BlockPos.containing(cx, cy - 0.2, cz);
            BlockState groundState = level.getBlockState(groundPos);
            BlockState deeperState = level.getBlockState(groundPos.below());

            boolean hasFooting = groundState.isSolid() || deeperState.isSolid();
            if (!hasFooting) {
                // Potential cliff drop
                return false;
            }
        }

        return true;
    }
}
