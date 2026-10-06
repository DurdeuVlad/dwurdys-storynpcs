package com.storynpcs.creator.gui;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;

/**
 * A versioned model/visual preset (P8-6): the server-authoritative schema for
 * the target Preset/ModelColor/GuiPresetSave surfaces — a named model reference
 * plus a bounded set of color/texture layers. Client rendering stays deferred
 * to P10-1; this definition is the canonical authored artifact.
 */
public class ModelPreset {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_LAYERS = 8;

    @JsonProperty
    private int schemaVersion = SCHEMA_VERSION;

    @JsonProperty(required = true)
    private NamespacedId id;

    /** Model identifier the preset binds (namespaced, e.g. an entity model ref). */
    @JsonProperty(required = true)
    private NamespacedId modelRef;

    @JsonProperty
    private String displayName = "";

    /** Color layers — index-ordered, each a hex color applied to a model layer. */
    @JsonProperty
    private java.util.List<ColorLayer> layers = new java.util.ArrayList<>();

    /** Optional texture overrides keyed by a namespaced texture reference. */
    @JsonProperty
    private java.util.List<NamespacedId> textureRefs = new java.util.ArrayList<>();

    public ModelPreset() {}

    /** One colored model layer: name + 24-bit RGB value. */
    public static class ColorLayer {
        @JsonProperty(required = true)
        private String name;
        @JsonProperty
        private int rgb; // 0x000000–0xFFFFFF

        public ColorLayer() {}
        public ColorLayer(String name, int rgb) { this.name = name; this.rgb = rgb; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getRgb() { return rgb; }
        public void setRgb(int rgb) { this.rgb = rgb; }
    }

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public NamespacedId getId() { return id; }
    public void setId(NamespacedId id) { this.id = id; }
    public NamespacedId getModelRef() { return modelRef; }
    public void setModelRef(NamespacedId modelRef) { this.modelRef = modelRef; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) {
        this.displayName = displayName == null ? "" : displayName;
    }
    public java.util.List<ColorLayer> getLayers() { return java.util.List.copyOf(layers); }
    public void setLayers(java.util.List<ColorLayer> layers) {
        this.layers = layers == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(layers);
    }
    public java.util.List<NamespacedId> getTextureRefs() { return java.util.List.copyOf(textureRefs); }
    public void setTextureRefs(java.util.List<NamespacedId> refs) {
        this.textureRefs = refs == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(refs);
    }

    /** Bounded-validation — layer cap, named layers, RGB range, texture count. */
    public ValidationResult validate() {
        var result = new ValidationResult();
        if (modelRef == null) {
            result.addError("PRESET_NO_MODEL", "preset has no modelRef");
        }
        if (layers.size() > MAX_LAYERS) {
            result.addError("PRESET_TOO_MANY_LAYERS",
                    layers.size() + " color layers exceed " + MAX_LAYERS);
        }
        if (textureRefs.size() > MAX_LAYERS) {
            result.addError("PRESET_TOO_MANY_TEXTURES",
                    textureRefs.size() + " texture refs exceed " + MAX_LAYERS);
        }
        var seen = new java.util.HashSet<String>();
        for (int i = 0; i < layers.size(); i++) {
            var layer = layers.get(i);
            if (layer.getName() == null || layer.getName().isBlank()) {
                result.addError("PRESET_UNNAMED_LAYER", "layer " + i + " has no name");
            } else if (!seen.add(layer.getName())) {
                result.addError("PRESET_DUPLICATE_LAYER",
                        "duplicate layer name '" + layer.getName() + "'");
            }
            if (layer.getRgb() < 0 || layer.getRgb() > 0xFFFFFF) {
                result.addError("PRESET_BAD_COLOR",
                        "layer '" + layer.getName() + "' rgb out of range");
            }
        }
        return result;
    }
}
