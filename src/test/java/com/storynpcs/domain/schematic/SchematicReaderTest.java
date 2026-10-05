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
                new byte[]{1}, null); // block array deliberately short
        assertThatThrownBy(() -> SchematicReader.read("huge.schematic", bytes))
                .isInstanceOf(SchematicParseException.class)
                .hasMessageContaining("huge.schematic");
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
