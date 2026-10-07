package com.storynpcs.client.ui;

/**
 * Design tokens for the StoryNPCs interface package (issue #198): color
 * palette, spacing scale, and chrome metrics shared by every {@link UiScreen}.
 * Pure value constants — no Minecraft imports — so contrast invariants stay
 * unit-testable headless.
 */
public final class UiTheme {

    private UiTheme() {}

    // ---- surfaces (ARGB) ----
    public static final int SURFACE_BG        = 0xF2161820;
    public static final int SURFACE_HEADER    = 0xF21E232C;
    public static final int SURFACE_RAISED    = 0xF2262B36;
    public static final int BORDER            = 0xFF3A4150;
    public static final int FIELD_BG          = 0xFF10131A;

    // ---- text ----
    public static final int TEXT              = 0xFFE8EAF0;
    public static final int TEXT_MUTED        = 0xFF9AA4B5;
    public static final int TEXT_DISABLED     = 0xFF5A6272;

    // ---- semantic ----
    public static final int ACCENT            = 0xFF38BDF8;
    public static final int SUCCESS           = 0xFF4ADE80;
    public static final int WARNING           = 0xFFFBBF24;
    public static final int DANGER            = 0xFFF87171;

    // ---- interaction fills ----
    public static final int ROW_SELECTED      = 0x3338BDF8;
    public static final int ROW_HOVER         = 0x14FFFFFF;

    // ---- spacing scale (logical px) ----
    public static final int PAD_XS = 2;
    public static final int PAD_S  = 4;
    public static final int PAD_M  = 8;
    public static final int PAD_L  = 12;
    public static final int PAD_XL = 16;

    // ---- chrome metrics (logical px) ----
    public static final int PANEL_INSET = 10;
    public static final int PANEL_PAD   = 8;
    public static final int PANEL_MAX_W = 420;
    public static final int PANEL_MAX_H = 320;
    public static final int HEADER_H    = 18;
    public static final int FOOTER_H    = 22;
    public static final int BUTTON_H    = 14;
    public static final int ROW_H       = 12;

    /** WCAG contrast ratio between two ARGB colors, 1.0-21.0. */
    public static double contrastRatio(int argbA, int argbB) {
        double la = luminance(argbA);
        double lb = luminance(argbB);
        double hi = Math.max(la, lb);
        double lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    /** sRGB relative luminance (0=black, 1=white). Alpha ignored. */
    static double luminance(int argb) {
        double r = linearize(((argb >> 16) & 0xFF) / 255.0);
        double g = linearize(((argb >> 8) & 0xFF) / 255.0);
        double b = linearize((argb & 0xFF) / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double linearize(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
