package com.storynpcs.domain.schematic;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Store contract for issue #149 (ADR-006): bundled assets are read-only,
 * creator-added files are plain files, names are validated before any IO, and
 * load failures surface `name: reason` diagnostics.
 */
class SchematicStoreTest {

    @TempDir
    Path creatorDir;

    private static byte[] tinySchem() throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 2);
        root.putShort("Width", (short) 1);
        root.putShort("Height", (short) 1);
        root.putShort("Length", (short) 1);
        CompoundTag palette = new CompoundTag();
        palette.putInt("minecraft:stone", 0);
        root.put("Palette", palette);
        root.putByteArray("BlockData", new byte[]{0});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(root, out);
        return out.toByteArray();
    }

    @Test
    void creatorFilesLoadAndList() throws IOException {
        Files.write(creatorDir.resolve("tower.schem"), tinySchem());
        Files.write(creatorDir.resolve("not-a-schematic.txt"), "junk".getBytes());
        var names = SchematicStore.list(null, creatorDir);
        assertThat(names).containsExactly("tower");
        var result = SchematicStore.load(null, "tower", creatorDir);
        assertThat(result.error()).isNull();
        assertThat(result.schematic()).isPresent();
        assertThat(result.schematic().get().width()).isEqualTo(1);
    }

    @Test
    void invalidAndTraversingNamesRejectBeforeIO() {
        for (String bad : new String[]{"../escape", "..", "a/b/../../x",
                "Caps", "with space", "..\\win", "a\u0000b"}) {
            var result = SchematicStore.load(null, bad, creatorDir);
            assertThat(result.schematic()).isEmpty();
            assertThat(result.error()).contains("invalid schematic name");
        }
        assertThat(SchematicStore.load(null, "missing", creatorDir).error())
                .contains("unknown schematic");
    }

    @Test
    void malformedCreatorFileFailsWithDiagnostics() throws IOException {
        Files.write(creatorDir.resolve("broken.schem"), "not nbt".getBytes());
        var result = SchematicStore.load(null, "broken", creatorDir);
        assertThat(result.schematic()).isEmpty();
        assertThat(result.error()).contains("broken.schem");
    }

    @Test
    void nullCreatorDirDegradesToBundledOnly() {
        assertThat(SchematicStore.list(null, null)).isEmpty();
        assertThat(SchematicStore.load(null, "house", null).error())
                .contains("unknown schematic");
    }
}
