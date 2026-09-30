package com.storynpcs.creator.gui;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

/**
 * Custom GUI layout tree (P8-6): bounded depth, fan-out, and element count.
 * Oversized/deep/invalid trees reject at load with element-path diagnostics.
 */
public class CustomGuiLayout {

    public static final int MAX_DEPTH = 6;
    public static final int MAX_ELEMENTS = 64;
    public static final int MAX_CHILDREN = 8;

    @JsonProperty(required = true)
    private NamespacedId id;

    @JsonProperty
    private GuiElement root;

    public CustomGuiLayout() {}

    public NamespacedId getId() { return id; }
    public void setId(NamespacedId id) { this.id = id; }

    public GuiElement getRoot() { return root; }
    public void setRoot(GuiElement root) { this.root = root; }

    /** A layout element: named, positioned, optionally containing children. */
    public static class GuiElement {
        @JsonProperty(required = true)
        private String name;
        @JsonProperty
        private String type = "panel"; // panel|button|label|texture|input
        @JsonProperty
        private int x;
        @JsonProperty
        private int y;
        @JsonProperty
        private int width;
        @JsonProperty
        private int height;
        @JsonProperty
        private NamespacedId textureRef;
        @JsonProperty
        private List<GuiElement> children = new java.util.ArrayList<>();

        public GuiElement() {}
        public GuiElement(String name, String type) { this.name = name; this.type = type; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public int getX() { return x; }
        public void setX(int x) { this.x = x; }
        public int getY() { return y; }
        public void setY(int y) { this.y = y; }
        public int getWidth() { return width; }
        public void setWidth(int width) { this.width = width; }
        public int getHeight() { return height; }
        public void setHeight(int height) { this.height = height; }
        public NamespacedId getTextureRef() { return textureRef; }
        public void setTextureRef(NamespacedId textureRef) { this.textureRef = textureRef; }
        public List<GuiElement> getChildren() { return List.copyOf(children); }
        public void setChildren(List<GuiElement> children) {
            this.children = children == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(children);
        }
    }

    /** Bounded-tree validation — every error names the element path. */
    public com.storynpcs.domain.common.ValidationResult validate() {
        var result = new com.storynpcs.domain.common.ValidationResult();
        if (root == null) {
            result.addError("GUI_NO_ROOT", "layout has no root element");
            return result;
        }
        int[] count = {0};
        validateElement(root, "root", 1, result, count);
        if (count[0] > MAX_ELEMENTS) {
            result.addError("GUI_TOO_MANY_ELEMENTS",
                    count[0] + " elements exceed " + MAX_ELEMENTS);
        }
        return result;
    }

    private static void validateElement(GuiElement el, String path, int depth,
                                        com.storynpcs.domain.common.ValidationResult result, int[] count) {
        count[0]++;
        if (depth > MAX_DEPTH) {
            result.addError("GUI_TOO_DEEP", "element '" + path + "' exceeds depth " + MAX_DEPTH);
            return;
        }
        if (el.getName() == null || el.getName().isBlank()) {
            result.addError("GUI_UNNAMED_ELEMENT", "element '" + path + "' has no name");
        }
        if (el.getChildren().size() > MAX_CHILDREN) {
            result.addError("GUI_TOO_MANY_CHILDREN",
                    "element '" + path + "' has " + el.getChildren().size() + " children (max " + MAX_CHILDREN + ")");
        }
        if (el.getWidth() < 0 || el.getHeight() < 0 || el.getWidth() > 512 || el.getHeight() > 512) {
            result.addError("GUI_BAD_SIZE", "element '" + path + "' has invalid size "
                    + el.getWidth() + "x" + el.getHeight());
        }
        for (int i = 0; i < el.getChildren().size(); i++) {
            validateElement(el.getChildren().get(i), path + "." + (el.getChildren().get(i).getName() == null
                    ? "child" + i : el.getChildren().get(i).getName()), depth + 1, result, count);
        }
    }
}
