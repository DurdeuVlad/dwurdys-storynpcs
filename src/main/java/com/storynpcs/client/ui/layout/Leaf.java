package com.storynpcs.client.ui.layout;

/**
 * Terminal layout node with a fixed preferred size (issue #200) — the leaf a
 * real widget or text block occupies. An optional id lets the owning screen
 * look up the arranged rect to position the actual widget.
 */
public final class Leaf implements LayoutNode {

    private final String id;
    private final int preferredW;
    private final int preferredH;
    private final boolean widthFlexible;
    private Rect bounds = Rect.EMPTY;

    private Leaf(String id, int w, int h, boolean widthFlexible) {
        this.id = id;
        this.preferredW = Math.max(0, w);
        this.preferredH = Math.max(0, h);
        this.widthFlexible = widthFlexible;
    }

    /** Fixed-size leaf (e.g. a button). */
    public static Leaf fixed(String id, int w, int h) {
        return new Leaf(id, w, h, false);
    }

    /**
     * Height-fixed leaf that stretches to the offered width (e.g. a text
     * field). Intended as a {@code flex ≥ 1} child — placed non-flex inside a
     * bounded {@link Linear.Row} it legitimately claims the whole offered
     * width and can push siblings out.
     */
    public static Leaf fillWidth(String id, int h) {
        return new Leaf(id, Integer.MAX_VALUE, h, true);
    }

    public String id() { return id; }

    @Override
    public Size measure(Constraints constraints) {
        int w = widthFlexible ? constraints.maxWidth() : preferredW;
        if (w == Integer.MAX_VALUE) w = 0; // unbounded fillWidth collapses
        return constraints.constrain(new Size(w, preferredH));
    }

    @Override
    public void arrange(Rect bounds) {
        this.bounds = bounds;
    }

    @Override
    public Rect bounds() { return bounds; }
}
