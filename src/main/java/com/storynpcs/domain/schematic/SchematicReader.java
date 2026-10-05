package com.storynpcs.domain.schematic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Schematic container reader (issue #149): Sponge {@code .schem} v2/v3 and the
 * legacy MCEdit {@code .schematic} format, detected from the NBT root shape.
 *
 * <p>Bounded on every axis: the compressed payload is accounted (8 MiB),
 * dimensions are capped by {@link Schematic#MAX_DIMENSION} /
 * {@link Schematic#MAX_BLOCKS}, and malformed or truncated containers raise
 * {@link SchematicParseException} with {@code source: reason}.
 *
 * <p>Legacy numeric ids resolve through {@link LegacyBlockTable}; ids with no
 * table entry map to air and are counted in the schematic's diagnostics so a
 * lossy import is loud, never silent.
 */
public final class SchematicReader {

    /** Compressed-NBT size ceiling for one schematic file. */
    private static final long MAX_COMPRESSED_NBT = 8L * 1024 * 1024;
    private static final int MAX_PALETTE = 1 << 20;
    private static final int MAX_BLOCK_ENTITIES = 1 << 16;

    /** Reads a schematic container (gzip or uncompressed NBT). */
    public static Schematic read(String source, byte[] bytes) throws SchematicParseException {
        CompoundTag root = readContainer(source, bytes);
        if (root.contains("Palette") && root.contains("BlockData")) {
            return readSponge(source, root);
        }
        if (root.contains("Blocks") && root.contains("Data")) {
            return readLegacy(source, root);
        }
        throw new SchematicParseException(source,
                "unrecognized schematic container (neither Sponge .schem nor legacy .schematic)");
    }

    private static CompoundTag readContainer(String source, byte[] bytes)
            throws SchematicParseException {
        if (bytes == null || bytes.length == 0) {
            throw new SchematicParseException(source, "empty file");
        }
        var accounter = net.minecraft.nbt.NbtAccounter.create(MAX_COMPRESSED_NBT);
        try (var in = new ByteArrayInputStream(bytes)) {
            CompoundTag tag = net.minecraft.nbt.NbtIo.readCompressed(in, accounter);
            if (tag != null) return tag;
        } catch (Exception ignored) {
            // Not gzip — fall through to the plain NBT stream.
        }
        try (var in = new java.io.DataInputStream(new ByteArrayInputStream(bytes))) {
            CompoundTag tag = net.minecraft.nbt.NbtIo.read(in, accounter);
            if (tag != null) return tag;
        } catch (Exception e) {
            throw new SchematicParseException(source, "cannot read NBT container (" + e.getMessage() + ")", e);
        }
        throw new SchematicParseException(source, "empty NBT container");
    }

    // ---- Sponge .schem (v2/v3) ----

    private static Schematic readSponge(String source, CompoundTag root)
            throws SchematicParseException {
        int version = root.getInt("Version");
        if (version != 2 && version != 3) {
            throw new SchematicParseException(source,
                    "unsupported Sponge schematic version " + version + " (expected 2 or 3)");
        }
        int width = dim(source, root, "Width");
        int height = dim(source, root, "Height");
        int length = dim(source, root, "Length");
        int[] blocks = requireIndexArray(source, width, height, length);

        CompoundTag paletteTag = root.getCompound("Palette");
        int paletteMax = root.getInt("PaletteMax");
        if (paletteTag.size() > MAX_PALETTE || paletteMax > MAX_PALETTE) {
            throw new SchematicParseException(source, "palette exceeds bound " + MAX_PALETTE);
        }
        List<String> diagnostics = new ArrayList<>();
        if (paletteMax != 0 && paletteMax != paletteTag.size()) {
            diagnostics.add("PaletteMax=" + paletteMax + " disagrees with palette size "
                    + paletteTag.size());
        }
        String[] palette = new String[paletteTag.size()];
        for (String name : paletteTag.getAllKeys()) {
            int index = paletteTag.getInt(name);
            if (index < 0 || index >= palette.length) {
                throw new SchematicParseException(source,
                        "palette index out of range for '" + name + "'");
            }
            palette[index] = name;
        }
        // Duplicate palette indices leave holes; a referenced hole would render
        // as air — fill them explicitly and report so the loss is visible.
        int holes = 0;
        for (int i = 0; i < palette.length; i++) {
            if (palette[i] == null) {
                palette[i] = "minecraft:air";
                holes++;
            }
        }
        if (holes > 0) {
            diagnostics.add(holes + " unassigned palette index(es) treated as air");
        }

        byte[] blockData = root.getByteArray("BlockData");
        if (blockData.length == 0) {
            throw new SchematicParseException(source, "missing BlockData");
        }
        decodeVarintIndices(source, blockData, blocks, palette.length);

        List<Schematic.BlockEntityRecord> blockEntities = readBlockEntities(
                source, root, "BlockEntities", width, height, length, diagnostics);

        return new Schematic(source, width, height, length,
                List.of(palette), blocks, blockEntities, diagnostics);
    }

    /** BlockData stores palette indices as little-endian varints. */
    private static void decodeVarintIndices(String source, byte[] data, int[] out, int paletteSize)
            throws SchematicParseException {
        int i = 0;
        int cursor = 0;
        while (i < out.length) {
            int value = 0;
            int shift = 0;
            byte b;
            do {
                if (cursor >= data.length) {
                    throw new SchematicParseException(source,
                            "BlockData truncated: " + i + " of " + out.length + " indices decoded");
                }
                b = data[cursor++];
                value |= (b & 0x7F) << shift;
                shift += 7;
                if (shift > 35) {
                    throw new SchematicParseException(source, "BlockData varint overflow");
                }
            } while ((b & 0x80) != 0);
            if (value < 0 || value >= paletteSize) {
                throw new SchematicParseException(source,
                        "BlockData palette index " + value + " out of range");
            }
            out[i++] = value;
        }
    }

    // ---- Legacy .schematic (MCEdit) ----

    private static Schematic readLegacy(String source, CompoundTag root)
            throws SchematicParseException {
        int width = dim(source, root, "Width");
        int height = dim(source, root, "Height");
        int length = dim(source, root, "Length");

        byte[] rawBlocks = root.getByteArray("Blocks");
        if (rawBlocks.length == 0) {
            throw new SchematicParseException(source, "missing Blocks");
        }
        byte[] rawData = root.getByteArray("Data");
        byte[] rawAdd = root.getByteArray("AddBlocks");
        int expected = width * height * length;
        if (rawBlocks.length != expected) {
            throw new SchematicParseException(source,
                    "Blocks length " + rawBlocks.length + " != volume " + expected);
        }
        if (rawData.length != 0 && rawData.length != expected) {
            throw new SchematicParseException(source,
                    "Data length " + rawData.length + " != volume " + expected);
        }

        // First pass: translate every cell to a palette name, building the
        // palette on demand; unmapped ids become air + diagnostics.
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        paletteIndex.put("minecraft:air", 0);
        List<String> palette = new ArrayList<>(List.of("minecraft:air"));
        int[] blocks = new int[expected];
        Map<Integer, Integer> unmapped = new TreeMap<>();
        for (int i = 0; i < expected; i++) {
            int id = rawBlocks[i] & 0xFF;
            if (i < rawAdd.length * 2) {
                int nibble = (rawAdd[i >> 1] >> ((i & 1) << 2)) & 0xF;
                id |= nibble << 8;
            }
            int data = i < rawData.length ? rawData[i] & 0xFF : 0;
            if (id == 0) {
                continue; // air
            }
            Optional<String> name = LegacyBlockTable.resolve(id, data);
            if (name.isEmpty()) {
                unmapped.merge(id, 1, Integer::sum);
                continue;
            }
            int idx = paletteIndex.computeIfAbsent(name.get(), n -> {
                palette.add(n);
                return palette.size() - 1;
            });
            blocks[i] = idx;
        }

        List<String> diagnostics = new ArrayList<>();
        List<Schematic.BlockEntityRecord> blockEntities = readBlockEntities(
                source, root, "TileEntities", width, height, length, diagnostics);
        if (!unmapped.isEmpty()) {
            diagnostics.add("unmapped legacy block ids (mapped to air): "
                    + unmapped.keySet().stream().map(String::valueOf)
                            .collect(java.util.stream.Collectors.joining(","))
                    + " (" + unmapped.values().stream().mapToInt(Integer::intValue).sum()
                    + " cells)");
        }
        if (!blockEntities.isEmpty()) {
            diagnostics.add("carried " + blockEntities.size()
                    + " block-entity payloads (applied only when the placed block entity's"
                    + " type matches; legacy item fields are not translated)");
        }

        return new Schematic(source, width, height, length, palette, blocks,
                blockEntities, diagnostics);
    }

    // ---- shared helpers ----

    private static int dim(String source, CompoundTag root, String key)
            throws SchematicParseException {
        int value = root.contains(key) ? root.getInt(key) : 0;
        if (value == 0) {
            value = root.getShort(key);
        }
        if (value <= 0 || value > Schematic.MAX_DIMENSION) {
            throw new SchematicParseException(source,
                    key + "=" + value + " outside [1," + Schematic.MAX_DIMENSION + "]");
        }
        return value;
    }

    private static int[] requireIndexArray(String source, int width, int height, int length)
            throws SchematicParseException {
        long volume = (long) width * height * length;
        if (volume > Schematic.MAX_BLOCKS) {
            throw new SchematicParseException(source,
                    "volume " + volume + " exceeds max blocks " + Schematic.MAX_BLOCKS);
        }
        return new int[(int) volume];
    }

    private static List<Schematic.BlockEntityRecord> readBlockEntities(
            String source, CompoundTag root, String key,
            int width, int height, int length, List<String> diagnostics)
            throws SchematicParseException {
        if (!root.contains(key)) {
            return List.of();
        }
        ListTag list = root.getList(key, Tag.TAG_COMPOUND);
        if (list.size() > MAX_BLOCK_ENTITIES) {
            throw new SchematicParseException(source,
                    key + " has " + list.size() + " entries (max " + MAX_BLOCK_ENTITIES + ")");
        }
        List<Schematic.BlockEntityRecord> out = new ArrayList<>();
        int dropped = 0;
        for (Tag tag : list) {
            if (!(tag instanceof CompoundTag entry)) continue;
            int[] pos = entry.getIntArray("Pos");
            int x, y, z;
            if (pos.length == 3) {
                x = pos[0];
                y = pos[1];
                z = pos[2];
            } else if (entry.contains("x") && entry.contains("y") && entry.contains("z")) {
                // Legacy TileEntities carry x/y/z fields.
                x = entry.getInt("x");
                y = entry.getInt("y");
                z = entry.getInt("z");
            } else {
                dropped++;
                continue;
            }
            // An out-of-footprint payload would apply to an unrelated loaded
            // block entity at build time — drop it with a diagnostic.
            if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) {
                dropped++;
                continue;
            }
            String id = entry.contains("Id") ? entry.getString("Id")
                    : entry.getString("id");
            if (id.isEmpty()) continue;
            CompoundTag data = entry.copy();
            data.remove("Pos");
            data.remove("x");
            data.remove("y");
            data.remove("z");
            data.remove("Id");
            data.remove("id");
            out.add(new Schematic.BlockEntityRecord(x, y, z, id, data));
        }
        if (dropped > 0) {
            diagnostics.add("dropped " + dropped
                    + " malformed/out-of-footprint block-entity entries");
        }
        return out;
    }

    private SchematicReader() {}
}
