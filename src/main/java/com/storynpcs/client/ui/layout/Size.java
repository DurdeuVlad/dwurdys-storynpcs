package com.storynpcs.client.ui.layout;

/** Immutable logical-pixel size returned by {@link LayoutNode#measure}. */
public record Size(int width, int height) {

    public static final Size ZERO = new Size(0, 0);

    public Size clamped(Constraints c) {
        return new Size(c.clampWidth(width), c.clampHeight(height));
    }
}
