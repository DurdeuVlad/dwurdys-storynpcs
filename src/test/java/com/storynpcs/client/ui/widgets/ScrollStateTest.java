package com.storynpcs.client.ui.widgets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** #199: ScrollState clamping and wheel behavior. */
class ScrollStateTest {

    @Test
    @DisplayName("offset clamps to [0, contentSize - viewportSize]")
    void clampsOffset() {
        ScrollState s = new ScrollState();
        s.setContentSize(100);
        s.setViewportSize(20);
        assertEquals(80, s.maxOffset());
        assertTrue(s.scrollable());

        assertTrue(s.scrollTo(50));
        assertEquals(50, s.offset());
        assertTrue(s.scrollTo(999));
        assertEquals(80, s.offset());
        assertTrue(s.scrollTo(-5));
        assertEquals(0, s.offset());
        assertFalse(s.scrollTo(0), "no-op scroll must report false");
    }

    @Test
    @DisplayName("shrinking content or viewport re-clamps a stale offset")
    void staleOffsetReclamps() {
        ScrollState s = new ScrollState();
        s.setContentSize(100);
        s.setViewportSize(20);
        s.scrollTo(80);

        s.setContentSize(30);
        assertEquals(10, s.offset(), "stale offset would blank the view");

        s.setViewportSize(50);
        assertEquals(0, s.offset());
        assertFalse(s.scrollable());
    }

    @Test
    @DisplayName("wheel scrolls one unit per notch and only when scrollable")
    void wheel() {
        ScrollState s = new ScrollState();
        s.setContentSize(10);
        s.setViewportSize(3);
        assertTrue(s.wheel(-1));
        assertEquals(1, s.offset());
        assertTrue(s.wheel(1));
        assertEquals(0, s.offset());
        assertFalse(s.wheel(1), "already at top");
        assertFalse(s.wheel(0));

        s.setViewportSize(10);
        assertFalse(s.wheel(-1), "not scrollable when everything fits");
    }

    @Test
    @DisplayName("pixel-mode wheel steps by the given unit, not 1")
    void pixelWheel() {
        ScrollState s = new ScrollState();
        s.setContentSize(300);   // pixels
        s.setViewportSize(100);
        assertTrue(s.wheel(-1, 12));
        assertEquals(12, s.offset(), "one notch must move one row-height, not 1px");
        assertTrue(s.wheel(1, 12));
        assertEquals(0, s.offset());
    }

    @Test
    @DisplayName("page moves by viewport-1 and clamps")
    void page() {
        ScrollState s = new ScrollState();
        s.setContentSize(20);
        s.setViewportSize(5);
        assertTrue(s.page(1));
        assertEquals(4, s.offset());
        assertTrue(s.page(99));
        assertEquals(15, s.offset());
        assertTrue(s.page(-99));
        assertEquals(0, s.offset());
    }
}
