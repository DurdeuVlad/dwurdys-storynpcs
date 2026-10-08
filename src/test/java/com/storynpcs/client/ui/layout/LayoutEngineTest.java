package com.storynpcs.client.ui.layout;

import com.storynpcs.client.ui.UiTheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** #200: deterministic int-only layout math — measure/arrange contracts. */
class LayoutEngineTest {

    // ---- Rect ----

    @Test
    @DisplayName("rect inset/translate/intersect/centeredMax")
    void rectOps() {
        Rect r = new Rect(10, 10, 100, 50);
        assertEquals(110, r.right());
        assertEquals(60, r.bottom());
        assertTrue(r.contains(10, 10));
        assertFalse(r.contains(110, 10), "right edge is exclusive");

        Rect i = r.inset(8);
        assertEquals(new Rect(18, 18, 84, 34), i);
        Rect collapsed = new Rect(0, 0, 4, 4).inset(8);
        assertTrue(collapsed.isEmpty(), "inset never produces negative size");
        assertEquals(0, collapsed.width());

        assertEquals(new Rect(5, 10, 30, 10),
                new Rect(0, 0, 40, 20).intersect(new Rect(5, 10, 30, 20)));
        assertTrue(r.intersect(new Rect(500, 500, 10, 10)).isEmpty(),
                "disjoint rects intersect to a zero-area rect");

        Rect c = new Rect(0, 0, 500, 400).centeredMax(200, 100);
        assertEquals(new Rect(150, 150, 200, 100), c);
    }

    // ---- Column ----

    @Test
    @DisplayName("column: fixed children get natural height, flex splits the rest")
    void columnFlex() {
        Leaf a = Leaf.fixed("a", 10, 10);
        Leaf b = Leaf.fixed("b", 10, 20);
        Leaf fill = Leaf.fillWidth("fill", 0);
        Linear.Column col = new Linear.Column();
        col.spacing(4).add(a).add(b).add(fill, 1);

        Size m = col.measure(Constraints.loose(100, 200));
        assertEquals(38, m.height()); // 10 + 20 + 2*4
        assertEquals(100, m.width(), "fillWidth child reports the offered width");

        col.arrange(new Rect(0, 0, 100, 100));
        assertEquals(new Rect(0, 0, 100, 10), a.bounds());
        assertEquals(new Rect(0, 14, 100, 20), b.bounds());
        assertEquals(new Rect(0, 38, 100, 62), fill.bounds(),
                "flex child takes the leftover down to the bottom edge");
    }

    @Test
    @DisplayName("column: two flex children split evenly, last absorbs rounding")
    void columnFlexSplit() {
        Leaf x = Leaf.fixed("x", 5, 5);
        Linear.Column col = new Linear.Column();
        Leaf f1 = Leaf.fillWidth("f1", 0);
        Leaf f2 = Leaf.fillWidth("f2", 0);
        col.add(x).add(f1, 1).add(f2, 1);

        col.measure(Constraints.loose(50, 101));
        col.arrange(new Rect(0, 0, 50, 101));
        int leftover = 101 - 5;
        assertEquals(leftover / 2, f1.bounds().height());
        assertEquals(leftover - leftover / 2, f2.bounds().height());
        assertEquals(101, f2.bounds().bottom(), "flex fills to the edge exactly");
    }

    @Test
    @DisplayName("column overflow: children are not squeezed, excess runs past bottom")
    void columnOverflow() {
        Linear.Column col = new Linear.Column();
        Leaf a = Leaf.fixed("a", 10, 40);
        Leaf b = Leaf.fixed("b", 10, 40);
        col.add(a).add(b);
        col.measure(Constraints.loose(50, 100));
        col.arrange(new Rect(0, 0, 50, 30));
        assertEquals(40, a.bounds().height(), "no squeeze below natural size");
        assertEquals(new Rect(0, 40, 50, 40), b.bounds(),
                "STRETCH cross-align fills container width even on overflow");
    }

    // ---- Row ----

