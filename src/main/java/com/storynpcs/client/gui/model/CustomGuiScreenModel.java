package com.storynpcs.client.gui.model;

import com.storynpcs.creator.gui.CustomGuiLayout;
import com.storynpcs.creator.gui.CustomGuiLayout.GuiElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Headless render model for the authored-custom-GUI runtime screen
 * (issue #150, GuiCustom* parity). Flattens the layout's element tree into
 * absolutely-positioned render entries, resolves button hit tests, and
 * collects input-element values for commit payloads.
 *
 * <p>Coordinate convention: a child's {@code x}/{@code y} are relative to its
 * parent element's origin; the root's own {@code x}/{@code y} offset the whole
 * layout. The model reports layout-space coordinates — the screen adds the
 * centering offset so the model itself stays resolution-independent
 * (unit-testable without Minecraft classes).
 *
 * <p>Unsupported authored element types ({@code scroll}, {@code item_slot},
 * {@code entity_display}) are still flattened and rendered as labelled
 * placeholders — they are never silently dropped — but only {@code button},
 * {@code input}, {@code label}, {@code texture}, and {@code panel} have
 * interactive/committed behavior.
 */
public final class CustomGuiScreenModel {

    /** One flattened element with absolute layout-space bounds. */
    public record RenderEntry(String path, String name, String type,
                              int absX, int absY, int width, int height,
                              String textKey, String textureRef,
                              int depth, boolean supported) {}

    private final CustomGuiLayout layout;
    private final List<RenderEntry> entries;
    private final Map<String, String> inputValues = new LinkedHashMap<>();

    public CustomGuiScreenModel(CustomGuiLayout layout) {
        this.layout = layout;
        this.entries = new ArrayList<>();
        if (layout != null && layout.getRoot() != null) {
            flatten(layout.getRoot(), 0, 0, 0, "0");
        }
    }

    /** Path = child-index chain from the root, e.g. "0/2/1" — stable per layout. */
    private void flatten(GuiElement el, int parentX, int parentY, int depth,
                         String path) {
        int absX = parentX + el.getX();
        int absY = parentY + el.getY();
        entries.add(new RenderEntry(
                path, el.getName(), el.getType(), absX, absY,
                el.getWidth(), el.getHeight(), el.getTextKey(),
                el.getTextureRef() == null ? null : el.getTextureRef().toString(),
                depth, isInteractiveSupported(el.getType())));
        if ("input".equals(el.getType())) {
            inputValues.putIfAbsent(path, "");
        }
        List<GuiElement> children = el.getChildren();
        for (int i = 0; i < children.size(); i++) {
            flatten(children.get(i), absX, absY, depth + 1, path + "/" + i);
        }
    }

    private static boolean isInteractiveSupported(String type) {
        return switch (type == null ? "" : type) {
            case "panel", "button", "label", "texture", "input" -> true;
            default -> false;
        };
    }

    /** All flattened entries in document order (parents before children). */
    public List<RenderEntry> entries() {
        return List.copyOf(entries);
    }

    public List<RenderEntry> buttons() {
        return entries.stream().filter(e -> "button".equals(e.type())).toList();
    }

    public List<RenderEntry> inputs() {
        return entries.stream().filter(e -> "input".equals(e.type())).toList();
    }

    /**
     * Layout-space hit test against button elements. Returns the hit entry's
     * stable path, or {@code null} when no button covers the point.
     */
    public String buttonAt(int layoutX, int layoutY) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            RenderEntry e = entries.get(i);
            if (!"button".equals(e.type())) {
                continue;
            }
            if (layoutX >= e.absX() && layoutX < e.absX() + Math.max(1, e.width())
                    && layoutY >= e.absY() && layoutY < e.absY() + Math.max(1, e.height())) {
                return e.path();
            }
        }
        return null;
    }

    /** Set a collected input value by element path; unknown paths are ignored. */
    public void setInputValue(String path, String value) {
        if (inputValues.containsKey(path)) {
            inputValues.put(path, value == null ? "" : value);
        }
    }

    /** Current input values keyed by element path — the commit payload body. */
    public Map<String, String> inputValues() {
        return Map.copyOf(inputValues);
    }

    /** Extent of the rendered layout (max child edge) for centering math. */
    public int[] extent() {
        int maxX = 0;
        int maxY = 0;
        for (RenderEntry e : entries) {
            maxX = Math.max(maxX, e.absX() + e.width());
            maxY = Math.max(maxY, e.absY() + e.height());
        }
        return new int[]{maxX, maxY};
    }

    public CustomGuiLayout layout() {
        return layout;
    }
}
