package com.storynpcs.client.ui.layout;

import com.storynpcs.client.ui.widgets.ScrollState;

/**
 * Clipping viewport over a taller child (issue #200): measures the child at
 * tight width / unbounded height, then reports the overflow so a
 * {@code ScrollRegion} (or the caller) can scroll it — the defined overflow
 * behavior when content exceeds min height. Pixel-unit {@link ScrollState}
 * is exposed for direct wiring.
 */
public final class Viewport implements LayoutNode {

    private final LayoutNode child;
    private final ScrollState scroll = new ScrollState();
    private int contentHeight;
    private Rect bounds = Rect.EMPTY;
    private Rect contentBounds = Rect.EMPTY;

    public Viewport(LayoutNode child) {
        this.child = child;
    }

    public ScrollState scroll() { return scroll; }
    /** True when the child measured taller than the granted viewport. */
    public boolean overflowing() { return contentHeight > bounds.height(); }
    /** The child's full (unclipped) arranged rect — render scissor clips it. */
    public Rect contentBounds() { return contentBounds; }

    @Override
    public Size measure(Constraints c) {
        Size natural = child.measure(new Constraints(
                c.minWidth(), c.maxWidth(), 0, Integer.MAX_VALUE));
        contentHeight = natural.height();
        return new Size(natural.width(), Math.min(natural.height(), c.maxHeight()))
                .clamped(c);
    }

    @Override
    public void arrange(Rect r) {
        bounds = r;
        // Re-measure at the *granted* width — a width-dependent child (Flow,
        // wrapped text) re-wraps, so contentHeight must reflect r.width(),
        // not the offered width from the measure pass.
        Size natural = child.measure(Constraints.tightWidth(r.width()));
        contentHeight = natural.height();
        scroll.setContentSize(contentHeight);
        scroll.setViewportSize(r.height());
        // Child gets its natural height starting above the viewport by the
        // scroll offset; the render pass scissors to bounds.
        contentBounds = new Rect(r.x(), r.y() - scroll.offset(),
                r.width(), contentHeight);
        child.arrange(contentBounds);
    }

    @Override
    public Rect bounds() { return bounds; }
}
