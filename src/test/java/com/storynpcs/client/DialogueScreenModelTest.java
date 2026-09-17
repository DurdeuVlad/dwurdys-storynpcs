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

        assertTrue(model.chooseOption(1));
        assertEquals(1, chosen.get());

        assertTrue(model.chooseOption(0));
        assertEquals(0, chosen.get());

        assertFalse(model.chooseOption(-1));
        assertEquals(0, chosen.get(), "Invalid index should not update selection");

        assertFalse(model.chooseOption(5));
        assertEquals(0, chosen.get(), "Out-of-bounds index should not update selection");
    }

    @Test
    @DisplayName("Keyboard shortcuts: digit keys 1-9 map to option indexes 0-8")
    void testDigitKeyboardShortcuts() {
        AtomicInteger chosen = new AtomicInteger(-1);
        DialogueScreenModel model = new DialogueScreenModel(
                "d1", "n1", "Test", "",
                List.of("First", "Second", "Third"),
                false,
                chosen::set
        );

        // Key code 49 = '1'
        assertTrue(model.handleKeyPress(49));
        assertEquals(0, chosen.get());

        // Key code 50 = '2'
        assertTrue(model.handleKeyPress(50));
        assertEquals(1, chosen.get());

        // Key code 51 = '3'
        assertTrue(model.handleKeyPress(51));
        assertEquals(2, chosen.get());

        // Key code 52 = '4' (no 4th option)
        assertFalse(model.handleKeyPress(52));
        assertEquals(2, chosen.get());
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
}