    @Test
    @DisplayName("row: natural widths + flex remainder")
    void rowFlex() {
        Leaf a = Leaf.fixed("a", 20, 10);
        Leaf b = Leaf.fixed("b", 30, 10);
        Leaf f = Leaf.fillWidth("f", 10);
        Linear.Row row = new Linear.Row();
        row.spacing(2).add(a).add(b).add(f, 1);
        row.measure(Constraints.loose(200, 50));
        row.arrange(new Rect(0, 0, 200, 20));
        assertEquals(new Rect(0, 0, 20, 20), a.bounds());
        assertEquals(new Rect(22, 0, 30, 20), b.bounds());
        assertEquals(new Rect(54, 0, 146, 20), f.bounds());
    }

    // ---- Flow ----

    @Test
    @DisplayName("flow wraps to a new line when the next child overflows")
    void flowWrap() {
        Flow flow = new Flow();
        Leaf a = Leaf.fixed("a", 60, 10);
        Leaf b = Leaf.fixed("b", 60, 10);
        Leaf c = Leaf.fixed("c", 60, 10);
        flow.spacing(4).lineSpacing(2).add(a).add(b).add(c);

        Size m = flow.measure(Constraints.loose(130, 500));
        assertEquals(2, flow.lineCount());      // 60+4+60 fits, third wraps
        assertEquals(22, m.height());           // 10 + 2 + 10

        flow.arrange(new Rect(0, 0, 130, 100));
        assertEquals(new Rect(0, 0, 60, 10), a.bounds());
        assertEquals(new Rect(64, 0, 60, 10), b.bounds());
        assertEquals(new Rect(0, 12, 60, 10), c.bounds());
    }

    @Test
    @DisplayName("flow single-line when width allows")
    void flowNoWrap() {
        Flow flow = new Flow();
        flow.spacing(4).add(Leaf.fixed("a", 30, 8)).add(Leaf.fixed("b", 30, 8));
        flow.measure(Constraints.loose(200, 50));
        assertEquals(1, flow.lineCount());
    }

    // ---- Breakpoint ----

    @Test
    @DisplayName("breakpoint picks wide/narrow by offered width")
    void breakpointCollapse() {
        Leaf wide = Leaf.fixed("w", 100, 10);
        Leaf narrow = Leaf.fixed("n", 50, 20);
        Breakpoint bp = new Breakpoint(300, wide, narrow);

        bp.measure(Constraints.loose(400, 50));
        assertTrue(bp.isWide());
        assertEquals(new Size(100, 10), bp.measure(Constraints.loose(400, 50)));

        bp.measure(Constraints.loose(299, 50));
        assertFalse(bp.isWide());
        assertEquals(narrow, bp.active());
    }

    // ---- Viewport ----

    @Test
    @DisplayName("viewport: child keeps natural height, scroll state takes over")
    void viewportOverflow() {
        Linear.Column col = new Linear.Column();
        for (int i = 0; i < 10; i++) col.add(Leaf.fixed("r" + i, 50, 12));
        Viewport vp = new Viewport(col);

        Size m = vp.measure(Constraints.loose(80, 48));
        assertEquals(48, m.height(), "viewport clamps to granted height");
        assertTrue(vp.overflowing(), "120px of content in 48px must overflow");

        vp.arrange(new Rect(5, 5, 80, 48));
        assertEquals(120, vp.contentBounds().height());
        assertEquals(72, vp.scroll().maxOffset());
        assertTrue(vp.scroll().scrollable());

        vp.scroll().scrollTo(24);
        vp.arrange(new Rect(5, 5, 80, 48));
        assertEquals(5 - 24, vp.contentBounds().y(),
                "content shifts above the viewport by the scroll offset");
    }

    @Test
    @DisplayName("viewport: no overflow when content fits")
    void viewportFits() {
        Viewport vp = new Viewport(Leaf.fixed("x", 10, 10));
        vp.measure(Constraints.loose(80, 48));
        vp.arrange(new Rect(0, 0, 80, 48));
        assertFalse(vp.overflowing());
        assertFalse(vp.scroll().scrollable());
    }

