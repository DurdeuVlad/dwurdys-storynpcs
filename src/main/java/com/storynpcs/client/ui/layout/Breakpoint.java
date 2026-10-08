package com.storynpcs.client.ui.layout;

/**
 * Width-driven variant selector (issue #200): uses {@code wide} when the
 * offered max width meets the threshold, {@code narrow} otherwise — the
 * column-collapse primitive. Both subtrees exist; only the active one is
 * measured/arranged, so the narrow variant pays nothing when inactive.
 */
public final class Breakpoint implements LayoutNode {

    private final int minWidthForWide;
    private final LayoutNode wide;
    private final LayoutNode narrow;
    private LayoutNode active;
    private Rect bounds = Rect.EMPTY;

    public Breakpoint(int minWidthForWide, LayoutNode wide, LayoutNode narrow) {
        this.minWidthForWide = minWidthForWide;
        this.wide = wide;
        this.narrow = narrow;
    }

    public LayoutNode active() { return active; }
    public boolean isWide() { return active == wide; }

    @Override
    public Size measure(Constraints c) {
        active = c.maxWidth() >= minWidthForWide ? wide : narrow;
        return active.measure(c);
    }

    @Override
    public void arrange(Rect r) {
        bounds = r;
        if (active != null) {
            active.arrange(r);
        }
    }

    @Override
    public Rect bounds() { return bounds; }
}
