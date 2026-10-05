package com.storynpcs.domain.schematic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reader contract for issue #149: both container formats parse into the same
 * {@link Schematic} model, malformed containers fail with {@code source:
 * reason} diagnostics, and bounds reject oversized payloads.
 */
class SchematicReaderTest {

    /** Builds a minimal Sponge .schem v2 byte payload. */
    private static byte[] spongeSchem(int w, int h, int l, List<String> palette, int[] indices)
            throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 2);
        root.putInt("DataVersion", 3700);
        root.putShort("Width", (short) w);
        root.putShort("Height", (short) h);
        root.putShort("Length", (short) l);
        CompoundTag paletteTag = new CompoundTag();
        for (int i = 0; i < palette.size(); i++) {
            paletteTag.putInt(palette.get(i), i);
        }
        root.put("Palette", paletteTag);
        root.putInt("PaletteMax", palette.size());
        // Varint-encode the indices.
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int idx : indices) {
            int v = idx;
            while ((v & ~0x7F) != 0) {
                raw.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            raw.write(v);
        }
        root.putByteArray("BlockData", raw.toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(root, out);
        return out.toByteArray();
    }

    /**
     * Builds a spec-conformant Sponge .schem v3 payload: the NBT root holds a
     * {@code Schematic} compound carrying Version/DataVersion/dimensions, and
     * the whole block container — Palette, varint {@code Data},
     * BlockEntities — nested under its {@code Blocks} child.
     */
    private static byte[] spongeSchemV3(int w, int h, int l, List<String> palette, int[] indices,
                                        ListTag blockEntities)
            throws IOException {
        CompoundTag root = new CompoundTag();
        CompoundTag schematic = new CompoundTag();
        schematic.putInt("Version", 3);
        schematic.putInt("DataVersion", 3955);
        schematic.putShort("Width", (short) w);
        schematic.putShort("Height", (short) h);
        schematic.putShort("Length", (short) l);
        CompoundTag blocksTag = new CompoundTag();
        CompoundTag paletteTag = new CompoundTag();
        for (int i = 0; i < palette.size(); i++) {
            paletteTag.putInt(palette.get(i), i);
        }
        blocksTag.put("Palette", paletteTag);
        blocksTag.putInt("PaletteMax", palette.size());
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int idx : indices) {
            int v = idx;
            while ((v & ~0x7F) != 0) {
                raw.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            raw.write(v);
        }
        blocksTag.putByteArray("Data", raw.toByteArray());
        if (blockEntities != null) {
            blocksTag.put("BlockEntities", blockEntities);
        }
        schematic.put("Blocks", blocksTag);
        root.put("Schematic", schematic);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(root, out);
        return out.toByteArray();
    }

    /** Builds a legacy .schematic payload (gzip NBT, numeric ids). */
    private static byte[] legacySchematic(int w, int h, int l, byte[] blocks, byte[] data)
            throws IOException {
        CompoundTag root = new CompoundTag();
        root.putShort("Width", (short) w);
        root.putShort("Height", (short) h);
        root.putShort("Length", (short) l);
        root.putString("Materials", "Alpha");
        root.putByteArray("Blocks", blocks);
        if (data != null) {
            root.putByteArray("Data", data);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(root, out);
        return out.toByteArray();
    }

    @Test
    void spongeSchemParsesDimsPaletteAndVarintIndices() throws Exception {
        // 2x1x1: stone at (0,0,0), air at (1,0,0).
        byte[] bytes = spongeSchem(2, 1, 1,
                List.of("minecraft:stone", "minecraft:air"), new int[]{0, 1});
        Schematic s = SchematicReader.read("test.schem", bytes);
        assertThat(s.width()).isEqualTo(2);
        assertThat(s.height()).isEqualTo(1);
        assertThat(s.palette()).containsExactly("minecraft:stone", "minecraft:air");
        assertThat(s.paletteNameAt(0, 0, 0)).contains("minecraft:stone");
        assertThat(s.paletteNameAt(1, 0, 0)).contains("minecraft:air");
        assertThat(s.diagnostics()).isEmpty();
    }

    @Test
    void spongeSchemDecodesMultibyteVarintIndices() throws Exception {
        // Palette >127 forces two-byte varints.
        List<String> palette = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            palette.add("minecraft:fake_" + i);
        }
        byte[] bytes = spongeSchem(2, 1, 1, palette, new int[]{129, 0});
        Schematic s = SchematicReader.read("wide.schem", bytes);
        assertThat(s.paletteNameAt(0, 0, 0)).contains("minecraft:fake_129");
    }

    @Test
    void spongeSchemV3ParsesNestedBlocksContainer() throws Exception {
        // Regression: real v3 files wrap the schematic in a "Schematic"
        // compound and nest Palette/Data/BlockEntities under its "Blocks"
        // child — a root-shape check that only knows the v2 flat layout
        // rejects them as unrecognized.
        byte[] bytes = spongeSchemV3(2, 1, 1,
                List.of("minecraft:stone", "minecraft:air"), new int[]{0, 1}, null);
        Schematic s = SchematicReader.read("v3.schem", bytes);
        assertThat(s.width()).isEqualTo(2);
        assertThat(s.height()).isEqualTo(1);
        assertThat(s.length()).isEqualTo(1);
        assertThat(s.palette()).containsExactly("minecraft:stone", "minecraft:air");
        assertThat(s.paletteNameAt(0, 0, 0)).contains("minecraft:stone");
        assertThat(s.paletteNameAt(1, 0, 0)).contains("minecraft:air");
    }

    @Test
    void spongeSchemV3BlockEntitiesUnwrapNestedData() throws Exception {
        // v3 (and spec-conformant v2) block-entity entries are
        // {Pos: int[3], Id: string, Data: {payload}} — the payload must be
        // the Data compound itself, not a wrapper leaking "Data"/"Id" keys.
        ListTag bes = new ListTag();
        CompoundTag chest = new CompoundTag();
        chest.putIntArray("Pos", new int[]{0, 0, 0});
        chest.putString("Id", "minecraft:chest");
        CompoundTag payload = new CompoundTag();
        payload.putString("LootTable", "minecraft:chests/simple_dungeon");
        chest.put("Data", payload);
        bes.add(chest);
        byte[] bytes = spongeSchemV3(1, 1, 1,
                List.of("minecraft:chest"), new int[]{0}, bes);
        Schematic s = SchematicReader.read("v3be.schem", bytes);
        assertThat(s.blockEntities()).hasSize(1);
        var be = s.blockEntities().get(0);
        assertThat(be.id()).isEqualTo("minecraft:chest");
        assertThat(be.x()).isEqualTo(0);
        assertThat(be.data().getString("LootTable"))
                .isEqualTo("minecraft:chests/simple_dungeon");
        assertThat(be.data().contains("Data")).isFalse();
        assertThat(be.data().contains("Id")).isFalse();
        assertThat(be.data().contains("Pos")).isFalse();
    }

    @Test
    void nestedBlocksContainerRejectsNonV3Versions() throws Exception {
        byte[] bytes = spongeSchemV3(1, 1, 1, List.of("minecraft:stone"), new int[]{0}, null);
        CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(
                new java.io.ByteArrayInputStream(bytes),
                net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        root.getCompound("Schematic").putInt("Version", 2); // nested container claims v2
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(root, out);
        assertThatThrownBy(() -> SchematicReader.read("badv.schem", out.toByteArray()))
                .isInstanceOf(SchematicParseException.class)
                .hasMessageContaining("version 2");
    }

    @Test
    void spongeSchemV3ReportsUnplacedEntitiesAndBiomes() throws Exception {
        // Entities/Biomes live on the Schematic body — a wrapped v3 file
        // carrying them must surface the "not placed" diagnostic.
        byte[] bytes = spongeSchemV3(1, 1, 1, List.of("minecraft:stone"), new int[]{0}, null);
        CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(
                new java.io.ByteArrayInputStream(bytes),
                net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        CompoundTag schematic = root.getCompound("Schematic");
        ListTag entities = new ListTag();
        CompoundTag ent = new CompoundTag();
        ent.putString("Id", "minecraft:creeper");
        entities.add(ent);
        schematic.put("Entities", entities);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(root, out);
        Schematic s = SchematicReader.read("v3ent.schem", out.toByteArray());
        assertThat(s.diagnostics()).anyMatch(d -> d.contains("Entities/Biomes"));
    }

    @Test
    void unwrappedNestedBlocksLayoutIsTolerated() throws Exception {
        // Some tools emit Version 3 with a root-level "Blocks" compound (no
        // "Schematic" wrapper). Non-spec but unambiguous — same parse.
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 3);
        root.putShort("Width", (short) 1);
        root.putShort("Height", (short) 1);
        root.putShort("Length", (short) 1);
        CompoundTag blocksTag = new CompoundTag();
        CompoundTag paletteTag = new CompoundTag();
        paletteTag.putInt("minecraft:stone", 0);
        blocksTag.put("Palette", paletteTag);
        blocksTag.putByteArray("Data", new byte[]{0});
        root.put("Blocks", blocksTag);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(root, out);
        Schematic s = SchematicReader.read("unwrapped.schem", out.toByteArray());
        assertThat(s.paletteNameAt(0, 0, 0)).contains("minecraft:stone");
    }

    @Test
    void spongeSchemV1ReadsTileEntitiesSection() throws Exception {
        // v1 named the block-entity section "TileEntities" (renamed to
        // "BlockEntities" in v2) — accepting flat v1 must not silently drop
        // the payloads. v1's PaletteMax is a byte count, not an entry count,
        // so a byte-count value must not trip the mismatch diagnostic either.
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 1);
        root.putShort("Width", (short) 1);
        root.putShort("Height", (short) 1);
        root.putShort("Length", (short) 1);
        CompoundTag paletteTag = new CompoundTag();
        paletteTag.putInt("minecraft:chest", 0);
        root.put("Palette", paletteTag);
        root.putInt("PaletteMax", 3); // v1 semantics: bytes, not entries
        root.putByteArray("BlockData", new byte[]{0});
        ListTag tiles = new ListTag();
        CompoundTag chest = new CompoundTag();
        chest.putIntArray("Pos", new int[]{0, 0, 0});
        chest.putString("Id", "minecraft:chest");
        CompoundTag payload = new CompoundTag();
        payload.putString("LootTable", "minecraft:chests/simple_dungeon");
        chest.put("Data", payload);
        tiles.add(chest);
        root.put("TileEntities", tiles);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(root, out);
        Schematic s = SchematicReader.read("v1.schem", out.toByteArray());
        assertThat(s.paletteNameAt(0, 0, 0)).contains("minecraft:chest");
        assertThat(s.blockEntities()).hasSize(1);
        assertThat(s.blockEntities().get(0).id()).isEqualTo("minecraft:chest");
        assertThat(s.blockEntities().get(0).data().getString("LootTable"))
                .isEqualTo("minecraft:chests/simple_dungeon");
        assertThat(s.diagnostics()).noneMatch(d -> d.contains("PaletteMax"));
    }

    @Test
    void emptyEntitiesAndBiomesSectionsStaySilent() throws Exception {
        // Spec-conformant writers emit empty Entities/Biomes unconditionally —
        // the "not placed" diagnostic must only fire when content is lost.
        byte[] bytes = spongeSchem(1, 1, 1, List.of("minecraft:stone"), new int[]{0});
        CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(
                new java.io.ByteArrayInputStream(bytes),
                net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        root.put("Entities", new ListTag());
        root.put("Biomes", new CompoundTag());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(root, out);
        Schematic s = SchematicReader.read("quiet.schem", out.toByteArray());
        assertThat(s.diagnostics()).noneMatch(d -> d.contains("Entities/Biomes"));
    }

    @Test
    void nonEmptyEntitiesSectionStillReports() throws Exception {
        byte[] bytes = spongeSchem(1, 1, 1, List.of("minecraft:stone"), new int[]{0});
        CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(
                new java.io.ByteArrayInputStream(bytes),
                net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        ListTag entities = new ListTag();
        CompoundTag ent = new CompoundTag();
        ent.putString("Id", "minecraft:creeper");
        entities.add(ent);
        root.put("Entities", entities);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(root, out);
        Schematic s = SchematicReader.read("loud.schem", out.toByteArray());
        assertThat(s.diagnostics()).anyMatch(d -> d.contains("Entities/Biomes"));
    }

    @Test
    void legacySchematicTranslatesNumericIdsThroughBlockTable() throws Exception {
        // 3x1x1: stone(1), oak planks(5:0), oak stairs(53, facing=east)
        byte[] bytes = legacySchematic(3, 1, 1,
                new byte[]{1, 5, 53}, new byte[]{0, 0, 0});
        Schematic s = SchematicReader.read("house.schematic", bytes);
        assertThat(s.paletteNameAt(0, 0, 0)).contains("minecraft:stone");
        assertThat(s.paletteNameAt(1, 0, 0)).contains("minecraft:oak_planks");
        assertThat(s.paletteNameAt(2, 0, 0)).contains("minecraft:oak_stairs[facing=east,half=bottom]");
    }

    @Test
    void legacyUnmappedIdsBecomeAirWithDiagnostics() throws Exception {
        byte[] bytes = legacySchematic(2, 1, 1,
                new byte[]{1, (byte) 254}, new byte[]{0, 0}); // 254 = unknown id
        Schematic s = SchematicReader.read("odd.schematic", bytes);
        assertThat(s.paletteNameAt(0, 0, 0)).contains("minecraft:stone");
        assertThat(s.paletteNameAt(1, 0, 0)).contains("minecraft:air");
        assertThat(s.diagnostics()).anyMatch(d -> d.contains("unmapped legacy block ids")
                && d.contains("254"));
    }

    @Test
    void legacyEntitiesAndTileTicksAreReportedNotPlaced() throws Exception {
        // All 28 bundled target assets are legacy files and several carry
        // Entities (minecarts, ...) — dropping them silently would break the
        // loud-loss contract the Sponge path already honors.
        byte[] bytes = legacySchematic(1, 1, 1, new byte[]{1}, new byte[]{0});
        CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(
                new java.io.ByteArrayInputStream(bytes),
                net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        ListTag entities = new ListTag();
        CompoundTag cart = new CompoundTag();
        cart.putString("id", "minecraft:minecart");
        entities.add(cart);
        root.put("Entities", entities);
        ListTag ticks = new ListTag();
        ticks.add(new CompoundTag());
        root.put("TileTicks", ticks);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(root, out);
        Schematic s = SchematicReader.read("legacy-ent.schematic", out.toByteArray());
        assertThat(s.diagnostics())
                .anyMatch(d -> d.contains("Entities") && d.contains("not placed")
                        && d.contains("1"))
                .anyMatch(d -> d.contains("TileTicks") && d.contains("dropped"));
    }

    @Test
    void legacyStairAndSlabOrientationBitsSurvive() throws Exception {
        // oak stairs data=5 → facing west (1) + upside-down (bit4)
        byte[] bytes = legacySchematic(2, 1, 1,
                new byte[]{53, 44}, new byte[]{5, 8}); // stairs; stone slab upper
        Schematic s = SchematicReader.read("orient.schematic", bytes);
        assertThat(s.paletteNameAt(0, 0, 0))
                .contains("minecraft:oak_stairs[facing=west,half=top]");
        assertThat(s.paletteNameAt(1, 0, 0))
                .contains("minecraft:smooth_stone_slab[type=top]");
    }

    @Test
    void malformedContainersFailWithSourceAndReason() {
        assertThatThrownBy(() -> SchematicReader.read("empty.schem", new byte[0]))
                .isInstanceOf(SchematicParseException.class)
                .hasMessageContaining("empty.schem").hasMessageContaining("empty");
        assertThatThrownBy(() -> SchematicReader.read("junk.schem", new byte[]{1, 2, 3}))
                .isInstanceOf(SchematicParseException.class)
                .hasMessageContaining("junk.schem");
    }

    @Test
    void unsupportedSpongeVersionFails() throws Exception {
        byte[] bytes = spongeSchem(1, 1, 1, List.of("minecraft:stone"), new int[]{0});
        // Rewrite the Version tag to 99 by reparsing the payload.
        CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(
                new java.io.ByteArrayInputStream(bytes),
                net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        root.putInt("Version", 99);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(root, out);
        assertThatThrownBy(() -> SchematicReader.read("v99.schem", out.toByteArray()))
                .isInstanceOf(SchematicParseException.class)
                .hasMessageContaining("version 99");
    }

    @Test
    void truncatedBlockDataFails() throws Exception {
        byte[] bytes = spongeSchem(3, 1, 1, List.of("minecraft:stone"), new int[]{0, 0, 0});
        CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(
                new java.io.ByteArrayInputStream(bytes),
                net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        root.putByteArray("BlockData", new byte[]{0}); // 1 varint for 3 slots
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(root, out);
        assertThatThrownBy(() -> SchematicReader.read("trunc.schem", out.toByteArray()))
                .isInstanceOf(SchematicParseException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void oversizedDimensionsReject() throws Exception {
        byte[] bytes = legacySchematic(Schematic.MAX_DIMENSION + 1, 1, 1,
                new byte[]{1}, new byte[]{0}); // block array deliberately short
        assertThatThrownBy(() -> SchematicReader.read("huge.schematic", bytes))
                .isInstanceOf(SchematicParseException.class)
                .hasMessageContaining("huge.schematic")
                .hasMessageContaining("outside");
    }

    @Test
    void legacyBlockEntitiesCarryPositionAndPayload() throws Exception {
        CompoundTag root = new CompoundTag();
        root.putShort("Width", (short) 1);
        root.putShort("Height", (short) 1);
        root.putShort("Length", (short) 1);
        root.putString("Materials", "Alpha");
        root.putByteArray("Blocks", new byte[]{54}); // chest
        root.putByteArray("Data", new byte[]{0});
        ListTag tiles = new ListTag();
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putInt("x", 0);
        chest.putInt("y", 0);
        chest.putInt("z", 0);
        chest.putString("LootTable", "minecraft:chests/simple_dungeon");
        tiles.add(chest);
        root.put("TileEntities", tiles);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(root, out);
        Schematic s = SchematicReader.read("loot.schematic", out.toByteArray());
        assertThat(s.blockEntities()).hasSize(1);
        assertThat(s.blockEntities().get(0).id()).isEqualTo("minecraft:chest");
        assertThat(s.blockEntities().get(0).data().getString("LootTable"))
                .isEqualTo("minecraft:chests/simple_dungeon");
    }

    @Test
    void outOfFootprintBlockEntitiesAreDroppedWithDiagnostic() throws Exception {
        // Regression: a crafted container could point a BE payload outside the
        // footprint and the placer would overwrite an unrelated loaded entity.
        CompoundTag root = new CompoundTag();
        root.putShort("Width", (short) 1);
        root.putShort("Height", (short) 1);
        root.putShort("Length", (short) 1);
        root.putString("Materials", "Alpha");
        root.putByteArray("Blocks", new byte[]{54});
        root.putByteArray("Data", new byte[]{0});
        ListTag tiles = new ListTag();
        CompoundTag hostile = new CompoundTag();
        hostile.putString("id", "minecraft:chest");
        hostile.putInt("x", 200); // out of a 1x1x1 footprint
        hostile.putInt("y", 0);
        hostile.putInt("z", 0);
        hostile.putString("Items", "hostile-payload");
        tiles.add(hostile);
        CompoundTag malformed = new CompoundTag();
        malformed.putString("id", "minecraft:chest");
        malformed.putIntArray("Pos", new int[]{0, 0}); // malformed length
        tiles.add(malformed);
        root.put("TileEntities", tiles);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(root, out);
        Schematic s = SchematicReader.read("hostile.schematic", out.toByteArray());
        assertThat(s.blockEntities()).isEmpty();
        assertThat(s.diagnostics()).anyMatch(d -> d.contains("block-entity")
                && d.contains("2"));
    }

    @Test
    void paletteHolesAndPaletteMaxMismatchAreDiagnosed() throws Exception {
        // Craft a palette where index 1 is never assigned (a "hole") and
        // PaletteMax disagrees with the actual palette size.
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 2);
        root.putShort("Width", (short) 1);
        root.putShort("Height", (short) 1);
        root.putShort("Length", (short) 1);
        CompoundTag paletteTag = new CompoundTag();
        paletteTag.putInt("minecraft:stone", 0);
        paletteTag.putInt("minecraft:dirt", 0);  // duplicate: index 1 unassigned
        paletteTag.putInt("minecraft:bricks", 2);
        root.put("Palette", paletteTag);
        root.putInt("PaletteMax", 7);
        root.putByteArray("BlockData", new byte[]{0});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(root, out);
        Schematic s = SchematicReader.read("holes.schem", out.toByteArray());
        assertThat(s.palette().get(1)).isEqualTo("minecraft:air");
        assertThat(s.diagnostics()).anyMatch(d -> d.contains("palette index"));
        assertThat(s.diagnostics()).anyMatch(d -> d.contains("PaletteMax"));
    }
}
