package com.storynpcs.api.event;

import java.util.UUID;

import com.storynpcs.domain.common.NamespacedId;

/**
 * P8-6 custom-GUI/overlay events — server-authoritative parity records for the
 * target {@code CustomGuiEvent} family and overlay surfaces. Interaction events
 * (button/scroll/slot-click) publish only through the canonical packet boundary
 * (P1-3); open/close/overlay transitions are live now.
 */
public final class P86GuiEvents {

    private P86GuiEvents() {}

    /** A custom-GUI layout was opened for a player session. */
    public record CustomGuiOpenedEvent(NamespacedId layoutId, UUID playerUuid,
                                       UUID sessionId) implements StoryNpcsEvent {}

    /** A player interaction inside a custom GUI (button/scroll/slot semantics). */
    public record CustomGuiActionEvent(NamespacedId layoutId, UUID playerUuid,
                                       String elementPath, String action)
            implements StoryNpcsEvent {}

    /** A custom-GUI session closed (logout, expiry, or explicit close). */
    public record CustomGuiClosedEvent(NamespacedId layoutId, UUID playerUuid,
                                       UUID sessionId, String reason) implements StoryNpcsEvent {}

    /** An overlay was shown to a session. */
    public record OverlayShownEvent(NamespacedId layoutId, String elementId,
                                    UUID playerUuid, UUID overlayId,
                                    int expiryTick) implements StoryNpcsEvent {}

    /** An overlay expired or was dropped by session close. */
    public record OverlayExpiredEvent(UUID playerUuid, UUID overlayId,
                                      String elementId, String reason) implements StoryNpcsEvent {}
}
