package com.storynpcs.client.ui.widgets;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** #199: transient message lifecycle + severity colors. */
class StatusEchoTest {

    @Test
    @DisplayName("message lives until TTL, then lazily clears")
    void expiry() {
        StatusEcho echo = new StatusEcho();
        assertNull(echo.current(0));
        echo.show(StatusEcho.Severity.INFO, Component.literal("sent"), 1000, 500);
        assertNotNull(echo.current(1499));
        assertNull(echo.current(1500), "expired at boundary");
        assertNull(echo.current(9999));
    }

    @Test
    @DisplayName("latest show wins; clear drops immediately")
    void replaceAndClear() {
        StatusEcho echo = new StatusEcho();
        echo.show(StatusEcho.Severity.ERROR, Component.literal("first"), 5000, 0);
        echo.show(StatusEcho.Severity.SUCCESS, Component.literal("second"), 5000, 100);
        StatusEcho.Message m = echo.current(200);
        assertEquals(StatusEcho.Severity.SUCCESS, m.severity());
        assertEquals("second", m.text().getString());
        echo.clear();
        assertNull(echo.current(200));
    }

    @Test
    @DisplayName("severity maps to theme colors")
    void colors() {
        StatusEcho echo = new StatusEcho();
        assertEquals(0xFF9AA4B5, echo.colorFor(StatusEcho.Severity.INFO));
        assertEquals(0xFF4ADE80, echo.colorFor(StatusEcho.Severity.SUCCESS));
        assertEquals(0xFFFBBF24, echo.colorFor(StatusEcho.Severity.WARNING));
        assertEquals(0xFFF87171, echo.colorFor(StatusEcho.Severity.ERROR));
    }
}
