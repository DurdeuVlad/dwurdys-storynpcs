package com.storynpcs.client.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public class DialogueScreenModel {

    private final String dialogueId;
    private final String nodeId;
    private final String text;
    private final String sound;
    private final List<String> options;
    private final boolean terminal;
    private final Consumer<Integer> optionChooser;
    private final String npcName;
    private final List<String> optionHints;

    private int hoveredOptionIndex = -1;
    private boolean closed = false;

    public DialogueScreenModel(
            String dialogueId,
            String nodeId,
            String text,
            String sound,
            List<String> options,
            boolean terminal,
            Consumer<Integer> optionChooser
    ) {
        this(dialogueId, nodeId, text, sound, options, terminal, optionChooser, "", null);
    }

    public DialogueScreenModel(
            String dialogueId,
            String nodeId,
            String text,
            String sound,
            List<String> options,
            boolean terminal,
            Consumer<Integer> optionChooser,
            String npcName,
            List<String> optionHints
    ) {
        this.dialogueId = dialogueId != null ? dialogueId : "";
        this.nodeId = nodeId != null ? nodeId : "";
        this.text = text != null ? text : "";
        this.sound = sound != null ? sound : "";
        this.options = options != null ? new ArrayList<>(options) : Collections.emptyList();
        this.terminal = terminal;
        this.optionChooser = optionChooser;
        this.npcName = npcName != null ? npcName : "";
        this.optionHints = optionHints != null ? new ArrayList<>(optionHints) : Collections.emptyList();
    }

    public String getDialogueId() {
        return dialogueId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getText() {
        return text;
    }

    public String getSound() {
        return sound;
    }

    public String getNpcName() {
        return npcName;
    }

    /** Consequence hint for option {@code index} ("" when none or out of range). */
    public String getOptionHint(int index) {
        if (index < 0 || index >= optionHints.size()) {
            return "";
        }
        return optionHints.get(index);
    }

    public List<String> getOptions() {
        return Collections.unmodifiableList(options);
    }

    public int getOptionCount() {
        return options.size();
    }

    public boolean isTerminal() {
        return terminal;
    }

    public int getHoveredOptionIndex() {
        return hoveredOptionIndex;
    }

    public void setHoveredOptionIndex(int hoveredOptionIndex) {
        if (hoveredOptionIndex >= -1 && hoveredOptionIndex < options.size()) {
            this.hoveredOptionIndex = hoveredOptionIndex;
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        this.closed = true;
    }

    public boolean chooseOption(int index) {
        if (closed) return false;
        if (index < 0 || index >= options.size()) {
            return false;
        }
        if (optionChooser != null) {
            optionChooser.accept(index);
        }
        return true;
    }

    /**
     * Handles keyboard shortcuts:
     * Digits '1'..'9' (GLFW keycodes 49..57) choose options 0..8
     * Enter (257) or Space (32) chooses currently hovered option
     * Escape (256) closes dialogue
     */
    public boolean handleKeyPress(int keyCode) {
        if (closed) return false;

        // Escape
        if (keyCode == 256) {
            close();
            return true;
        }

        // Digits '1' to '9'
        if (keyCode >= 49 && keyCode <= 57) {
            int targetIndex = keyCode - 49;
            if (targetIndex < options.size()) {
                return chooseOption(targetIndex);
            }
        }

        // Enter or Space
        if (keyCode == 257 || keyCode == 32) {
            if (hoveredOptionIndex >= 0 && hoveredOptionIndex < options.size()) {
                return chooseOption(hoveredOptionIndex);
            }
        }

        return false;
    }
}