package com.storynpcs.client.ui.widgets;

import com.storynpcs.client.ui.UiTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Scrollable single-select row list (issue #199): real selection highlight +
 * hover fill (fixes the audit's F3 "only a `> ` marker" affordance), keyboard
 * up/down + PageUp/PageDown, mouse wheel, and key-stable selection via
 * {@link SelectionModel} so a data refresh doesn't silently drop the user's
 * place.
 *
 * <p>State lives in {@link #selection} and {@link #scroll} — both pure classes
 * that are unit-tested headless; this widget only maps them to pixels.
 *
 * @param <T> row payload
 * @param <K> stable row key — drives selection persistence across refreshes
 */
public class SelectableList<T, K> extends AbstractWidget {

    private final Function<T, K> keyOf;
    private final Function<T, Component> labelOf;
    private final int rowHeight;
    private final SelectionModel<K> selection = new SelectionModel<>();
    private final ScrollState scroll = new ScrollState();
    private List<T> rows = List.of();
    private Consumer<T> onSelect;

    public SelectableList(int x, int y, int width, int height, int rowHeight,
                          Function<T, K> keyOf, Function<T, Component> labelOf) {
        super(x, y, width, height, Component.empty());
        this.rowHeight = Math.max(8, rowHeight);
        this.keyOf = keyOf;
        this.labelOf = labelOf;
        this.scroll.setViewportSize(visibleRows());
    }

    /** Fires once per user-initiated selection change (click or key). */
    public void setOnSelect(Consumer<T> onSelect) { this.onSelect = onSelect; }

    public SelectionModel<K> selection() { return selection; }
    public ScrollState scroll() { return scroll; }

    /** Swap in fresh data; the selected key survives if it still exists. */
    public void setRows(List<T> newRows) {
        rows = newRows == null ? List.of() : List.copyOf(newRows);
        selection.setItems(rows.stream().map(keyOf).toList());
        scroll.setContentSize(rows.size());
    }

    public T selectedRow() {
        K key = selection.selected();
        if (key == null) return null;
        for (T row : rows) {
            if (keyOf.apply(row).equals(key)) return row;
        }
        return null;
    }

    private int visibleRows() { return getHeight() / rowHeight; }

    private void ensureSelectedVisible() {
        int idx = selection.selectedIndex();
        if (idx < 0) return;
        if (idx < scroll.offset()) {
            scroll.scrollTo(idx);
        } else if (idx >= scroll.offset() + visibleRows()) {
            scroll.scrollTo(idx - visibleRows() + 1);
        }
    }

    private void applySelectIndex(int index) {
        if (index >= 0 && index < rows.size() && selection.selectIndex(index)) {
            ensureSelectedVisible();
            if (onSelect != null) onSelect.accept(rows.get(index));
        }
    }

    @Override
    public void setHeight(int height) {
        super.setHeight(height);
        scroll.setViewportSize(visibleRows());
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int bandBottom = getY() + visibleRows() * rowHeight;
        graphics.enableScissor(getX(), getY(), getX() + getWidth(), bandBottom);
        for (int i = 0; i < visibleRows() && i + scroll.offset() < rows.size(); i++) {
            int idx = scroll.offset() + i;
            T row = rows.get(idx);
            int ry = getY() + i * rowHeight;
            boolean selected = keyOf.apply(row).equals(selection.selected());
            boolean hover = isMouseOver(mouseX, mouseY)
                    && mouseX >= getX() && mouseX < getX() + getWidth()
                    && mouseY >= ry && mouseY < ry + rowHeight;
            if (selected) {
                graphics.fill(getX(), ry, getX() + getWidth(), ry + rowHeight, UiTheme.ROW_SELECTED);
            } else if (hover) {
                graphics.fill(getX(), ry, getX() + getWidth(), ry + rowHeight, UiTheme.ROW_HOVER);
            }
            Component label = labelOf.apply(row);
            graphics.drawString(getFont(), label == null ? Component.empty() : label,
                    getX() + UiTheme.PAD_S, ry + (rowHeight - 8) / 2,
                    selected ? UiTheme.ACCENT : UiTheme.TEXT);
        }
        graphics.disableScissor();
    }

    private net.minecraft.client.gui.Font getFont() {
        return net.minecraft.client.Minecraft.getInstance().font;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (active && visible && button == 0
                && mouseX >= getX() && mouseX < getX() + getWidth()
                && mouseY >= getY() && mouseY < getY() + visibleRows() * rowHeight) {
            applySelectIndex(scroll.offset() + (int) ((mouseY - getY()) / rowHeight));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isMouseOver(mouseX, mouseY)) {
            return scroll.wheel(scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isFocused()) {
            boolean moved = switch (keyCode) {
                case 264 -> selection.move(1);          // down
                case 265 -> selection.move(-1);         // up
                case 266 -> selection.move(-visibleRows()); // page up
                case 267 -> selection.move(visibleRows());  // page down
                default -> false;
            };
            if (moved) {
                ensureSelectedVisible();
                T row = selectedRow();
                if (onSelect != null && row != null) onSelect.accept(row);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        T row = selectedRow();
        if (row != null) output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, labelOf.apply(row));
    }
}
