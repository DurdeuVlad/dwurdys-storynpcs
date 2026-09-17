package com.storynpcs.service;

import com.storynpcs.domain.common.NamespacedId;

import java.util.List;

public record DialogueView(
        NamespacedId dialogueId,
        String nodeId,
        String text,
        String sound,
        List<String> options,
        boolean isTerminal
) {
    public static DialogueView closed(NamespacedId dialogueId) {
        return new DialogueView(dialogueId, "", "", "", List.of(), true);
    }
}
