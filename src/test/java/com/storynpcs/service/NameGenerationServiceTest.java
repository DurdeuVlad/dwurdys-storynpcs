package com.storynpcs.service;

import static org.junit.jupiter.api.Assertions.*;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.namegen.MarkovNameGenerator;
import com.storynpcs.domain.namegen.NameDictionary;
import com.storynpcs.domain.namegen.NameDictionaryLoader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #123 — clean-room Markov name generation: dictionary validation,
 * strict YAML loading, seeded determinism, bounded output, and the
 * service-level catalog/suggest surface.
 */
class NameGenerationServiceTest {

    private static final List<String> SEEDS = List.of(
            "Aelius", "Brutus", "Cassius", "Decimus", "Fabius", "Gallus",
            "Lucius", "Marcus", "Octavius", "Quintus", "Severus", "Valerius");

    private static NameDictionary dict(List<String> names) {
        NameDictionary d = new NameDictionary();
        d.setId("storynpcs:test");
        d.setCulture("Test");
        d.setOrder(2);
        d.setNames(names);
        return d;
    }

    // ── Dictionary validation ───────────────────────────────────────────────

    @Test
    @DisplayName("dictionary rejects out-of-range order")
    void orderBounds() {
        NameDictionary d = new NameDictionary();
        assertThrows(IllegalArgumentException.class, () -> d.setOrder(1));
        assertThrows(IllegalArgumentException.class, () -> d.setOrder(5));
        d.setOrder(3); // in range
        assertEquals(3, d.getOrder());
    }

    @Test
    @DisplayName("dictionary rejects invalid ids and overlong names")
    void fieldValidation() {
        NameDictionary d = new NameDictionary();
        assertThrows(Exception.class, () -> d.setId("not a valid id!"));
        assertThrows(IllegalArgumentException.class,
                () -> d.setCulture("x".repeat(65)));
        assertThrows(IllegalArgumentException.class,
                () -> d.setNames(List.of("a".repeat(NameDictionary.MAX_NAME_LENGTH + 1))));
        assertThrows(IllegalArgumentException.class,
                () -> d.setNames(List.of("  ")));
        assertThrows(IllegalArgumentException.class,
                () -> d.setNames(List.of("bad\u0007name")));
    }

    // ── Loader ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loader parses a well-formed document")
    void loaderParses() {
        String yaml = "schemaVersion: 1\nid: \"storynpcs:test\"\nculture: \"T\"\norder: 2\n"
                + "names:\n  - Able\n  - Baker\n  - Charlie\n  - Delta\n";
        NameDictionary d = NameDictionaryLoader.parse("test.yaml", yaml);
        assertEquals("storynpcs:test", d.getId());
        assertEquals(4, d.getNames().size());
        assertEquals(NamespacedId.of("storynpcs:test"), d.namespacedId());
    }

    @Test
    @DisplayName("loader rejects unknown fields, bad versions, thin dictionaries")
    void loaderRejects() {
        String base = "schemaVersion: 1\nid: \"storynpcs:t\"\nnames:\n"
                + "  - Able\n  - Baker\n  - Charlie\n  - Delta\n";
        assertThrows(IllegalArgumentException.class,
                () -> NameDictionaryLoader.parse("u", base + "unexpected: 1\n"));
        assertThrows(IllegalArgumentException.class,
                () -> NameDictionaryLoader.parse("v", base.replace("schemaVersion: 1", "schemaVersion: 99")));
        assertThrows(IllegalArgumentException.class,
                () -> NameDictionaryLoader.parse("w",
                        "schemaVersion: 1\nid: \"storynpcs:t\"\nnames:\n  - Solo\n  - Duo\n"));
        assertThrows(IllegalArgumentException.class,
                () -> NameDictionaryLoader.parse("x", "schemaVersion: 1\nnames:\n  - a\n  - b\n  - c\n  - d\n"));
    }

    // ── Generator determinism & bounds ──────────────────────────────────────

