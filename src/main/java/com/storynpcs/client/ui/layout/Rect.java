package com.storynpcs.client.ui.layout;

/**
 * Immutable logical-pixel rectangle (issue #200). The engine's only geometry
 * type — ints in, ints out, so layout math is deterministic and headless-testable.
 */
public record Rect(int x, int y, int width, int height) {

    public static final Rect EMPTY = new Rect(0, 0, 0, 0);

    public int right() { return x + width; }
    public int bottom() { return y + height; }
    public int centerX() { return x + width / 2; }
    public int centerY() { return y + height / 2; }

    public boolean contains(int px, int py) {
        return px >= x && px < right() && py >= y && py < bottom();
    }

    /** Shrinks all sides by {@code pad}; never goes below 0x0. */
    public Rect inset(int pad) {
        return new Rect(x + pad, y + pad,
                Math.max(0, width - pad * 2), Math.max(0, height - pad * 2));
    }

    /** Shrinks each side independently; never goes below 0x0. */
    public Rect inset(int left, int top, int right, int bottom) {
        return new Rect(x + left, y + top,
                Math.max(0, width - left - right), Math.max(0, height - top - bottom));
    }

    /** Moves the rect without resizing. */
    public Rect translate(int dx, int dy) {
        return new Rect(x + dx, y + dy, width, height);
    }

    /** Largest centered rect inside this one capped at maxW x maxH. */
    public Rect centeredMax(int maxW, int maxH) {
        int w = Math.min(width, maxW);
        int h = Math.min(height, maxH);
        return new Rect(x + (width - w) / 2, y + (height - h) / 2, w, h);
    }

    /** Clips to the intersection with {@code other}; empty when disjoint. */
    public Rect intersect(Rect other) {
        int nx = Math.max(x, other.x);
        int ny = Math.max(y, other.y);
        int nr = Math.min(right(), other.right());
        int nb = Math.min(bottom(), other.bottom());
        return new Rect(nx, ny, Math.max(0, nr - nx), Math.max(0, nb - ny));
    }

    public boolean isEmpty() { return width <= 0 || height <= 0; }
}
