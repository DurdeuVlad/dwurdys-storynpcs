package com.storynpcs.editor.hub;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P10-1 — the 149-row target-GUI parity catalog. The row set is verified
 * against {@code docs/parity/target-surface-manifest.json}: a renamed,
 * dropped, or invented target GUI class fails this suite.
 */
class GuiParityCatalogTest {

    private static Set<String> manifestGuiClasses() throws Exception {
        Path manifest = Path.of("docs/parity/target-surface-manifest.json");
        assertTrue(Files.exists(manifest), "manifest must exist: " + manifest);
        JsonObject root = JsonParser.parseString(
                Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
        Set<String> names = new HashSet<>();
        for (var el : root.getAsJsonObject("surfaces").getAsJsonArray("gui")) {
            names.add(el.getAsJsonObject().get("class_name").getAsString());
        }
        return names;
    }

    @Test
    @DisplayName("Catalog rows exactly match the 149 manifest GUI classes")
    void catalogMatchesManifest() throws Exception {
        Set<String> manifest = manifestGuiClasses();
        assertEquals(149, manifest.size(), "manifest gui row count drifted");
        Set<String> catalog = GuiParityCatalog.rows().keySet();
        Set<String> missing = manifest.stream()
                .filter(n -> !catalog.contains(n)).collect(Collectors.toSet());
        Set<String> extra = catalog.stream()
                .filter(n -> !manifest.contains(n)).collect(Collectors.toSet());
        assertTrue(missing.isEmpty(), "manifest rows missing from catalog: " + missing);
        assertTrue(extra.isEmpty(), "catalog rows not in the manifest: " + extra);
    }

    @Test
    @DisplayName("Every row has a status, non-blank destination, and a note")
    void rowsAreFullySpecified() {
        assertTrue(GuiParityCatalog.isFullyMapped());
        for (Map.Entry<String, GuiParityCatalog.Row> e : GuiParityCatalog.rows().entrySet()) {
            GuiParityCatalog.Row r = e.getValue();
            assertEquals(e.getKey(), r.targetClass());
            assertNotNull(r.status(), e.getKey() + " has no status");
            assertFalse(r.destination().isBlank(), e.getKey() + " blank destination");
            assertFalse(r.note().isBlank(), e.getKey() + " blank note");
        }
    }

    @Test
    @DisplayName("SCREEN destinations name real StoryNPCs screen classes")
    void screenDestinationsExist() {
        for (GuiParityCatalog.Row r : GuiParityCatalog.rows().values()) {
            if (r.status() == GuiParityCatalog.Status.SCREEN) {
                boolean exists = java.util.stream.Stream.of("client/gui", "client/gui/player")
                        .map(dir -> Path.of("src/main/java/com/storynpcs", dir,
                                r.destination() + ".java"))
                        .anyMatch(Files::exists);
                assertTrue(exists,
                        r.targetClass() + " routes to missing screen " + r.destination());
            }
        }
    }

    @Test
    @DisplayName("No row silently drops a target — COMPONENT_NA names the replacement toolkit")
    void componentRowsNameToolkit() {
        for (GuiParityCatalog.Row r : GuiParityCatalog.rows().values()) {
            if (r.status() == GuiParityCatalog.Status.COMPONENT_NA) {
                assertTrue(r.destination().contains("CustomGuiLayout")
                                || r.destination().contains("vanilla"),
                        r.targetClass() + " COMPONENT_NA without a replacement toolkit");
            }
            if (r.status() == GuiParityCatalog.Status.EQUIVALENT) {
                assertTrue(r.destination().contains("panel") || r.destination().contains("PANEL")
                                || r.destination().contains("YAML") || r.destination().contains("storynpcs"),
                        r.targetClass() + " EQUIVALENT without an authored destination");
            }
        }
    }

    @Test
    @DisplayName("Catalog lookup is keyed on the exact target class name")
    void lookup() {
        assertNotNull(GuiParityCatalog.forTarget("GuiNpcDisplay"));
        assertNull(GuiParityCatalog.forTarget("guinpcdisplay"));
        assertNull(GuiParityCatalog.forTarget("NoSuchGui"));
        // The map must be unmodifiable — the row set is generated data.
        assertThrows(UnsupportedOperationException.class,
                () -> GuiParityCatalog.rows().clear());
    }
}
