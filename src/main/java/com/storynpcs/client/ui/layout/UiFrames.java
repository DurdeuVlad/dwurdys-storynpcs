package com.storynpcs.client.ui.layout;

import com.storynpcs.client.ui.UiTheme;

/**
 * Top-level frame math (issue #200): safe-area insets, centered panel with
 * max-width/max-height caps, and the split into header/content/footer bands.
 * Pure int math — {@link com.storynpcs.client.ui.UiScreen} delegates its panel
 * geometry here so chrome math is unit-tested headless.
 */
public final class UiFrames {

    private UiFrames() {}

    /** Full screen minus safe-area insets. */
    public static Rect safeArea(int screenW, int screenH, int inset) {
        return new Rect(inset, inset,
                Math.max(0, screenW - inset * 2), Math.max(0, screenH - inset * 2));
    }

    /**
     * The bounded, centered panel rect for a screen of {@code screenW x screenH}.
     * Floors: never smaller than {@code minW x minH} so degenerate windows
     * degrade gracefully instead of producing negative geometry.
     */
    public static Rect panel(int screenW, int screenH) {
        Rect safe = safeArea(screenW, screenH, UiTheme.PANEL_INSET);
        int w = Math.max(UiTheme.PAD_XL * 2, Math.min(safe.width(), UiTheme.PANEL_MAX_W));
        int h = Math.max(UiTheme.HEADER_H + UiTheme.FOOTER_H + UiTheme.PAD_S * 2 + 1,
                Math.min(safe.height(), UiTheme.PANEL_MAX_H));
        return new Rect(safe.x() + (safe.width() - w) / 2,
                safe.y() + (safe.height() - h) / 2, w, h);
    }

    /** Header band inside a panel. */
    public static Rect header(Rect panel) {
        return new Rect(panel.x(), panel.y(), panel.width(), UiTheme.HEADER_H);
    }

    /** Footer band inside a panel. */
    public static Rect footer(Rect panel) {
        return new Rect(panel.x(), panel.y() + panel.height() - UiTheme.FOOTER_H,
                panel.width(), UiTheme.FOOTER_H);
    }

    /** Content band between header and footer, padded. */
    public static Rect content(Rect panel) {
        return panel.inset(UiTheme.PANEL_PAD,
                UiTheme.HEADER_H + UiTheme.PAD_S,
                UiTheme.PANEL_PAD,
                UiTheme.FOOTER_H + UiTheme.PAD_S);
    }
}
