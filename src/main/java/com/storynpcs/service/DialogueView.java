package com.storynpcs.service;

import com.storynpcs.domain.common.NamespacedId;

import java.util.List;

public record DialogueView(
        NamespacedId dialogueId,
        String nodeId,
        String text,
        String sound,
        List<String> options,
        boolean isTerminal,
        String npcName,
        List<String> optionHints
) {
    public DialogueView {
        npcName = npcName != null ? npcName : "";
        optionHints = optionHints != null ? optionHints : List.of();
        options = options != null ? options : List.of();
    }

    /** Convenience constructor for views without speaker/hint metadata. */
    public DialogueView(NamespacedId dialogueId, String nodeId, String text, String sound,
                        List<String> options, boolean isTerminal) {
        this(dialogueId, nodeId, text, sound, options, isTerminal, "", List.of());
    }

    public static DialogueView closed(NamespacedId dialogueId) {
        return new DialogueView(dialogueId, "", "", "", List.of(), true);
    }
}