    @Test
    @DisplayName("same dictionary and seed always produce the same name")
    void seededDeterminism() {
        NameDictionary d = dict(SEEDS);
        String a = MarkovNameGenerator.generate(d, 42L).orElseThrow();
        String b = MarkovNameGenerator.generate(d, 42L).orElseThrow();
        assertEquals(a, b);
        assertFalse(a.isBlank());
        assertTrue(a.length() <= NameDictionary.MAX_NAME_LENGTH);
        // Different seeds explore the chain — over a handful of seeds we
        // expect more than one distinct output.
        long distinct = java.util.stream.LongStream.range(0, 8)
                .mapToObj(s -> MarkovNameGenerator.generate(d, s).orElseThrow())
                .distinct().count();
        assertTrue(distinct > 1, "seed variation should produce varied names");
    }

    @Test
    @DisplayName("empty and null dictionaries fail closed")
    void degenerateInput() {
        assertTrue(MarkovNameGenerator.generate(null, 1L).isEmpty());
        NameDictionary empty = dict(SEEDS);
        empty.setNames(List.of());
        assertTrue(MarkovNameGenerator.generate(empty, 1L).isEmpty());
    }

    // ── Service surface ─────────────────────────────────────────────────────

    @Test
    @DisplayName("service suggests deterministically and resolves unknown cultures to empty")
    void serviceSurface(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("test.yaml"),
                "schemaVersion: 1\nid: \"storynpcs:t\"\nnames:\n"
                        + "  - Able\n  - Baker\n  - Charlie\n  - Delta\n  - Echo\n",
                StandardCharsets.UTF_8);
        NameGenerationService svc = NameGenerationService.load(null, dir);
        assertTrue(svc.cultures().contains(NamespacedId.of("storynpcs:t")));

        String a = svc.suggest(NamespacedId.of("storynpcs:t"), 7L).orElseThrow();
        assertEquals(a, svc.suggest(NamespacedId.of("storynpcs:t"), 7L).orElseThrow());
        assertEquals(Optional.empty(), svc.suggest(NamespacedId.of("storynpcs:missing"), 7L));
    }

    @Test
    @DisplayName("bad creator dictionary warns and skips; catalog still loads")
    void badCreatorSkipped(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("good.yaml"),
                "schemaVersion: 1\nid: \"storynpcs:good\"\nnames:\n"
                        + "  - Able\n  - Baker\n  - Charlie\n  - Delta\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("bad.yaml"),
                "schemaVersion: 1\nnames: not-a-list\n", StandardCharsets.UTF_8);
        NameGenerationService svc = NameGenerationService.load(null, dir);
        assertTrue(svc.cultures().contains(NamespacedId.of("storynpcs:good")));
        assertEquals(1, svc.cultures().size());
    }

    // ── Bundled catalog — the 11 culture dictionaries must all parse ────────

    @Test
    @DisplayName("all 14 bundled culture dictionaries parse and generate")
    void bundledCatalog() {
        // Covers every target markovnames culture (roman, ancient_greek,
        // japanese, old_norse, slavic, spanish, welsh, aztec, saami,
        // customnpcs_classic) plus clean-room additions — all hand-authored,
        // no target dictionary text.
        String[] expected = {"roman", "ancient_greek", "japanese", "slavic",
                "welsh", "saharan", "old_norse", "celtic", "french",
                "spanish", "fantasy", "aztec", "saami", "customnpcs_classic"};
        for (String name : expected) {
            String resource = "/data/storynpcs/namegen/" + name + ".yaml";
            try (InputStream in = getClass().getResourceAsStream(resource)) {
                assertNotNull(in, "missing bundled dictionary " + name);
                String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                NameDictionary d = NameDictionaryLoader.parse(name + ".yaml", yaml);
                assertEquals("storynpcs:" + name, d.getId());
                Optional<String> generated = MarkovNameGenerator.generate(d, 99L);
                assertTrue(generated.isPresent(), "bundled " + name + " produced no name");
                assertTrue(generated.get().length() <= NameDictionary.MAX_NAME_LENGTH);
            } catch (Exception e) {
                fail("bundled dictionary " + name + " failed: " + e.getMessage());
            }
        }
    }
}
