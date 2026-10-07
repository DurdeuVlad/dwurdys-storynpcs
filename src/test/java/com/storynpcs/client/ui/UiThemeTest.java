package com.storynpcs.client.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UiThemeTest {

    @Test
    @DisplayName("contrastRatio is symmetric and bounded [1, 21]")
    void contrastRatioBounds() {
        assertEquals(21.0, UiTheme.contrastRatio(0xFFFFFFFF, 0xFF000000), 0.01);
        assertEquals(1.0, UiTheme.contrastRatio(0xFF161820, 0xFF161820), 0.001);
        assertEquals(UiTheme.contrastRatio(UiTheme.TEXT, UiTheme.SURFACE_BG),
                UiTheme.contrastRatio(UiTheme.SURFACE_BG, UiTheme.TEXT), 0.0001);
    }

    @Test
    @DisplayName("primary and muted text stay WCAG-AA readable on panel surfaces")
    void textContrast() {
        assertTrue(UiTheme.contrastRatio(UiTheme.TEXT, UiTheme.SURFACE_BG) >= 7.0,
                "TEXT on SURFACE_BG below AAA");
        assertTrue(UiTheme.contrastRatio(UiTheme.TEXT_MUTED, UiTheme.SURFACE_BG) >= 4.5,
                "TEXT_MUTED on SURFACE_BG below AA");
        assertTrue(UiTheme.contrastRatio(UiTheme.TEXT, UiTheme.SURFACE_HEADER) >= 7.0,
                "TEXT on SURFACE_HEADER below AAA");
        assertTrue(UiTheme.contrastRatio(UiTheme.TEXT_MUTED, UiTheme.SURFACE_HEADER) >= 4.5,
                "TEXT_MUTED on SURFACE_HEADER below AA");
    }

    @Test
    @DisplayName("semantic colors stay readable on panel surfaces")
    void semanticContrast() {
        for (int color : new int[]{UiTheme.ACCENT, UiTheme.SUCCESS, UiTheme.WARNING, UiTheme.DANGER}) {
            assertTrue(UiTheme.contrastRatio(color, UiTheme.SURFACE_BG) >= 4.5,
                    "semantic color 0x" + Integer.toHexString(color) + " below AA on surface");
        }
    }

    @Test
    @DisplayName("spacing scale is positive and strictly increasing")
    void spacingScale() {
        assertTrue(UiTheme.PAD_XS > 0);
        assertTrue(UiTheme.PAD_XS < UiTheme.PAD_S);
        assertTrue(UiTheme.PAD_S < UiTheme.PAD_M);
        assertTrue(UiTheme.PAD_M < UiTheme.PAD_L);
        assertTrue(UiTheme.PAD_L < UiTheme.PAD_XL);
    }

    @Test
    @DisplayName("chrome metrics leave room for content on the smallest canvas")
    void chromeFits() {
        // 854x480 @ guiScale 4 -> ~213x120 logical is the floor; chrome must
        // still leave a positive content band.
        int panelH = Math.min(120 - UiTheme.PANEL_INSET * 2, UiTheme.PANEL_MAX_H);
        int content = panelH - UiTheme.HEADER_H - UiTheme.FOOTER_H - UiTheme.PAD_S * 2;
        assertTrue(content > 0, "chrome leaves no content band on smallest canvas");
        assertTrue(UiTheme.BUTTON_H <= UiTheme.FOOTER_H, "button taller than footer");
    }
}
