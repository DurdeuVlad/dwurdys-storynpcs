package com.storynpcs.client.ui;

import com.storynpcs.client.ui.layout.Rect;
import com.storynpcs.client.ui.layout.UiFrames;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Base chrome for StoryNPCs screens (issue #198): a bounded, centered panel
 * surface with title header, footer action row, and an optional status line —
 * replacing the "content floats on dimmed world" pattern flagged in the beta
 * audit (D1).
 *
 * <p>Render order is fixed and guarded: the world background/blur pass runs
 * exactly once, before any StoryNPCs pixels exist, so the post-process blur
 * can never smear panel content (the defect reported in #197). Subclass
 * content renders inside {@link #renderContent}; vanilla widgets render last.
 *
 * <p>Subclass contract: implement {@link #renderContent} for the body, and
 * {@link #initContent} (not {@code init} — it is final so panel metrics always
 * exist before widget layout) to add widgets and footer actions.
 *
 * <p><b>Canvas exception (issue #209):</b> screens whose primary surface is a
 * bespoke canvas rather than form content — currently only
 * {@code DialogueEditorScreen}, a pan/zoom node-edge graph — may stay plain
 * {@code Screen}s instead of extending this class. The exception covers
 * canvas geometry and graph rendering only: toolbar, inspector, dialogs, and
 * status surfaces must still draw from {@link UiTheme} tokens, and any canvas
 * colors must be named constants, never inline literals.
 */
public abstract class UiScreen extends Screen {

    private final List<FooterAction> footerActions = new ArrayList<>();
    private final List<Button> footerButtons = new ArrayList<>();
    private Component status;
    private Component baseStatus;
    private long statusUntilMs = Long.MAX_VALUE;
    private boolean suppressBackground;

    protected int panelX, panelY, panelW, panelH;

    private record FooterAction(Component label, Button.OnPress onPress) {}

    protected UiScreen(Component title) {
        super(title);
    }

    @Override
    protected final void init() {
        // Panel geometry comes from the layout engine (UiFrames), so chrome
        // math is headless-tested and every UiScreen shares it (#200).
        Rect panel = UiFrames.panel(this.width, this.height);
        panelX = panel.x();
        panelY = panel.y();
        panelW = panel.width();
        panelH = panel.height();
        footerActions.clear();
        footerButtons.clear();
        status = null;
        baseStatus = null;
        initContent();
        layoutFooter();
        postInit();
    }

    /** Add content widgets and footer actions; panel metrics are already set. */
    protected void initContent() {}

    /**
     * Runs after footer buttons exist — screens can adjust {@code .active}
     * (e.g. re-apply an armed pending state) without waiting for a tick.
     */
    protected void postInit() {}

    /** Body rendering between header and footer; default is empty. */
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    /**
     * Register a footer button; laid out right-aligned in the footer row.
     * Valid only during {@link #initContent} — actions added after {@link #init}
     * completes are never laid out and are discarded on the next rebuild.
     */
    protected void addFooterAction(Component label, Button.OnPress onPress) {
        footerActions.add(new FooterAction(label, onPress));
    }

    /**
     * Cycle-picker button (issue #208): the label is re-read from the supplier
     * after every click instead of baked once at build time, so option cycling
     * can't leave a stale caption the way a static message did.
     */
    protected Button cycleBtn(java.util.function.Supplier<String> label,
                              int x, int y, int w, Runnable onClick) {
        return Button.builder(Component.literal(label.get()), b -> {
            onClick.run();
            b.setMessage(Component.literal(label.get()));
        }).bounds(x, y, Math.max(20, w), UiTheme.BUTTON_H).build();
    }

    /** Persistent status line rendered at the left of the footer; null clears it. */
    protected void setStatus(Component status) {
        this.baseStatus = status;
        this.status = status;
        this.statusUntilMs = Long.MAX_VALUE;
    }

    /**
     * Transient status echo (issue #199/#201): shows for {@code millis} then
     * reverts to the {@link #setStatus} base status — used for "Select a row
     * first." style feedback. A later {@link #setStatus} call overrides both.
     */
    protected void echo(Component status, int millis) {
        this.status = status;
        this.statusUntilMs = System.currentTimeMillis() + Math.max(0, millis);
    }

    /**
     * Overrides the shown status without touching the base status or arming
     * an expiry — for pending labels that own the footer until replaced.
     */
    protected void overrideStatus(Component status) {
        this.status = status;
        this.statusUntilMs = Long.MAX_VALUE;
    }

    /**
     * Footer buttons in {@link #addFooterAction} declaration order — screens
     * toggle {@code .active} for pending/disabled states without a rebuild.
     */
    protected List<Button> footerButtons() {
        return footerButtons;
    }

    private void layoutFooter() {
        int x = contentRight();
        int by = footerTop() + (UiTheme.FOOTER_H - UiTheme.BUTTON_H) / 2 + 1;
        for (int i = footerActions.size() - 1; i >= 0; i--) {
            FooterAction action = footerActions.get(i);
            int w = Math.max(54, this.font.width(action.label()) + UiTheme.PAD_L * 2);
            w = Math.min(w, x - contentLeft());
            if (w < 20) break;
            x -= w;
            Button btn = Button.builder(action.label(), action.onPress())
                    .bounds(x, by, w, UiTheme.BUTTON_H).build();
            footerButtons.add(btn);
            addRenderableWidget(btn);
            x -= UiTheme.PAD_S;
        }
        // Declared order is left-to-right; layout produced right-to-left —
        // restore declaration order for callers indexing footerButtons().
        java.util.Collections.reverse(footerButtons);
    }

    // ---- geometry helpers for subclass content ----

    protected int contentLeft()   { return panelX + UiTheme.PANEL_PAD; }
    protected int contentRight()  { return panelX + panelW - UiTheme.PANEL_PAD; }
    protected int contentTop()    { return panelY + UiTheme.HEADER_H + UiTheme.PAD_S; }
    protected int contentWidth()  { return contentRight() - contentLeft(); }
    protected int footerTop()     { return panelY + panelH - UiTheme.FOOTER_H; }
    protected int contentBottom() { return footerTop() - UiTheme.PAD_S; }

    @Override
    public final void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        renderChrome(graphics);
        renderContent(graphics, mouseX, mouseY, partialTick);
        suppressBackground = true;
        try {
            super.render(graphics, mouseX, mouseY, partialTick);
        } finally {
            suppressBackground = false;
        }
    }

    /**
     * Runs the vanilla background exactly once per frame, at the top of
     * {@link #render}. {@code super.render} may invoke it again after our
     * content exists — the gate swallows that second call so the blur pass
     * stays behind every StoryNPCs pixel.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (suppressBackground) {
            return;
        }
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
    }

    /** Called when a timed {@link #echo} clears — screens may restore a default status. */
    protected void onStatusExpired() {}

    /** Shared empty-state line, centered in the content band. */
    protected void renderEmpty(GuiGraphics graphics, String message) {
        graphics.drawCenteredString(this.font, "§7" + message,
                panelX + panelW / 2, contentTop() + UiTheme.PAD_M * 2, UiTheme.TEXT_MUTED);
    }

    private void renderChrome(GuiGraphics graphics) {
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, UiTheme.SURFACE_BG);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + UiTheme.HEADER_H, UiTheme.SURFACE_HEADER);
        graphics.drawCenteredString(this.font, this.title,
                panelX + panelW / 2, panelY + (UiTheme.HEADER_H - 8) / 2, UiTheme.TEXT);
        graphics.fill(panelX, panelY + UiTheme.HEADER_H, panelX + panelW,
                panelY + UiTheme.HEADER_H + 1, UiTheme.BORDER);
        int ft = footerTop();
        graphics.fill(panelX, ft, panelX + panelW, ft + 1, UiTheme.BORDER);
        if (status != null && System.currentTimeMillis() >= statusUntilMs) {
            status = baseStatus; // echo expired — restore the base status
            statusUntilMs = Long.MAX_VALUE;
            onStatusExpired();
        }
        if (status != null) {
            // Clip to the space left of the leftmost footer button — a long
            // status must never underlap the actions.
            int rightBound = contentRight();
            for (Button b : footerButtons) {
                rightBound = Math.min(rightBound, b.getX());
            }
            int avail = rightBound - UiTheme.PAD_S - contentLeft();
            int ellipsisW = this.font.width("…");
            String text = status.getString();
            if (avail > ellipsisW && this.font.width(text) > avail) {
                text = this.font.plainSubstrByWidth(text, avail - ellipsisW) + "…";
            } else if (avail <= ellipsisW) {
                text = "";
            }
            if (!text.isEmpty()) {
                graphics.drawString(this.font, text, contentLeft(),
                        ft + (UiTheme.FOOTER_H - 8) / 2 + 1, UiTheme.TEXT_MUTED);
            }
        }
        // border outline last so it sits above surface fills
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 1, UiTheme.BORDER);
        graphics.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, UiTheme.BORDER);
        graphics.fill(panelX, panelY, panelX + 1, panelY + panelH, UiTheme.BORDER);
        graphics.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, UiTheme.BORDER);
    }
}
