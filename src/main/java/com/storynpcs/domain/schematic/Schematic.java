package com.storynpcs.domain.schematic;

import java.util.List;

/**
 * Immutable parsed schematic (issue #149): a cuboid of palette-indexed blocks
 * in Y/Z/X-major order — {@code index = (y * length + z) * width + x}, matching
 * both the Sponge .schem and legacy .schematic layouts.
 *
 * <p>Palette entries are modern block-state strings
 * ({@code "minecraft:oak_stairs[facing=east]"}); legacy numeric ids are already
 * resolved by the reader through {@link LegacyBlockTable}. {@code "minecraft:air"}
 * and {@code "minecraft:structure_void"} entries are placement no-ops.
 */
public record Schematic(
        String name,
        int width,
        int height,
        int length,
        List<String> palette,
        int[] blocks,
        List<BlockEntityRecord> blockEntities,
        List<String> diagnostics
) {

    /** Carried block-entity payload (chest contents, sign text, ...). */
    public record BlockEntityRecord(int x, int y, int z, String id, net.minecraft.nbt.CompoundTag data) {}

    /** Upper bounds enforced at read time — oversized containers reject. */
    public static final int MAX_DIMENSION = 512;
    public static final int MAX_BLOCKS = 1_048_576;

    public Schematic {
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new IllegalArgumentException("schematic dimensions must be positive");
        }
        if (width > MAX_DIMENSION || height > MAX_DIMENSION || length > MAX_DIMENSION) {
            throw new IllegalArgumentException(
                    "schematic exceeds max dimension " + MAX_DIMENSION);
        }
        if (blocks == null || blocks.length != width * height * length) {
            throw new IllegalArgumentException("block index length does not match dimensions");
        }
        blocks = blocks.clone(); // int[] is mutable — keep the record immutable
        palette = palette == null ? List.of() : List.copyOf(palette);
        blockEntities = blockEntities == null ? List.of() : List.copyOf(blockEntities);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public int volume() {
        return width * height * length;
    }

    /** Defensive copy — the index array must not leak mutably. */
    @Override
    public int[] blocks() {
        return blocks.clone();
    }

    /** Palette name for the block at local coords; empty when out of range. */
    public java.util.Optional<String> paletteNameAt(int x, int y, int z) {
        if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= length) {
            return java.util.Optional.empty();
        }
        int paletteIndex = blocks[(y * length + z) * width + x];
        if (paletteIndex < 0 || paletteIndex >= palette.size()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(palette.get(paletteIndex));
    }
}
