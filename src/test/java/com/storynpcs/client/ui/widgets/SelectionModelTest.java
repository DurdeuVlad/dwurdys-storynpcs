package com.storynpcs.client.ui.widgets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** #199: key-stable selection across data refreshes + keyboard move semantics. */
class SelectionModelTest {

    @Test
    @DisplayName("selection survives refresh while the key still exists")
    void selectionSurvivesRefresh() {
        SelectionModel<String> m = new SelectionModel<>();
        m.setItems(List.of("a", "b", "c"));
        assertTrue(m.select("b"));

        m.setItems(List.of("x", "b", "a"));
        assertEquals("b", m.selected());
        assertEquals(1, m.selectedIndex());

        m.setItems(List.of("a", "c"));
        assertNull(m.selected(), "vanished key must drop the selection");
    }

    @Test
    @DisplayName("select rejects unknown keys and reports real changes")
    void selectGuards() {
        SelectionModel<String> m = new SelectionModel<>();
        m.setItems(List.of("a", "b"));
        assertFalse(m.select("zzz"));
        assertTrue(m.selectIndex(0));
        assertFalse(m.selectIndex(0), "re-selecting the same key is a no-op");
        assertFalse(m.selectIndex(9));
        assertFalse(m.selectIndex(-1));
    }

    @Test
    @DisplayName("move from nothing picks first/last by direction; clamps at edges")
    void move() {
        SelectionModel<String> m = new SelectionModel<>();
        m.setItems(List.of("a", "b", "c"));
        assertTrue(m.move(1));
        assertEquals("a", m.selected());
        assertTrue(m.move(99));
        assertEquals("c", m.selected());
        assertFalse(m.move(1), "at the bottom edge");
        assertTrue(m.move(-99));
        assertEquals("a", m.selected());

        m.clear();
        assertTrue(m.move(-1));
        assertEquals("c", m.selected(), "up-with-none picks the last row");

        m.setItems(List.of());
        assertFalse(m.move(1), "empty list never moves");
    }
}
