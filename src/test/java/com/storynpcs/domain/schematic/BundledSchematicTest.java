package com.storynpcs.domain.schematic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bundled-asset fixture for issue #149 / ADR-006: every structure shipped as
 * a mod asset under {@code data/storynpcs/schematics/} must parse through
 * {@link SchematicReader} and produce a placeable {@link BuildPlan} under
 * every quarter-turn — the "all bundled target schematics load and build"
 * acceptance criterion. The set below mirrors the pinned target jar's
 * {@code data/customnpcs/schematics/} catalog (probed at 28 files).
 */
class BundledSchematicTest {

    private static final String RESOURCE_DIR = "data/storynpcs/schematics/";

    private static final List<String> BUNDLED = List.of(
            "archery_range", "bakery", "barn", "building_site", "chapel",
            "church", "gate", "glassworks", "guard_tower", "guild_house",
            "house", "house_small", "inn", "library", "lighthouse", "mill",
            "observatory", "ship", "shop", "stall", "stall2", "stall3",
            "tier_house1", "tier_house2", "tier_house3", "tower", "wall",
            "wall_corner");

    @Test
    void bundledTargetSchematicsLoadAndBuild() throws IOException, SchematicParseException {
        for (String name : BUNDLED) {
            Schematic schematic = loadBundled(name);
            assertThat(schematic.width()).as("%s width", name)
                    .isBetween(1, Schematic.MAX_DIMENSION);
            assertThat(schematic.height()).as("%s height", name)
                    .isBetween(1, Schematic.MAX_DIMENSION);
            assertThat(schematic.length()).as("%s length", name)
                    .isBetween(1, Schematic.MAX_DIMENSION);
            assertThat(schematic.palette()).as("%s palette", name).isNotEmpty();
            for (int turns = 0; turns < 4; turns++) {
                assertThat(BuildPlan.of(schematic, turns).placements())
                        .as("%s must yield placeable blocks at %d quarter-turn(s)", name, turns)
                        .isNotEmpty();
            }
        }
    }

    @Test
    void bundledDirectoryMatchesExpectedCatalog() throws IOException {
        // Test classpath resolves main resources as files — guard both a
        // dropped asset and an undocumented extra file landing in the bundle.
        // G-E1 (#158): the `file` protocol is a Gradle-test-classpath contract —
        // directory enumeration needs a jar-aware strategy under a jar-packaged
        // runner; the load/parse coverage above is classpath-protocol agnostic.
        var url = BundledSchematicTest.class.getClassLoader().getResource(RESOURCE_DIR);
        assertThat(url).as("bundled schematics resource dir").isNotNull();
        assertThat(url.getProtocol())
                .as("Gradle test classpath must expose resources as files (documented "
                        + "Gradle-only assumption, #158 — jar runners need jar-aware enumeration)")
                .isEqualTo("file");
        var names = new TreeSet<String>();
        try (var stream = java.nio.file.Files.list(
                java.nio.file.Path.of(java.net.URI.create(url.toExternalForm())))) {
            // Only .schematic payloads are catalog entries — licensing notices
            // (ATTRIBUTION.txt) and other metadata files are not structures.
            stream.filter(java.nio.file.Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".schematic"))
                    .forEach(names::add);
        }
        var expected = new TreeSet<String>();
        BUNDLED.forEach(n -> expected.add(n + ".schematic"));
        assertThat(names).isEqualTo(expected);
    }

    private static Schematic loadBundled(String name) throws IOException, SchematicParseException {
        try (var in = BundledSchematicTest.class.getClassLoader()
                .getResourceAsStream(RESOURCE_DIR + name + ".schematic")) {
            assertThat(in).as("bundled schematic resource %s", name).isNotNull();
            return SchematicReader.read(name + ".schematic", in.readAllBytes());
        }
    }
}
