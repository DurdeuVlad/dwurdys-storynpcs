package com.storynpcs.client.ui.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Vertical {@link Column} or horizontal {@link Row} stack (issue #200):
 * {@code flex == 0} children get their measured main-axis size; {@code flex}
 * children split the leftover proportionally (and may shrink below natural
 * when the container is short — they are the flexible ones). Cross axis
 * stretches to the container or keeps measured size, per {@link CrossAlign}.
 *
 * <p>Overflow rule: fixed children are NOT squeezed below their measured
 * size — the container reports its natural size and the caller wraps it in
 * a {@link Viewport} (scroll takes over).
 *
 * <p>Contract: {@link #measure} must run before {@link #arrange}; mutating
 * children between them throws {@link IllegalStateException} rather than
 * arranging against stale measurements.
 */
public abstract class Linear implements LayoutNode {

    public enum CrossAlign { START, STRETCH }

    /** Child + flex weight on the main axis (0 = natural size). */
    public record Slot(LayoutNode node, int flex) {}

    protected final List<Slot> slots = new ArrayList<>();
    protected int spacing;
    protected CrossAlign crossAlign = CrossAlign.STRETCH;
    protected Rect bounds = Rect.EMPTY;
    /** Main-axis natural sizes from the last measure pass. */
    protected int[] natural;
    /** Cross-axis natural sizes from the last measure pass (START align). */
    protected int[] cross;
    /** Child count at measure time — arrange detects mid-pass mutation. */
    private int measuredCount = -1;

    public Linear spacing(int px) { this.spacing = Math.max(0, px); return this; }
    public Linear crossAlign(CrossAlign align) { this.crossAlign = align; return this; }
    public Linear add(LayoutNode node) { return add(node, 0); }
    public Linear add(LayoutNode node, int flex) {
        slots.add(new Slot(node, Math.max(0, flex)));
        return this;
    }

    public List<Slot> children() { return Collections.unmodifiableList(slots); }

    @Override
    public Rect bounds() { return bounds; }

    /** Guard: arrange must follow measure on an unchanged child list. */
    protected void requireMeasured() {
        if (natural == null || measuredCount != slots.size()) {
            throw new IllegalStateException(
                    "measure() must be called before arrange() with no children added between");
        }
    }

    protected void markMeasured(int n) { measuredCount = n; }

    /**
     * Distributes {@code r}'s main-axis extent among the slots: fixed children
     * keep their natural size, flex children share {@code leftover} by weight
     * (last flex child takes the remainder, absorbing rounding — never
     * reclaiming space reserved for trailing fixed siblings).
     */
    static int[] distributeMain(int containerMain, int[] natural, List<Slot> slots, int spacing) {
        int n = slots.size();
        int[] out = new int[n];
        int gap = spacing * Math.max(0, n - 1);
        int fixed = gap;
        int totalFlex = 0;
        for (int i = 0; i < n; i++) {
            totalFlex += slots.get(i).flex();
            if (slots.get(i).flex() == 0) fixed += natural[i];
        }
        int leftover = Math.max(0, containerMain - fixed);
        int allocated = 0;
        int flexUsed = 0;
        for (int i = 0; i < n; i++) {
            int flex = slots.get(i).flex();
            if (flex == 0) {
                out[i] = natural[i];
            } else {
                flexUsed += flex;
                out[i] = flexUsed == totalFlex
                        ? Math.max(0, leftover - allocated)
                        : leftover * flex / totalFlex;
                allocated += out[i];
            }
        }
        return out;
    }

    /** Vertical stacking. */
    public static final class Column extends Linear {
        @Override
        public Size measure(Constraints c) {
            natural = new int[slots.size()];
            cross = new int[slots.size()];
            int w = 0, h = spacing * Math.max(0, slots.size() - 1);
            Constraints child = c.loosen();
            for (int i = 0; i < slots.size(); i++) {
                Size s = slots.get(i).node().measure(child);
                natural[i] = s.height();
                cross[i] = s.width();
                w = Math.max(w, s.width());
                h += s.height();
            }
            markMeasured(slots.size());
            return c.constrain(new Size(w, h));
        }

        @Override
        public void arrange(Rect r) {
            requireMeasured();
            bounds = r;
            int[] mains = distributeMain(r.height(), natural, slots, spacing);
            int y = r.y();
            for (int i = 0; i < slots.size(); i++) {
                int w = crossAlign == CrossAlign.STRETCH ? r.width()
                        : Math.min(r.width(), cross[i]);
                slots.get(i).node().arrange(new Rect(r.x(), y, w, mains[i]));
                y += mains[i] + spacing;
            }
        }
    }

    /** Horizontal stacking. */
    public static final class Row extends Linear {
        @Override
        public Size measure(Constraints c) {
            natural = new int[slots.size()];
            cross = new int[slots.size()];
            int w = spacing * Math.max(0, slots.size() - 1), h = 0;
            Constraints child = c.loosen();
            for (int i = 0; i < slots.size(); i++) {
                Size s = slots.get(i).node().measure(child);
                natural[i] = s.width();
                cross[i] = s.height();
                w += s.width();
                h = Math.max(h, s.height());
            }
            markMeasured(slots.size());
            return c.constrain(new Size(w, h));
        }

        @Override
        public void arrange(Rect r) {
            requireMeasured();
            bounds = r;
            int[] mains = distributeMain(r.width(), natural, slots, spacing);
            int x = r.x();
            for (int i = 0; i < slots.size(); i++) {
                int h = crossAlign == CrossAlign.STRETCH ? r.height()
                        : Math.min(r.height(), cross[i]);
                slots.get(i).node().arrange(new Rect(x, r.y(), mains[i], h));
                x += mains[i] + spacing;
            }
        }
    }
}
