package com.storynpcs.script;

import javax.script.ScriptEngineManager;

/**
 * P9-2/#125: the pinned script engine contract (ADR-008).
 *
 * The target runs JSR-223 ECMAScript on Nashorn (research/125 F3,
 * VERIFIED_TARGET_RUNTIME: {@code ECMAScript: .js}). Per ADR-007's non-Nashorn
 * direction, StoryNPCs pins <b>Rhino</b> (org.mozilla:rhino + rhino-engine,
 * v1.9.1) as an explicit artifact — never a loader-provided library. Rhino is
 * the dialect-nearest maintained engine, and its interpreted-mode instruction
 * observers ({@code ContextFactory.observeInstructionCount}) supply the
 * deterministic instruction counters P9-2's quotas require.
 *
 * Sandbox policy (P9-2) wraps the engine — isolation never relies on engine
 * internals: Rhino's ClassShutter gates Java access, instruction observers
 * bound execution, and the scheduler owns wall-clock budgets.
 */
public final class ScriptEnginePin {

    /** Pinned engine + version — the single source of truth for #125. */
    public static final String ENGINE_ID = "rhino";
    public static final String ENGINE_VERSION = "1.9.1";
    /** Target dialect recorded for parity reporting — a deviation, not parity. */
    public static final String TARGET_DIALECT = "ECMAScript (.js) via Nashorn JSR-223";

    private ScriptEnginePin() {}

    /**
     * Boot-time contract: the pinned Rhino engine must resolve. Extension
     * lookup ({@code .js}) is NOT deterministic — Nashorn 15.4 arrives on the
     * classpath via NeoForge's library set (research/125 F3) and shadows
     * extension/name resolution, so the pin enumerates factories and picks
     * Rhino explicitly.
     */
    public static javax.script.ScriptEngine pinnedEngine() {
        for (var factory : new ScriptEngineManager().getEngineFactories()) {
            if (factory.getEngineName().toLowerCase(java.util.Locale.ROOT).contains(ENGINE_ID)) {
                return factory.getScriptEngine();
            }
        }
        return null;
    }

    /** Fails loudly when the pinned engine artifact is absent or stripped. */
    public static void assertAvailable() {
        var engine = pinnedEngine();
        if (engine == null) {
            throw new IllegalStateException(
                    "Pinned script engine unavailable: no Rhino JSR-223 factory on the classpath. "
                            + "Expected Rhino " + ENGINE_VERSION
                            + " (org.mozilla:rhino + rhino-engine) — check build.gradle "
                            + "packaging (implementation + additionalRuntimeClasspath + jarJar). "
                            + "Note: Nashorn may still shadow '.js' resolution; callers must "
                            + "obtain the engine via ScriptEnginePin.pinnedEngine().");
        }
    }
}
