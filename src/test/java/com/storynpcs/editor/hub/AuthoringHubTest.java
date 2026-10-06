package com.storynpcs.editor.hub;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** P10-1 — the unified authoring hub model. */
class AuthoringHubTest {

    @Test
    @DisplayName("Every required parity domain has exactly one panel")
    void coversAllDomains() {
        assertTrue(AuthoringHub.coversAllDomains());
        // No duplicate panel names can exist by construction (enum), so the
        // set-equality check inside coversAllDomains is the real guard.
        assertEquals(AuthoringHub.Panel.values().length,
                EnumSet.allOf(AuthoringHub.Panel.class).size());
    }

    @Test
    @DisplayName("Screen state revisions increment on open and touch")
    void revisionedState() {
        AuthoringHub hub = new AuthoringHub();
        long r0 = hub.state().revision();
        hub.open(AuthoringHub.Panel.QUEST, "storynpcs:q1");
        assertEquals(r0 + 1, hub.state().revision());
        assertEquals(AuthoringHub.Panel.QUEST, hub.state().panel());
        assertEquals("storynpcs:q1", hub.state().selectedId());
        hub.touch();
        assertEquals(r0 + 2, hub.state().revision());
        assertEquals(AuthoringHub.Panel.QUEST, hub.state().panel());
    }

    @Test
    @DisplayName("Search is case-insensitive, sorted, and null-safe")
    void searchPanels() {
        List<AuthoringHub.Panel> all = AuthoringHub.searchPanels(null);
        assertEquals(AuthoringHub.Panel.values().length, all.size());
        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.get(i - 1).name().compareTo(all.get(i).name()) < 0);
        }
        List<AuthoringHub.Panel> hit = AuthoringHub.searchPanels("quest");
        assertEquals(List.of(AuthoringHub.Panel.QUEST), hit);
        assertTrue(AuthoringHub.searchPanels("ZZZ_NO_MATCH").isEmpty());
    }

    @Test
    @DisplayName("Pagination is bounded and never clips — min-res viewport contract")
    void pagination() {
        List<Integer> items = java.util.stream.IntStream.range(0, 17).boxed().toList();
        // 854x480 physical at GUI scale 2 → 427x240 logical; hub fits 9 rows.
        int rows = 9;
        assertEquals(2, AuthoringHub.pageCount(items.size(), rows));
        List<Integer> p0 = AuthoringHub.page(items, 0, rows);
        List<Integer> p1 = AuthoringHub.page(items, 1, rows);
        assertEquals(9, p0.size());
        assertEquals(8, p1.size());
        assertEquals(List.of(9,10,11,12,13,14,15,16), p1);
        // Every item appears on exactly one page — nothing clipped.
        assertEquals(17, p0.size() + p1.size());
        assertEquals(List.of(), AuthoringHub.page(items, -1, rows));
        assertEquals(List.of(), AuthoringHub.page(items, 0, 0));
        assertEquals(List.of(), AuthoringHub.page(items, 5, rows));
        assertEquals(0, AuthoringHub.pageCount(10, 0));
    }

    @Test
    @DisplayName("Field errors carry schema path plus a repair hint")
    void fieldErrors() {
        AuthoringHub.FieldError e = AuthoringHub.fieldError("stats.health", "out of range");
        assertEquals("stats.health", e.schemaPath());
        assertTrue(e.repairHint().contains("[1,1024]"));
        AuthoringHub.FieldError unknown = AuthoringHub.fieldError("unlisted.path", "bad");
        assertFalse(unknown.repairHint().isBlank());
        AuthoringHub.FieldError nullPath = AuthoringHub.fieldError(null, null);
        assertEquals("?", nullPath.schemaPath());
    }

    @Test
    @DisplayName("Every panel has at least one route; command routes name a real storynpcs literal")
    void routesCoverPanels() throws Exception {
        String commands = Files.readString(Path.of(
                "src/main/java/com/storynpcs/command/StoryNpcsCommands.java"));
        Pattern literal = Pattern.compile("literal\\(\"([a-z_]+)\"\\)");
        java.util.Set<String> literals = new java.util.HashSet<>();
        Matcher m = literal.matcher(commands);
        while (m.find()) literals.add(m.group(1));

        for (AuthoringHub.Panel panel : AuthoringHub.Panel.values()) {
            List<AuthoringHub.Route> routes = AuthoringHub.routesFor(panel);
            assertFalse(routes.isEmpty(), panel + " has no route");
            for (AuthoringHub.Route r : routes) {
                assertFalse(r.openPath().isBlank(), panel + " blank route path");
                assertFalse(r.description().isBlank(), panel + " blank route description");
                if (r.kind() == AuthoringHub.Route.Kind.COMMAND) {
                    // First token after "storynpcs" (or each |-alternative's
                    // token) must be a registered literal — catches typos.
                    String path = r.openPath();
                    assertTrue(path.startsWith("storynpcs"),
                            panel + " route not a storynpcs command: " + path);
                    String afterRoot = path.substring("storynpcs".length()).trim();
                    for (String alt : afterRoot.split("\\|")) {
                        String seg = alt.trim().split("\\s+")[0];
                        assertTrue(literals.contains(seg),
                                panel + " route literal missing: " + seg);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Keyboard navigation wraps at both ends and handles empty filters")
    void cyclePanel() {
        AuthoringHub hub = new AuthoringHub();
        List<AuthoringHub.Panel> all = AuthoringHub.searchPanels("");
        hub.open(all.get(0), null);
        // Wrap backward from first → last.
        assertEquals(all.get(all.size() - 1), hub.cyclePanel(all, false));
        hub.open(all.get(all.size() - 1), null);
        // Wrap forward from last → first.
        assertEquals(all.get(0), hub.cyclePanel(all, true));
        // Selection outside the filter starts at the boundary.
        hub.open(all.get(3), null);
        assertEquals(all.get(0), hub.cyclePanel(all.subList(0, 2), true));
        // Empty filter keeps the current selection.
        assertEquals(all.get(3), hub.cyclePanel(List.of(), true));
    }

    @Test
    @DisplayName("The canonical NPC workflow names real panels in order")
    void npcWorkflow() {
        List<String> steps = AuthoringHub.npcWorkflow();
        assertFalse(steps.isEmpty());
        assertEquals("NPC_IDENTITY", steps.get(0).split(" ")[0]);
        for (String step : steps) {
            AuthoringHub.Panel.valueOf(step.split(" ")[0]);
        }
        // The workflow must round-trip through dialogue + quest authoring.
        assertTrue(steps.stream().anyMatch(s -> s.startsWith("DIALOGUE")));
        assertTrue(steps.stream().anyMatch(s -> s.startsWith("QUEST")));
    }

    @Test
    @DisplayName("Every panel exposes a non-blank preview describing its surface")
    void panelPreviews() {
        for (AuthoringHub.Panel panel : AuthoringHub.Panel.values()) {
            assertFalse(AuthoringHub.preview(panel).isBlank(), panel + " blank preview");
        }
    }

    @Test
    @DisplayName("Error list is append-only externally and clearable")
    void errorList() {
        AuthoringHub hub = new AuthoringHub();
        hub.report(AuthoringHub.fieldError("id", "blank"));
        assertEquals(1, hub.errors().size());
        assertThrows(UnsupportedOperationException.class,
                () -> hub.errors().add(AuthoringHub.fieldError("x", "y")));
        hub.clearErrors();
        assertTrue(hub.errors().isEmpty());
    }
}
