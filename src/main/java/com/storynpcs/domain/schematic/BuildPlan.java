package com.storynpcs.domain.schematic;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure build-planning for a {@link Schematic} (issue #149): the ordered,
 * rotated placement list plus the block-entity payloads to apply afterwards.
 * Positions are local offsets; the placer translates them to world coords
 * under its per-tick budget, so planning stays cheap and testable.
 */
public record BuildPlan(List<Placement> placements, List<Schematic.BlockEntityRecord> blockEntities) {

    /** One rotated local-offset placement; {@code palette} names the state. */
    public record Placement(int dx, int dy, int dz, String palette) {}

    /**
     * Plans {@code quarterTurns} 90° clockwise turns around Y at the origin.
     * Rotating the footprint (not the block states) — the placer rotates each
     * resolved {@code BlockState} with the matching {@code Rotation}.
     */
    public static BuildPlan of(Schematic schematic, int quarterTurns) {
        int turns = Math.floorMod(quarterTurns, 4);
        List<Placement> placements = new ArrayList<>();
        int[] blocks = schematic.blocks();
        int w = schematic.width();
        int h = schematic.height();
        int l = schematic.length();
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    int paletteIndex = blocks[(y * l + z) * w + x];
                    if (paletteIndex < 0 || paletteIndex >= schematic.palette().size()) {
                        continue; // reader bounds-checks; guard hand-built schematics too
                    }
                    String name = schematic.palette().get(paletteIndex);
                    if (name == null || name.isEmpty()
                            || name.equals("minecraft:air")
                            || name.equals("minecraft:structure_void")) {
                        continue;
                    }
                    int[] rotated = rotateOffset(x, z, w, l, turns);
                    placements.add(new Placement(rotated[0], y, rotated[1], name));
                }
            }
        }
        List<Schematic.BlockEntityRecord> rotatedBe = new ArrayList<>();
        for (var be : schematic.blockEntities()) {
            int[] rotated = rotateOffset(be.x(), be.z(), w, l, turns);
            rotatedBe.add(new Schematic.BlockEntityRecord(
                    rotated[0], be.y(), rotated[1], be.id(), be.data()));
        }
        return new BuildPlan(placements, rotatedBe);
    }

    /** Rotates a local x/z offset within a w×l footprint by 90°×turns (CW). */
    static int[] rotateOffset(int x, int z, int width, int length, int turns) {
        return switch (turns) {
            case 1 -> new int[]{length - 1 - z, x};
            case 2 -> new int[]{width - 1 - x, length - 1 - z};
            case 3 -> new int[]{z, width - 1 - x};
            default -> new int[]{x, z};
        };
    }

    /** Rotated footprint width (x extent) for bounds checks. */
    public static int rotatedWidth(Schematic s, int quarterTurns) {
        return Math.floorMod(quarterTurns, 4) % 2 == 0 ? s.width() : s.length();
    }

    public static int rotatedLength(Schematic s, int quarterTurns) {
        return Math.floorMod(quarterTurns, 4) % 2 == 0 ? s.length() : s.width();
    }
}
