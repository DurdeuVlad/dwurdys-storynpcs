package com.storynpcs.client.ui.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Wrapping row of children (issue #200): lays out left→right at each child's
 * measured size and wraps to a new line when the next child would overflow —
 * the footer-reflow primitive for narrow windows. Line height = tallest child
 * on that line; spacing goes between consecutive children on a line (a
 * zero-width child still occupies its slot).
 *
 * <p>Contract: {@link #measure} must run before {@link #arrange}; adding a
 * child between them throws {@link IllegalStateException}.
 */
public final class Flow implements LayoutNode {

    private final List<LayoutNode> children = new ArrayList<>();
    private int spacing;
    private int lineSpacing;
    private Rect bounds = Rect.EMPTY;

    private int[] naturalW;
    private int[] naturalH;
    private int lines;
    private int measuredCount = -1;

    public Flow spacing(int px) { this.spacing = Math.max(0, px); return this; }
    public Flow lineSpacing(int px) { this.lineSpacing = Math.max(0, px); return this; }
    public Flow add(LayoutNode node) { children.add(node); return this; }

    public List<LayoutNode> children() { return Collections.unmodifiableList(children); }

    @Override
    public Size measure(Constraints c) {
        int maxW = c.maxWidth();
        naturalW = new int[children.size()];
        naturalH = new int[children.size()];
        measuredCount = children.size();
        lines = children.isEmpty() ? 0 : 1;
        int x = 0, lineH = 0, h = 0, w = 0;
        boolean firstOnLine = true;
        for (int i = 0; i < children.size(); i++) {
            Size s = children.get(i).measure(c.loosen());
            naturalW[i] = s.width();
            naturalH[i] = s.height();
            int advance = (firstOnLine ? 0 : spacing) + s.width();
            if (!firstOnLine && x + advance > maxW) {
                // wrap: close the current line — x is already its full width
                h += lineH + lineSpacing;
                w = Math.max(w, x);
                x = 0;
                lineH = 0;
                lines++;
                firstOnLine = true;
                advance = s.width();
            }
            x += advance;
            firstOnLine = false;
            lineH = Math.max(lineH, s.height());
        }
        w = Math.max(w, x);
        h += lineH;
        return c.constrain(new Size(w, h));
    }

    @Override
    public void arrange(Rect r) {
        if (naturalW == null || measuredCount != children.size()) {
            throw new IllegalStateException(
                    "measure() must be called before arrange() with no children added between");
        }
        bounds = r;
        int x = r.x(), y = r.y(), lineH = 0;
        boolean firstOnLine = true;
        List<LayoutNode> line = new ArrayList<>();
        List<Integer> widths = new ArrayList<>();
        for (int i = 0; i < children.size(); i++) {
            int w = naturalW[i], h = naturalH[i];
            int advance = (firstOnLine ? 0 : spacing) + w;
            if (!firstOnLine && x + advance > r.right()) {
                flushLine(line, widths, x, y, lineH);
                y += lineH + lineSpacing;
                x = r.x();
                lineH = 0;
                firstOnLine = true;
                advance = w;
            }
            line.add(children.get(i));
            widths.add(w);
            x += advance;
            firstOnLine = false;
            lineH = Math.max(lineH, h);
        }
        flushLine(line, widths, x, y, lineH);
    }

    private void flushLine(List<LayoutNode> line, List<Integer> widths,
                           int endX, int y, int lineH) {
        if (line.isEmpty()) return;
        int startX = endX;
        for (int i = line.size() - 1; i >= 0; i--) {
            LayoutNode node = line.remove(line.size() - 1);
            int w = widths.remove(widths.size() - 1);
            startX -= w;
            node.arrange(new Rect(startX, y, w, lineH));
            startX -= spacing;
        }
    }

    /** How many lines the last measure produced — drives breakpoint tests. */
    public int lineCount() { return lines; }

    @Override
    public Rect bounds() { return bounds; }
}
