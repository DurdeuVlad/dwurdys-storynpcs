package com.storynpcs.client.ui.widgets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** #199: pending flag lifecycle + timeout escape hatch. */
class BusyStateTest {

    @Test
    @DisplayName("begin marks pending until end() or timeout")
    void lifecycle() {
        BusyState b = new BusyState();
        assertFalse(b.isPending(0));

        b.begin(1000, 5000);
        assertTrue(b.isPending(5999));
        assertFalse(b.isPending(6000), "timeout must release a dropped reply");

        b.begin(1000, 5000);
        b.end();
        assertFalse(b.isPending(1001), "server reply clears pending");
    }

    @Test
    @DisplayName("FieldValidator composition and built-ins")
    void validators() {
        FieldValidator v = FieldValidator.all(
                FieldValidator.required("Name"),
                FieldValidator.maxLength(3),
                FieldValidator.namespacedId());
        assertEquals("Name is required", v.validate("  "));
        assertEquals("Must be at most 3 characters", v.validate("a:bcd"));
        assertEquals("Expected namespace:id, e.g. storynpcs:my_quest", v.validate("abc"));
        assertNull(v.validate("a:b"));
        assertNull(FieldValidator.namespacedId().validate(null));
    }
}
