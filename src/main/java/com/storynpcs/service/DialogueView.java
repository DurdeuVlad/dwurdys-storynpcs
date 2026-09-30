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
        List<String> optionHints,
        List<String> optionTokens
) {
    public DialogueView {
        npcName = npcName != null ? npcName : "";
        optionHints = optionHints != null ? optionHints : List.of();
        options = options != null ? options : List.of();
        // Tokens are parallel to options: index i of options is authorized by
        // token i of optionTokens. A view with fewer tokens than options is
        // malformed; the missing choices can never be accepted.
        optionTokens = optionTokens != null ? optionTokens : List.of();
    }

    /** Convenience constructor for views without speaker/hint/token metadata. */
    public DialogueView(NamespacedId dialogueId, String nodeId, String text, String sound,
                        List<String> options, boolean isTerminal) {
        this(dialogueId, nodeId, text, sound, options, isTerminal, "", List.of(), List.of());
    }

    /** Convenience constructor for views without issued choice tokens. */
    public DialogueView(NamespacedId dialogueId, String nodeId, String text, String sound,
                        List<String> options, boolean isTerminal, String npcName,
                        List<String> optionHints) {
        this(dialogueId, nodeId, text, sound, options, isTerminal, npcName, optionHints, List.of());
    }

    public static DialogueView closed(NamespacedId dialogueId) {
        return new DialogueView(dialogueId, "", "", "", List.of(), true);
    }
}
