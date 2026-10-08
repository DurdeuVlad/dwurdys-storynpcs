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
    private final SelectionModel<K> selection;
    private final ScrollState scroll;
    private List<T> rows = List.of();
    private List<K> keys = List.of();
    private Consumer<T> onSelect;
    private Consumer<T> onActivate;
    private Function<T, Component> detailOf;

    public SelectableList(int x, int y, int width, int height, int rowHeight,
                          Function<T, K> keyOf, Function<T, Component> labelOf) {
        this(x, y, width, height, rowHeight, keyOf, labelOf,
                new SelectionModel<>(), new ScrollState());
    }

    /**
     * State-injecting variant: pass shared {@link SelectionModel}/
     * {@link ScrollState} instances so selection and scroll position survive
     * widget rebuilds (window resize, mode toggles) — the screen owns them.
     */
    public SelectableList(int x, int y, int width, int height, int rowHeight,
                          Function<T, K> keyOf, Function<T, Component> labelOf,
                          SelectionModel<K> selection, ScrollState scroll) {
        super(x, y, width, height, Component.empty());
        this.rowHeight = Math.max(8, rowHeight);
        this.keyOf = keyOf;
        this.labelOf = labelOf;
        this.selection = selection;
        this.scroll = scroll;
        this.scroll.setViewportSize(visibleRows());
    }

    /** Fires once per user-initiated selection change (click or key). */
    public void setOnSelect(Consumer<T> onSelect) { this.onSelect = onSelect; }

    /**
     * Fires on explicit activation: clicking the already-selected row, or
     * Enter/Space while a row is selected. Distinct from {@link #setOnSelect}
     * so arrow-key navigation never triggers commits.
     */
    public void setOnActivate(Consumer<T> onActivate) { this.onActivate = onActivate; }

    /**
     * Optional second text line per row, drawn muted under the label —
     * for rows taller than ~18px (companion stats, recipe ingredients).
     */
    public void setDetailRenderer(Function<T, Component> detailOf) {
        this.detailOf = detailOf;
    }

    public SelectionModel<K> selection() { return selection; }
    public ScrollState scroll() { return scroll; }

    /**
     * Swap in fresh data; the selected key survives if it still exists and is
     * scrolled back into view. Malformed rows degrade instead of crashing:
     * null rows are dropped, and rows with a null or duplicate key render but
     * cannot be selected (keys must be unique for identity tracking).
     */
    public void setRows(List<T> newRows) {
        rows = newRows == null ? List.of() : newRows.stream().filter(java.util.Objects::nonNull).toList();
        // Null or duplicated keys mark their row unselectable rather than
        // failing — a malformed view payload must never crash the client.
        List<K> ks = new java.util.ArrayList<>(rows.size());
        java.util.Set<K> seen = new java.util.HashSet<>();
        for (T row : rows) {
            K k = keyOf.apply(row);
            ks.add(k == null || !seen.add(k) ? null : k);
        }
        keys = ks;
        selection.setItems(keys);
        scroll.setContentSize(rows.size());
        ensureSelectedVisible();
    }

    public T selectedRow() {
        K key = selection.selected();
        if (key == null) return null;
        for (int i = 0; i < rows.size(); i++) {
            if (java.util.Objects.equals(key, keys.get(i))) return rows.get(i);
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

    /** Right edge of the clickable row area — excludes the scrollbar track. */
    private int rowsRight() {
        return getX() + getWidth() - (scroll.scrollable() ? 4 : 0);
    }

    @Override
    public void setHeight(int height) {
        super.setHeight(height);
        scroll.setViewportSize(visibleRows());
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int bandBottom = getY() + visibleRows() * rowHeight;
        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        graphics.enableScissor(getX(), getY(), getX() + getWidth(), bandBottom);
        for (int i = 0; i < visibleRows() && i + scroll.offset() < rows.size(); i++) {
            int idx = scroll.offset() + i;
            T row = rows.get(idx);
            int ry = getY() + i * rowHeight;
            boolean selected = java.util.Objects.equals(keys.get(idx), selection.selected());
            boolean hover = isMouseOver(mouseX, mouseY)
                    && mouseX >= getX() && mouseX < rowsRight()
                    && mouseY >= ry && mouseY < ry + rowHeight;
            if (selected) {
                graphics.fill(getX(), ry, getX() + getWidth(), ry + rowHeight, UiTheme.ROW_SELECTED);
            } else if (hover) {
                graphics.fill(getX(), ry, getX() + getWidth(), ry + rowHeight, UiTheme.ROW_HOVER);
            }
            Component label = labelOf.apply(row);
            Component detail = detailOf != null && rowHeight >= 20 ? detailOf.apply(row) : null;
            int labelY = detail != null ? ry + 2 : ry + (rowHeight - 8) / 2;
            graphics.drawString(font, label == null ? Component.empty() : label,
                    getX() + UiTheme.PAD_S, labelY,
                    selected ? UiTheme.ACCENT : UiTheme.TEXT);
            if (detail != null) {
                graphics.drawString(font, detail,
                        getX() + UiTheme.PAD_S, ry + rowHeight - 10, UiTheme.TEXT_MUTED);
            }
        }
        graphics.disableScissor();
        // Scroll indicator: thin track + thumb when content overflows — the
        // scrollbar affordance the hand-rolled panels added with ^/v buttons.
        if (scroll.scrollable()) {
            int trackX = getX() + getWidth() - 3;
            int trackY = getY();
            int trackH = bandBottom - trackY;
            graphics.fill(trackX, trackY, trackX + 2, trackY + trackH, UiTheme.FIELD_BG);
            int thumbH = Math.max(8, trackH * scroll.viewportSize() / Math.max(1, scroll.contentSize()));
            int range = Math.max(1, trackH - thumbH);
            int thumbY = trackY + range * scroll.offset()
                    / Math.max(1, scroll.contentSize() - scroll.viewportSize());
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, UiTheme.ACCENT);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (active && visible && button == 0
                && mouseX >= getX() && mouseX < getX() + getWidth()
                && mouseY >= getY() && mouseY < getY() + getHeight()) {
            // Inside the widget: row band selects/activates; the dead strip
            // below the last full row and the scrollbar track are consumed
            // silently — a click there must not sound like it did something.
            if (mouseY >= getY() + visibleRows() * rowHeight || mouseX >= rowsRight()) {
                return true;
            }
            int index = scroll.offset() + (int) ((mouseY - getY()) / rowHeight);
            if (index == selection.selectedIndex() && onActivate != null
                    && index >= 0 && index < rows.size()) {
                onActivate.accept(rows.get(index));
            } else {
                applySelectIndex(index);
            }
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
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                    || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER
                    || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) {
                // Activation key: fire if wired and a row is selected;
                // otherwise consume silently — falling through to
                // AbstractWidget would play a button-click sound for a no-op.
                T row = onActivate != null ? selectedRow() : null;
                if (row != null) onActivate.accept(row);
                return true;
            }
            boolean moved = switch (keyCode) {
                case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> selection.move(1);
                case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> selection.move(-1);
                case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP -> selection.move(-visibleRows());
                case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN -> selection.move(visibleRows());
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
