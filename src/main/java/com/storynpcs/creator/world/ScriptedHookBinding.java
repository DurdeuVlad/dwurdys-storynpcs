package com.storynpcs.creator.world;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

/**
 * A validated, inert, typed hook binding (P8-3). Bindings are data — they name
 * a script hook and carry bounded parameters; the scripting host (P9) resolves
 * and executes them. This class never executes user code.
 */
public class ScriptedHookBinding {

    public static final int MAX_PARAMS = 16;

    @JsonProperty(required = true)
    private NamespacedId hookId;

    /** Event name that triggers the hook (e.g. "interact", "redstone_on"). */
    @JsonProperty(required = true)
    private String event;

    @JsonProperty
    private Map<String, String> parameters = new java.util.LinkedHashMap<>();

    public ScriptedHookBinding() {}

    public ScriptedHookBinding(NamespacedId hookId, String event) {
        this.hookId = hookId;
        this.event = event;
    }

    public NamespacedId getHookId() { return hookId; }
    public void setHookId(NamespacedId hookId) { this.hookId = hookId; }

    public String getEvent() { return event; }
    public void setEvent(String event) {
        if (event == null || event.isBlank() || event.length() > 64) {
            throw new IllegalArgumentException("event must be 1-64 chars");
        }
        this.event = event;
    }

    public Map<String, String> getParameters() { return Map.copyOf(parameters); }
    public void setParameters(Map<String, String> parameters) {
        if (parameters != null && parameters.size() > MAX_PARAMS) {
            throw new IllegalArgumentException("parameters cannot exceed " + MAX_PARAMS);
        }
        this.parameters = parameters == null ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(parameters);
    }
}
