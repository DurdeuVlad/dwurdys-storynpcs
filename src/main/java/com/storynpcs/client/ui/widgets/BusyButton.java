package com.storynpcs.client.ui.widgets;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;


/**
 * Button that reflects an in-flight server action (issue #199): pressing it
 * disables the button and swaps the label to a busy message until
 * {@link #endBusy()} runs (the reply arrived) or {@link BusyState}'s timeout
 * expires — so a dropped clientbound packet can't wedge the control.
 *
 * <p>Build via {@link #builder}: the wrapped {@link Button.OnPress} fires
 * after the busy flag is set, so handlers can stay synchronous-looking.
 */
public class BusyButton extends Button {

    private final BusyState busy = new BusyState();
    private final Component idleLabel;
    private final Component busyLabel;
    private final long timeoutMs;

    protected BusyButton(int x, int y, int w, int h, Component label, Component busyLabel,
                         long timeoutMs, OnPress onPress) {
        super(x, y, w, h, label, b -> {
            BusyButton self = (BusyButton) b;
            self.busy.begin(System.currentTimeMillis(), self.timeoutMs);
            self.syncState();
            onPress.onPress(b);
        }, DEFAULT_NARRATION);
        this.idleLabel = label;
        this.busyLabel = busyLabel;
        this.timeoutMs = timeoutMs;
    }

    /** The server replied (success or failure) — restore the idle label. */
    public void endBusy() {
        busy.end();
        syncState();
    }

    public boolean isBusy() { return busy.isPending(System.currentTimeMillis()); }

    private void syncState() {
        boolean pending = busy.isPending(System.currentTimeMillis());
        this.active = !pending;
        setMessage(pending ? busyLabel : idleLabel);
    }

    @Override
    public void renderWidget(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (busy.expired(System.currentTimeMillis()) && !this.active) {
            syncState();
        }
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
    }

    public static Builder builder(Component label, Component busyLabel, OnPress onPress) {
        return new Builder(label, busyLabel, onPress);
    }

    public static final class Builder {
        private final Component label;
        private final Component busyLabel;
        private final OnPress onPress;
        private long timeoutMs = BusyState.DEFAULT_TIMEOUT_MS;
        private int x, y, w = 54, h = 14;

        private Builder(Component label, Component busyLabel, OnPress onPress) {
            this.label = label;
            this.busyLabel = busyLabel;
            this.onPress = onPress;
        }

        public Builder bounds(int x, int y, int w, int h) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            return this;
        }

        public Builder timeout(long millis) {
            this.timeoutMs = millis;
            return this;
        }

        public BusyButton build() {
            return new BusyButton(x, y, w, h, label, busyLabel, timeoutMs, onPress);
        }
    }
}
