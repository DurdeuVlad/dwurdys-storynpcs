package com.storynpcs.client.ui.layout;

/**
 * A node in the layout tree (issue #200): two-phase measure/arrange over int
 * geometry. The contract is deterministic — the same constraint tree always
 * produces the same geometry, with no Minecraft types involved.
 *
 * <p>Usage: call {@link #measure} with the incoming {@link Constraints}, then
 * {@link #arrange} with the granted {@link Rect}. {@link #bounds} is valid
 * after arrange.
 */
public interface LayoutNode {

    /**
     * Preferred size within {@code constraints}; must honor the minima.
     * Must be idempotent — {@link Viewport#arrange} re-invokes it at the
     * granted width, and containers may re-measure children.
     */
    Size measure(Constraints constraints);

    /** Assigns final geometry; always called after measure. */
    void arrange(Rect bounds);

    /** The rect assigned by the last {@link #arrange}; EMPTY before. */
    default Rect bounds() { return Rect.EMPTY; }
}