    // ---- UiFrames ----

    @Test
    @DisplayName("panel: centered, capped at max, safe-area inset, degenerate-safe")
    void panelBounds() {
        Rect p = UiFrames.panel(528, 301);
        assertEquals(UiTheme.PANEL_MAX_W, p.width());
        assertEquals(301 - UiTheme.PANEL_INSET * 2, p.height());
        assertEquals((528 - p.width()) / 2, p.x(), "centered horizontally");

        // narrow window: safe area wins when it's above the floor
        Rect small = UiFrames.panel(60, 50);
        assertEquals(60 - UiTheme.PANEL_INSET * 2, small.width());
        // degenerate window: floors kick in so geometry never goes negative
        Rect tiny = UiFrames.panel(30, 30);
        assertEquals(UiTheme.PAD_XL * 2, tiny.width());
        assertEquals(UiTheme.HEADER_H + UiTheme.FOOTER_H + UiTheme.PAD_S * 2 + 1,
                tiny.height());

        // bands tile the panel without overlap
        Rect header = UiFrames.header(p);
        Rect footer = UiFrames.footer(p);
        Rect content = UiFrames.content(p);
        assertEquals(p.y(), header.y());
        assertEquals(p.bottom(), footer.bottom());
        assertTrue(content.y() >= header.bottom());
        assertTrue(content.bottom() <= footer.y());
    }

    @Test
    @DisplayName("flex between fixed siblings: header/body/footer never overflows")
    void flexBetweenFixed() {
        // The blocker repro: last flex must take leftover, not r.bottom()-y.
        Leaf header = Leaf.fixed("header", 50, 20);
        Leaf body = Leaf.fillWidth("body", 0);
        Leaf footer = Leaf.fixed("footer", 50, 20);
        Linear.Column col = new Linear.Column();
        col.add(header).add(body, 1).add(footer);
        col.measure(Constraints.loose(100, 100));
        col.arrange(new Rect(0, 0, 100, 100));
        assertEquals(new Rect(0, 0, 100, 20), header.bounds());
        assertEquals(new Rect(0, 20, 100, 60), body.bounds());
        assertEquals(new Rect(0, 80, 100, 20), footer.bounds());
    }

    @Test
    @DisplayName("row: flex between fixed siblings mirrors the column fix")
    void rowFlexBetweenFixed() {
        Leaf left = Leaf.fixed("l", 20, 10);
        Leaf mid = Leaf.fillWidth("m", 10);
        Leaf right = Leaf.fixed("r", 20, 10);
        Linear.Row row = new Linear.Row();
        row.add(left).add(mid, 1).add(right);
        row.measure(Constraints.loose(200, 20));
        row.arrange(new Rect(0, 0, 200, 20));
        assertEquals(new Rect(20, 0, 160, 20), mid.bounds());
        assertEquals(new Rect(180, 0, 20, 20), right.bounds());
    }

    @Test
    @DisplayName("arrange before measure, or add() between passes, throws")
    void measureFirstContract() {
        Linear.Column col = new Linear.Column();
        col.add(Leaf.fixed("a", 10, 10));
        assertThrows(IllegalStateException.class,
                () -> col.arrange(new Rect(0, 0, 50, 50)));
        col.measure(Constraints.loose(50, 50));
        col.add(Leaf.fixed("b", 10, 10));
        assertThrows(IllegalStateException.class,
                () -> col.arrange(new Rect(0, 0, 50, 50)),
                "stale natural[] must not be indexed");

        Flow flow = new Flow();
        flow.add(Leaf.fixed("a", 10, 10));
        assertThrows(IllegalStateException.class,
                () -> flow.arrange(new Rect(0, 0, 50, 50)));
    }

