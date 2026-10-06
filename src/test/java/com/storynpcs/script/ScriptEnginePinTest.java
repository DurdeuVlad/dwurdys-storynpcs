package com.storynpcs.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import javax.script.ScriptEngineManager;

import org.junit.jupiter.api.Test;

/** #125: the pinned script engine must resolve — Rhino via JSR-223. */
class ScriptEnginePinTest {

    @Test
    void pinnedEngineResolvesThroughJsr223() {
        // Boot smoke test — the same check the mod constructor runs.
        assertThatCode(ScriptEnginePin::assertAvailable).doesNotThrowAnyException();
    }

    @Test
    void pinnedFactoryIsRhinoNotNashorn() {
        var engine = ScriptEnginePin.pinnedEngine();
        assertThat(engine).as("pinned engine must resolve").isNotNull();
        assertThat(engine.getFactory().getEngineName().toLowerCase(java.util.Locale.ROOT))
                .as("the pinned engine must be Rhino")
                .contains("rhino");
        // Nashorn arrives via NeoForge's library set (research/125 F3) and
        // shadows '.js' extension resolution — the pin exists precisely because
        // getEngineByExtension is non-deterministic on this classpath.
        assertThat(engine.getClass().getName().toLowerCase(java.util.Locale.ROOT))
                .contains("rhino");
    }

    @Test
    void engineEvaluatesTargetDialectJavascript() throws Exception {
        var engine = ScriptEnginePin.pinnedEngine();
        // Target scripts are ECMAScript — basic dialect conformance.
        assertThat(engine.eval("var x = 6 * 7; x;").toString()).contains("42");
    }
}
