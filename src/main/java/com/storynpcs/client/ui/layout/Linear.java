package com.storynpcs.client.ui.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * Vertical {@link Column} or horizontal {@link Row} stack (issue #200):
 * fixed children get their measured size on the main axis; {@code flex}
 * children split the leftover proportionally. Cross axis stretches to the
 * container or keeps measured size, per {@link CrossAlign}.
 *
 * <p>Overflow rule: if the children don't fit, they are NOT squeezed below
 * their measured size — the container reports its natural height/width and
 * the caller wraps it in a {@link Viewport} (scroll takes over).
 */
public abstract class Linear implements LayoutNode {

    public enum CrossAlign { START, STRETCH }

    /** Child + flex weight on the main axis (0 = natural size). */
    public record Slot(LayoutNode node, int flex) {}

    protected final List<Slot> slots = new ArrayList<>();
    protected int spacing;
    protected CrossAlign crossAlign = CrossAlign.STRETCH;
    protected Rect bounds = Rect.EMPTY;
    /** Measured main-axis sizes from the last measure pass (natural, pre-flex). */
    protected int[] natural;

    public Linear spacing(int px) { this.spacing = Math.max(0, px); return this; }
    public Linear crossAlign(CrossAlign align) { this.crossAlign = align; return this; }
    public Linear add(LayoutNode node) { return add(node, 0); }
    public Linear add(LayoutNode node, int flex) {
        slots.add(new Slot(node, Math.max(0, flex)));
        return this;
    }

    public List<Slot> children() { return slots; }

    @Override
    public Rect bounds() { return bounds; }

    /** Vertical stacking. */
    public static final class Column extends Linear {
        @Override
        public Size measure(Constraints c) {
            natural = new int[slots.size()];
            int w = 0, h = spacing * Math.max(0, slots.size() - 1);
            Constraints child = c.loosen();
            for (int i = 0; i < slots.size(); i++) {
                Size s = slots.get(i).node().measure(child);
                natural[i] = s.height();
                w = Math.max(w, s.width());
                h += s.height();
            }
            return c.constrain(new Size(w, h));
        }

        @Override
        public void arrange(Rect r) {
            bounds = r;
            int n = slots.size();
            if (n == 0) return;
            int gap = spacing * (n - 1);
            int totalFlex = 0;
            int fixed = gap;
            for (int i = 0; i < n; i++) {
                totalFlex += slots.get(i).flex();
                if (slots.get(i).flex() == 0) fixed += natural[i];
            }
            int leftover = Math.max(0, r.height() - fixed);
            int y = r.y();
            int flexUsed = 0;
            for (int i = 0; i < n; i++) {
                Slot slot = slots.get(i);
                int h;
                if (slot.flex() == 0) {
                    h = natural[i];
                } else {
                    flexUsed += slot.flex();
                    // last flex child takes the remainder to absorb rounding
                    h = flexUsed == totalFlex ? r.bottom() - y
                            : leftover * slot.flex() / totalFlex;
                }
                int w = crossAlign == CrossAlign.STRETCH ? r.width()
                        : Math.min(r.width(), slot.node().measure(Constraints.loose(r.width(), h)).width());
                slot.node().arrange(new Rect(r.x(), y, w, h));
                y += h + spacing;
            }
        }
    }

    /** Horizontal stacking. */
    public static final class Row extends Linear {
        @Override
        public Size measure(Constraints c) {
            natural = new int[slots.size()];
            int w = spacing * Math.max(0, slots.size() - 1), h = 0;
            Constraints child = c.loosen();
            for (int i = 0; i < slots.size(); i++) {
                Size s = slots.get(i).node().measure(child);
                natural[i] = s.width();
                w += s.width();
                h = Math.max(h, s.height());
            }
            return c.constrain(new Size(w, h));
        }

        @Override
        public void arrange(Rect r) {
            bounds = r;
            int n = slots.size();
            if (n == 0) return;
            int gap = spacing * (n - 1);
            int totalFlex = 0;
            int fixed = gap;
            for (int i = 0; i < n; i++) {
                totalFlex += slots.get(i).flex();
                if (slots.get(i).flex() == 0) fixed += natural[i];
            }
            int leftover = Math.max(0, r.width() - fixed);
            int x = r.x();
            int flexUsed = 0;
            for (int i = 0; i < n; i++) {
                Slot slot = slots.get(i);
                int w;
                if (slot.flex() == 0) {
                    w = natural[i];
                } else {
                    flexUsed += slot.flex();
                    w = flexUsed == totalFlex ? r.right() - x
                            : leftover * slot.flex() / totalFlex;
                }
                int h = crossAlign == CrossAlign.STRETCH ? r.height()
                        : Math.min(r.height(), slot.node().measure(Constraints.loose(w, r.height())).height());
                slot.node().arrange(new Rect(x, r.y(), w, h));
                x += w + spacing;
            }
        }
    }
}
