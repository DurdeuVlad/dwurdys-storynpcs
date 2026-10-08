package com.storynpcs.client;

import com.storynpcs.client.model.DialogueScreenModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DialogueScreenModelTest {

    @Test
    @DisplayName("DialogueScreenModel stores dialogue state correctly")
    void testModelInitialization() {
        List<String> options = List.of("Yes, sir.", "Not right now.", "Tell me more.");
        DialogueScreenModel model = new DialogueScreenModel(
                "storynpcs:captain_dialogue",
                "start",
                "Hello recruit, ready for duty?",
                "minecraft:entity.villager.ambient",
                options,
                false,
                null
        );

        assertEquals("storynpcs:captain_dialogue", model.getDialogueId());
        assertEquals("start", model.getNodeId());
        assertEquals("Hello recruit, ready for duty?", model.getText());
        assertEquals("minecraft:entity.villager.ambient", model.getSound());
        assertEquals(3, model.getOptionCount());
        assertEquals("Yes, sir.", model.getOptions().get(0));
        assertFalse(model.isTerminal());
        assertFalse(model.isClosed());
    }

    @Test
    @DisplayName("Choosing options triggers callback with valid index and ignores invalid index")
    void testOptionSelection() {
        AtomicInteger chosen = new AtomicInteger(-1);
        DialogueScreenModel model = new DialogueScreenModel(
                "d1", "n1", "Test", "",
                List.of("Option 0", "Option 1"),
                false,
                chosen::set
        );

        assertFalse(model.chooseOption(-1));
        assertFalse(model.chooseOption(5));

        assertTrue(model.chooseOption(1));
        assertEquals(1, chosen.get());

        // Single-flight: a second activation would echo a consumed token and
        // the server fails closed, ending the session — so it is swallowed.
        assertFalse(model.chooseOption(0));
        assertEquals(1, chosen.get(), "Choice must not re-fire after one is sent");
    }

    @Test
    @DisplayName("Keyboard shortcuts: digit keys 1-9 map to option indexes 0-8")
    void testDigitKeyboardShortcuts() {
        // Single-flight model: each digit needs a fresh screen model.
        for (int key = 49; key <= 53; key++) {
            AtomicInteger chosen = new AtomicInteger(-1);
            DialogueScreenModel model = new DialogueScreenModel(
                    "d1", "n1", "Test", "",
                    List.of("First", "Second", "Third"),
                    false,
                    chosen::set
            );

            if (key - 49 < 3) {
                assertTrue(model.handleKeyPress(key), "digit " + (key - 48) + " should choose");
                assertEquals(key - 49, chosen.get());
            } else {
                assertFalse(model.handleKeyPress(key), "no option " + (key - 48));
            }
        }
    }

    @Test
    @DisplayName("Keyboard shortcuts: Space or Enter chooses hovered option, Escape closes")
    void testSpecialKeys() {
        AtomicInteger chosen = new AtomicInteger(-1);
        DialogueScreenModel model = new DialogueScreenModel(
                "d1", "n1", "Test", "",
                List.of("First", "Second"),
                false,
                chosen::set
        );

        model.setHoveredOptionIndex(1);
        assertEquals(1, model.getHoveredOptionIndex());

        // Key code 257 = Enter
        assertTrue(model.handleKeyPress(257));
        assertEquals(1, chosen.get());

        // Key code 256 = Escape
        assertTrue(model.handleKeyPress(256));
        assertTrue(model.isClosed());

        // After close, further input is rejected
        assertFalse(model.chooseOption(0));
        assertFalse(model.handleKeyPress(49));
    }

    @Test
    @DisplayName("Speaker name and option hints are exposed; missing hints yield empty string")
    void testNpcNameAndOptionHints() {
        DialogueScreenModel model = new DialogueScreenModel(
                "d1", "n1", "Test", "",
                List.of("Take the job", "Refuse"),
                false,
                null,
                "Quartermaster",
                List.of("Quest: Supply Run", "")
        );

        assertEquals("Quartermaster", model.getNpcName());
        assertEquals("Quest: Supply Run", model.getOptionHint(0));
        assertEquals("", model.getOptionHint(1));
        assertEquals("", model.getOptionHint(-1));
        assertEquals("", model.getOptionHint(9));
    }

    @Test
    @DisplayName("Legacy constructor defaults to no speaker and no hints")
    void testLegacyCtorDefaults() {
        DialogueScreenModel model = new DialogueScreenModel(
                "d1", "n1", "Test", "", List.of("A"), false, null);

        assertEquals("", model.getNpcName());
        assertEquals("", model.getOptionHint(0));
    }
}