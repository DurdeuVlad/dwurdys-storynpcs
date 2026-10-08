package com.storynpcs.client.ui.widgets;

import com.storynpcs.client.ui.UiTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Generic scrolling viewport with a scrollbar (issue #199): clips content to
 * its bounds, maps the mouse wheel to {@link ScrollState}, and draws a
 * track + proportional thumb on the right edge when content overflows.
 *
 * <p>Content is supplied as a render callback receiving the viewport origin
 * and clip rectangle; the callback draws in *content coordinates* (y offset
 * by the scrolled amount) inside the scissor the region installs. {@code T}
 * is pixel-based: content height is measured in logical pixels.
 */
public class ScrollRegion extends AbstractWidget {

    private static final int SCROLLBAR_W = 4;
    private static final int TRACK = 0xFF14171E;
    private static final int THUMB = 0xFF3A4150;
    private static final int THUMB_ACTIVE = 0xFF525C6E;

    private final ScrollState scroll = new ScrollState();
    private ContentRenderer contentRenderer = (g, left, top, right, bottom) -> {};
    private int contentHeight;

    /** Draws content; (top - scrollPx) is the first visible content pixel row. */
    @FunctionalInterface
    public interface ContentRenderer {
        void render(GuiGraphics graphics, int left, int top, int right, int bottom);
    }

    public ScrollRegion(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty());
        this.scroll.setViewportSize(height);
    }

    public void setContentRenderer(ContentRenderer renderer) {
        this.contentRenderer = renderer == null ? (g, l, t, r, b) -> {} : renderer;
    }

    /** Total scrollable content height in logical pixels. */
    public void setContentHeight(int heightPx) {
        contentHeight = Math.max(0, heightPx);
        scroll.setContentSize(heightPx);
    }

    public ScrollState scroll() { return scroll; }
    public int scrollPx() { return scroll.offset(); }

    /** Right edge of the content area, leaving the scrollbar column clear. */
    public int contentRight() {
        return getX() + getWidth() - (scroll.scrollable() ? SCROLLBAR_W + 1 : 0);
    }

    @Override
    public void setHeight(int height) {
        super.setHeight(height);
        scroll.setViewportSize(height);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.enableScissor(getX(), getY(), contentRight(), getY() + getHeight());
        contentRenderer.render(graphics, getX(), getY() - scroll.offset(),
                contentRight(), getY() - scroll.offset() + contentHeight);
        graphics.disableScissor();
        renderScrollbar(graphics, mouseX, mouseY);
    }

    private void renderScrollbar(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!scroll.scrollable()) return;
        int trackX = getX() + getWidth() - SCROLLBAR_W;
        int trackY = getY();
        int trackH = getHeight();
        graphics.fill(trackX, trackY, trackX + SCROLLBAR_W, trackY + trackH, TRACK);
        int thumbH = Math.max(8, trackH * scroll.viewportSize() / Math.max(1, scroll.contentSize()));
        int travel = trackH - thumbH;
        int thumbY = trackY + (scroll.maxOffset() == 0 ? 0
                : travel * scroll.offset() / scroll.maxOffset());
        boolean hover = mouseX >= trackX && mouseX < trackX + SCROLLBAR_W
                && mouseY >= thumbY && mouseY < thumbY + thumbH;
        graphics.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH,
                hover ? THUMB_ACTIVE : THUMB);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isMouseOver(mouseX, mouseY) && scroll.wheel(scrollY * UiTheme.ROW_H)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Clicks on the scrollbar track page the viewport.
        if (button == 0 && scroll.scrollable()
                && mouseX >= getX() + getWidth() - SCROLLBAR_W && mouseX < getX() + getWidth()
                && mouseY >= getY() && mouseY < getY() + getHeight()) {
            int thumbH = Math.max(8, getHeight() * scroll.viewportSize() / Math.max(1, scroll.contentSize()));
            int thumbY = getY() + (scroll.maxOffset() == 0 ? 0
                    : (getHeight() - thumbH) * scroll.offset() / scroll.maxOffset());
            scroll.page(mouseY < thumbY ? -1 : 1);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
