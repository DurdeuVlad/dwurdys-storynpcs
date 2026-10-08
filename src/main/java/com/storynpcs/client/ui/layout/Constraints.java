package com.storynpcs.client.ui.layout;

/**
 * Box constraints passed down the measure pass (issue #200): a node must
 * return a {@link Size} inside [min,max] on both axes. {@code Integer.MAX_VALUE}
 * max means unbounded — the node's intrinsic size wins.
 */
public record Constraints(int minWidth, int maxWidth, int minHeight, int maxHeight) {

    public static Constraints loose(int maxWidth, int maxHeight) {
        return new Constraints(0, Math.max(0, maxWidth), 0, Math.max(0, maxHeight));
    }

    /** Exact size — the node has no choice. */
    public static Constraints tight(int width, int height) {
        return new Constraints(width, width, height, height);
    }

    /** Only the width is pinned (scrolling content measures full height). */
    public static Constraints tightWidth(int width) {
        return new Constraints(width, width, 0, Integer.MAX_VALUE);
    }

    public int clampWidth(int w) {
        return Math.max(minWidth, Math.min(maxWidth, w));
    }

    public int clampHeight(int h) {
        return Math.max(minHeight, Math.min(maxHeight, h));
    }

    public Size constrain(Size size) {
        return new Size(clampWidth(size.width()), clampHeight(size.height()));
    }

    /** Same constraints with the axis maxima loosened by {@code amount}. */
    public Constraints deflate(int horizontal, int vertical) {
        return new Constraints(
                Math.max(0, minWidth - horizontal), Math.max(0, maxWidth - horizontal * 2),
                Math.max(0, minHeight - vertical), Math.max(0, maxHeight - vertical * 2));
    }

    /** Child constraints sharing this box's maxima but free minima. */
    public Constraints loosen() {
        return new Constraints(0, maxWidth, 0, maxHeight);
    }
}
