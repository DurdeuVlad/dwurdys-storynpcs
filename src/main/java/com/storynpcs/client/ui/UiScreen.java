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
 */
public abstract class UiScreen extends Screen {

    private final List<FooterAction> footerActions = new ArrayList<>();
    private Component status;
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
        status = null;
        initContent();
        layoutFooter();
    }

    /** Add content widgets and footer actions; panel metrics are already set. */
    protected void initContent() {}

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

    /** Status line rendered at the left of the footer; null clears it. */
    protected void setStatus(Component status) {
        this.status = status;
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
            addRenderableWidget(Button.builder(action.label(), action.onPress())
                    .bounds(x, by, w, UiTheme.BUTTON_H).build());
            x -= UiTheme.PAD_S;
        }
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

    private void renderChrome(GuiGraphics graphics) {
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, UiTheme.SURFACE_BG);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + UiTheme.HEADER_H, UiTheme.SURFACE_HEADER);
        graphics.drawCenteredString(this.font, this.title,
                panelX + panelW / 2, panelY + (UiTheme.HEADER_H - 8) / 2, UiTheme.TEXT);
        graphics.fill(panelX, panelY + UiTheme.HEADER_H, panelX + panelW,
                panelY + UiTheme.HEADER_H + 1, UiTheme.BORDER);
        int ft = footerTop();
        graphics.fill(panelX, ft, panelX + panelW, ft + 1, UiTheme.BORDER);
        if (status != null) {
            graphics.drawString(this.font, status, contentLeft(),
                    ft + (UiTheme.FOOTER_H - 8) / 2 + 1, UiTheme.TEXT_MUTED);
        }
        // border outline last so it sits above surface fills
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 1, UiTheme.BORDER);
        graphics.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, UiTheme.BORDER);
        graphics.fill(panelX, panelY, panelX + 1, panelY + panelH, UiTheme.BORDER);
        graphics.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, UiTheme.BORDER);
    }
}
