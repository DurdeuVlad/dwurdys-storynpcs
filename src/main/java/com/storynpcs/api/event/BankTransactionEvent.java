package com.storynpcs.api.event;

import java.util.UUID;

public record BankTransactionEvent(
        UUID playerUuid,
        Type type,
        int tab,
        String itemId,
        int count
) implements StoryNpcsEvent {
    public enum Type { DEPOSIT, WITHDRAW, UNLOCK_TAB }
}