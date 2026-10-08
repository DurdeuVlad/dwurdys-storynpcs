package com.storynpcs.client.ui.widgets;

import com.storynpcs.client.ui.UiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Transient status echo (issue #199): one short message with a severity color
 * and a millisecond TTL — the "did anything happen?" feedback the audit's D5
 * flagged as missing (silent no-ops, stale post-action state).
 *
 * <p>State ({@link #show}, {@link #current}, expiry) is pure and unit-testable;
 * {@link #render} is a thin drawing call any screen can make in its footer or
 * content area. Not a widget — it owns no hit region.
 */
public final class StatusEcho {

    public enum Severity { INFO, SUCCESS, WARNING, ERROR }

    public record Message(Severity severity, Component text, long expiresAtMillis) {
        public boolean expired(long now) { return now >= expiresAtMillis; }
    }

    public static final long DEFAULT_TTL_MS = 4000;

    private Message current;

    /** Publishes a message that auto-expires after {@code ttlMillis}. */
    public void show(Severity severity, Component text, long ttlMillis, long nowMillis) {
        current = new Message(severity, text, nowMillis + ttlMillis);
    }

    /** The live message, or null when absent/expired. Lazily cleared. */
    public Message current(long nowMillis) {
        if (current != null && current.expired(nowMillis)) {
            current = null;
        }
        return current;
    }

    public void clear() { current = null; }

    public int colorFor(Severity severity) {
        return switch (severity) {
            case INFO -> UiTheme.TEXT_MUTED;
            case SUCCESS -> UiTheme.SUCCESS;
            case WARNING -> UiTheme.WARNING;
            case ERROR -> UiTheme.DANGER;
        };
    }

    /** Draws the current message left-aligned at (x, y) if one is live. */
    public void render(GuiGraphics graphics, Font font, int x, int y, long nowMillis) {
        Message m = current(nowMillis);
        if (m != null) {
            graphics.drawString(font, m.text(), x, y, colorFor(m.severity()));
        }
    }
}