    @Test
    @DisplayName("constraints: clamp/deflate/loosen/tightWidth semantics")
    void constraintsUnits() {
        Constraints c = Constraints.loose(100, 50);
        assertEquals(100, c.clampWidth(150));
        assertEquals(0, c.clampHeight(-5));
        assertEquals(50, c.clampHeight(150));
        assertEquals(new Constraints(0, 100, 0, 50), c.loosen());
        assertEquals(new Constraints(0, 80, 0, 30), c.deflate(10, 10));
        assertEquals(new Constraints(80, 80, 30, 30),
                Constraints.tight(100, 50).deflate(10, 10));
        assertEquals(Integer.MAX_VALUE,
                Constraints.tightWidth(40).maxHeight());
        assertEquals(new Size(10, 10), c.constrain(new Size(10, 10)));
    }

    @Test
    @DisplayName("flow: empty container and zero-width child stay consistent")
    void flowEdge() {
        Flow empty = new Flow();
        assertEquals(new Size(0, 0), empty.measure(Constraints.loose(100, 50)));
        assertEquals(0, empty.lineCount());

        Flow f = new Flow().spacing(4);
        Leaf zero = Leaf.fixed("z", 0, 10);
        Leaf real = Leaf.fixed("r", 50, 10);
        f.add(zero).add(real);
        // spacing still applies between slots — measure and arrange agree
        assertEquals(54, f.measure(Constraints.loose(200, 50)).width());
        f.arrange(new Rect(0, 0, 200, 50));
        assertEquals(new Rect(0, 0, 0, 10), zero.bounds());
        assertEquals(new Rect(4, 0, 50, 10), real.bounds());
    }

    @Test
    @DisplayName("START cross-align keeps the measured cross size, no re-measure")
    void startAlign() {
        Leaf wide = Leaf.fixed("w", 30, 10);
        Linear.Column col = new Linear.Column();
        col.crossAlign(Linear.CrossAlign.START).add(wide);
        col.measure(Constraints.loose(200, 50));
        col.arrange(new Rect(0, 0, 200, 50));
        assertEquals(30, wide.bounds().width(), "START keeps natural width");
    }

    @Test
    @DisplayName("viewport re-measures at the granted width (rewrap-aware)")
    void viewportRewrap() {
        Flow flow = new Flow().spacing(0);
        for (int i = 0; i < 4; i++) flow.add(Leaf.fixed("c" + i, 50, 10));
        Viewport vp = new Viewport(flow);

        // offered 200 → 4 fit on one line (h=10); granted 100 → wraps to 2 lines
        vp.measure(Constraints.loose(200, 8));
        vp.arrange(new Rect(0, 0, 100, 8));
        assertEquals(20, vp.contentBounds().height(),
                "content height must reflect the granted width, not the offered one");
    }

    @Test
    @DisplayName("panel origin clamps to 0 on degenerate windows")
    void panelOriginClamped() {
        Rect p = UiFrames.panel(10, 10);
        assertTrue(p.x() >= 0 && p.y() >= 0,
                "panel origin must stay on-screen: " + p);
    }

    // ---- determinism ----

    @Test
    @DisplayName("same constraints produce identical geometry (property)")
    void deterministic() {
        for (int w = 40; w <= 900; w += 37) {
            for (int h = 40; h <= 600; h += 41) {
                Linear.Column col = new Linear.Column();
                Leaf f1 = Leaf.fillWidth("f1", 0);
                Leaf f2 = Leaf.fillWidth("f2", 0);
                col.spacing(3).add(Leaf.fixed("a", 20, 12)).add(f1, 1).add(f2, 2);
                col.measure(Constraints.loose(w, h));
                col.arrange(new Rect(0, 0, w, h));
                Rect first = f2.bounds();

                Linear.Column col2 = new Linear.Column();
                Leaf g2 = Leaf.fillWidth("g2", 0);
                col2.spacing(3).add(Leaf.fixed("a", 20, 12)).add(Leaf.fillWidth("g1", 0), 1).add(g2, 2);
                col2.measure(Constraints.loose(w, h));
                col2.arrange(new Rect(0, 0, w, h));
                assertEquals(first, g2.bounds(),
                        "non-deterministic at " + w + "x" + h);
            }
        }
    }
}
