package com.storynpcs.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P5-3 layout contract: at the supported minimum viewport (427x240 logical)
 * every panel stays on-screen and the canvas keeps a usable residue.
 */
class EditorViewportTest {

    @Test
    @DisplayName("Minimum viewport keeps inspector, canvas, and panes in bounds")
    void testMinViewportLayout() {
        var layout = EditorViewport.layout(
                EditorViewport.MIN_WIDTH, EditorViewport.MIN_HEIGHT, true, false);

        assertTrue(layout.compact());
        // Inspector on-screen and inside the window
        assertTrue(layout.inspectorW() >= EditorViewport.INSPECTOR_MIN_W);
        assertTrue(layout.inspectorX() >= 0);
        assertTrue(layout.inspectorX() + layout.inspectorW() <= EditorViewport.MIN_WIDTH);
        assertTrue(layout.inspectorH() >= EditorViewport.INSPECTOR_MIN_H);
        assertTrue(layout.inspectorY() + layout.inspectorH() <= EditorViewport.MIN_HEIGHT);
        // Diagnostics pane stays inside the window
        assertTrue(layout.diagnosticsX() >= 0);
        assertTrue(layout.diagnosticsX() + layout.diagnosticsW() <= EditorViewport.MIN_WIDTH);
        // Preview is suppressed — the canvas residue would be too small
        assertEquals(0, layout.previewW());
        assertTrue(layout.canvasW() > 0, "canvas must keep a usable residue");
    }

    @Test
    @DisplayName("Wide viewport admits the preview pane beside the inspector and canvas")
    void testWideViewportLayout() {
        var layout = EditorViewport.layout(1280, 800, true, true);

        assertFalse(layout.compact());
        assertEquals(200, layout.inspectorW());
        assertTrue(layout.previewW() >= 120, "wide screens get a preview pane");
        assertTrue(layout.canvasW() >= EditorViewport.PREVIEW_MIN_CANVAS_W);
        // No overlaps: preview | canvas | diagnostics? | inspector
        assertTrue(layout.previewX() + layout.previewW() <= layout.diagnosticsX() + layout.diagnosticsW());
        assertTrue(layout.diagnosticsX() + layout.diagnosticsW() <= layout.inspectorX() + EditorViewport.MARGIN);
    }

    @Test
    @DisplayName("Closed inspector frees its width for the canvas")
    void testNoInspectorLayout() {
        var layout = EditorViewport.layout(800, 480, false, false);
        assertEquals(0, layout.inspectorW());
        assertTrue(layout.canvasW() > 500);
    }
}
