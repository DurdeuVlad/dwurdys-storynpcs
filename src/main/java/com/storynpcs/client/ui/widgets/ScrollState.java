package com.storynpcs.client.ui.widgets;

/**
 * Pure scroll-offset state machine (issue #199): tracks a content size,
 * viewport size, and a clamped offset. No Minecraft types — the state is
 * fully unit-testable headless; {@link ScrollRegion} renders it.
 *
 * <p>Offsets are in "rows" (or arbitrary units) chosen by the caller —
 * pixel-based scrolling multiplies by the row height at render time.
 */
public final class ScrollState {

    private int contentSize;
    private int viewportSize;
    private int offset;

    public int contentSize() { return contentSize; }
    public int viewportSize() { return viewportSize; }

    /** Largest legal offset: 0 when everything fits. */
    public int maxOffset() { return Math.max(0, contentSize - viewportSize); }

    /** Whether any content is hidden — drives scrollbar visibility. */
    public boolean scrollable() { return contentSize > viewportSize; }

    public int offset() { return offset; }

    public void setContentSize(int size) {
        contentSize = Math.max(0, size);
        clamp();
    }

    public void setViewportSize(int size) {
        viewportSize = Math.max(0, size);
        clamp();
    }

    /** One wheel notch: positive scrollY scrolls up (decrement), one unit. */
    public boolean wheel(double scrollY) {
        return wheel(scrollY, 1);
    }

    /**
     * One wheel notch of {@code step} units — pixel-based regions pass their
     * row height; list widgets use the 1-unit default. Positive scrollY
     * scrolls up.
     */
    public boolean wheel(double scrollY, int step) {
        if (scrollY == 0 || !scrollable()) {
            return false;
        }
        return scrollBy((scrollY > 0 ? -1 : 1) * Math.max(1, step));
    }

    /** Scrolls by one viewport page (PageUp/PageDown semantics). */
    public boolean page(int direction) {
        return scrollBy(direction * Math.max(1, viewportSize - 1));
    }

    public boolean scrollBy(int delta) {
        return scrollTo(offset + delta);
    }

    public boolean scrollTo(int target) {
        int clamped = Math.max(0, Math.min(maxOffset(), target));
        if (clamped == offset) {
            return false;
        }
        offset = clamped;
        return true;
    }

    /** Re-clamps after size changes so a stale offset can't blank the view. */
    private void clamp() {
        offset = Math.max(0, Math.min(maxOffset(), offset));
    }
}
