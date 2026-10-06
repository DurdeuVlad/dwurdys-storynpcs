package com.storynpcs.editor;

/**
 * Responsive layout contract for the dialogue editor (P5-3). Computes where
 * the toolbar, inspector, diagnostics pane, and preview pane land for a given
 * window size so every panel stays on-screen at the supported minimum —
 * 854x480 physical / GUI scale 2 = 427x240 logical viewport.
 *
 * <p>Layout rules, smallest-first: the toolbar always fits; the inspector
 * shrinks to {@link #INSPECTOR_MIN_W} and {@link #INSPECTOR_MIN_H} before
 * anything else gives; the diagnostics strip collapses to a one-line summary
 * below {@link #COMPACT_WIDTH}; the preview pane overlays the left edge only
 * while the viewport is wide enough to leave a usable canvas.
 */
public final class EditorViewport {

    /** Minimum supported logical viewport (854x480 physical @ GUI scale 2). */
    public static final int MIN_WIDTH = 427;
    public static final int MIN_HEIGHT = 240;

    /** Two toolbar rows (P5-3): primary actions + search/history/IO row. */
    public static final int TOOLBAR_HEIGHT = 62;
    public static final int STATUS_BAR_HEIGHT = 16;

    public static final int INSPECTOR_MAX_W = 200;
    /** Inspector narrows on small screens but never below this width. */
    public static final int INSPECTOR_MIN_W = 140;
    public static final int INSPECTOR_MAX_H = 260;
    public static final int INSPECTOR_MIN_H = 140;
    public static final int INSPECTOR_Y = TOOLBAR_HEIGHT + 6;
    public static final int MARGIN = 8;

    /** Below this width the diagnostics pane shows only the error-count line. */
    public static final int COMPACT_WIDTH = 500;
    /** Preview pane needs at least this much residual canvas to be useful. */
    public static final int PREVIEW_MIN_CANVAS_W = 160;

    public record Layout(int width, int height, boolean compact,
                         int inspectorX, int inspectorY, int inspectorW, int inspectorH,
                         int diagnosticsX, int diagnosticsY, int diagnosticsW, int diagnosticsH,
                         int previewX, int previewY, int previewW, int previewH,
                         int canvasW) {}

    public static Layout layout(int width, int height, boolean inspectorOpen, boolean previewOpen) {
        boolean compact = width < COMPACT_WIDTH;

        int inspectorW = inspectorOpen
                ? Math.max(INSPECTOR_MIN_W, Math.min(INSPECTOR_MAX_W, width / 3))
                : 0;
        int inspectorH = inspectorOpen
                ? Math.max(INSPECTOR_MIN_H, Math.min(INSPECTOR_MAX_H, height - INSPECTOR_Y - MARGIN))
                : 0;
        int inspectorX = width - inspectorW - MARGIN;
        int inspectorY = INSPECTOR_Y;

        int diagH = Math.max(24, height - INSPECTOR_Y - STATUS_BAR_HEIGHT - 2 * MARGIN);

        // Preview hugs the left edge; it disappears if the canvas would shrink
        // below a usable width.
        int previewW = previewOpen
                ? Math.min(200, width - inspectorW - PREVIEW_MIN_CANVAS_W - 3 * MARGIN) : 0;
        if (previewW < 120) {
            previewW = 0;
        }

        // Diagnostics hug the right edge, to the left of the inspector.
        int diagnosticsW = compact
                ? Math.max(120, width - inspectorW - previewW - 4 * MARGIN) : 220;
        int diagnosticsX = width - inspectorW - diagnosticsW - (inspectorW > 0 ? 2 : 1) * MARGIN;
        int diagnosticsY = INSPECTOR_Y;

        int previewX = MARGIN;
        int previewY = INSPECTOR_Y;
        int previewH = diagH;

        int canvasW = width - inspectorW - (previewW > 0 ? previewW + MARGIN : 0) - 2 * MARGIN;

        return new Layout(width, height, compact,
                inspectorX, inspectorY, inspectorW, inspectorH,
                diagnosticsX, diagnosticsY, diagnosticsW, diagH,
                previewX, previewY, previewW, previewH,
                canvasW);
    }
}
