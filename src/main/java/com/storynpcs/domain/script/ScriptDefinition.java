package com.storynpcs.domain.script;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

/**
 * An authored server-side script (P9-2): ECMAScript source, the hooks it
 * implements, and the canonical capabilities it is granted. Scripts are
 * content definitions — versioned, YAML-first, read-only at runtime — while
 * the code they run stays sandboxed behind {@code ScriptHost}.
 */
public class ScriptDefinition {

    public static final int SCHEMA_VERSION = 1;
    /** Authored source size bound — a definition file is not a codebase dump. */
    public static final int MAX_SOURCE_BYTES = 65_536;
    public static final int MAX_HOOKS = 32;
    public static final int MAX_CAPABILITIES = 64;

    @JsonProperty
    private int schemaVersion = SCHEMA_VERSION;

    @JsonProperty(required = true)
    private NamespacedId id;

    /** Script language — only "javascript" (Rhino ECMAScript) is executed. */
    @JsonProperty
    private String language = "javascript";

    /** ECMAScript source — bounded. */
    @JsonProperty
    private String source = "";

    /** Hook names the script implements (init, tick, interact, ...). */
    @JsonProperty
    private List<String> hooks = new ArrayList<>();

    /**
     * Canonical capabilities the script may invoke (e.g. "faction.progress.set").
     * Validated against the capability registry at load; grants never widen
     * beyond PLAYER_SCOPED policies — definition writes stay unreachable.
     */
    @JsonProperty
    private List<String> capabilities = new ArrayList<>();

    /** Disabled scripts load but never register with the runtime. */
    @JsonProperty
    private boolean enabled = true;

    /** Author-facing version note; bump signals a reload-meaningful change. */
    @JsonProperty
    private String version = "1";

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public NamespacedId getId() { return id; }
    public void setId(NamespacedId id) { this.id = id; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public List<String> getHooks() { return hooks; }
    public void setHooks(List<String> hooks) {
        this.hooks = new ArrayList<>();
        if (hooks == null) return;
        if (hooks.size() > MAX_HOOKS) throw new IllegalArgumentException("hooks cannot exceed " + MAX_HOOKS);
        for (String hook : hooks) {
            if (hook == null || hook.isBlank()) throw new IllegalArgumentException("hooks cannot contain blanks");
            // Canonical form is the lowercase JS function name — the loader
            // accepts authored names case-insensitively; dispatch matches
            // exactly, so anything stored must already be normalized.
            this.hooks.add(hook.trim().toLowerCase(java.util.Locale.ROOT));
        }
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public List<String> getCapabilities() { return capabilities; }
    public void setCapabilities(List<String> capabilities) {
        this.capabilities = new ArrayList<>();
        if (capabilities == null) return;
        if (capabilities.size() > MAX_CAPABILITIES) {
            throw new IllegalArgumentException("capabilities cannot exceed " + MAX_CAPABILITIES);
        }
        for (String capability : capabilities) {
            if (capability == null || capability.isBlank()) {
                throw new IllegalArgumentException("capabilities cannot contain blanks");
            }
            this.capabilities.add(capability.trim().toLowerCase(java.util.Locale.ROOT));
        }
    }
}
