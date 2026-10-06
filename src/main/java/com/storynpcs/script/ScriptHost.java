package com.storynpcs.script;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import com.storynpcs.domain.script.ScriptDefinition;
import com.storynpcs.script.ScriptBudget.Meter;
import com.storynpcs.script.api.ScriptContext;
import com.storynpcs.script.api.ScriptHostApi;

/**
 * The Rhino-backed script sandbox (P9-2 / #84, engine pinned by #125).
 * Compiled scripts run in interpreted mode inside a per-script standard
 * scope, behind three independent containment layers:
 *
 * <ul>
 *   <li><b>Java access</b> — a {@code ClassShutter} admits only classes under
 *       {@code com.storynpcs.script.api}; every {@code JavaMembers} lookup on
 *       any other class (including {@code java.lang.Class},
 *       {@code ClassLoader}, reflection, filesystem, process) is rejected.
 *       Package roots and Java bridges are additionally deleted from each
 *       script's global scope, and E4X/XML is disabled.</li>
 *   <li><b>Execution budget</b> — the interpreter's instruction observer
 *       feeds the active {@link Meter}; exceeding the per-hook instruction
 *       budget throws {@link ScriptBudget.BudgetExceeded} mid-execution.
 *       Wall-clock time is charged by {@link ScriptScheduler} around the
 *       dispatch, so even meter-unaware blocking is bounded.</li>
 *   <li><b>Recursion</b> — the interpreter's maximum stack depth is capped at
 *       the budget's recursion bound, converting deep recursion into a
 *       catchable engine error instead of a VM stack overflow.</li>
 * </ul>
 *
 * <p>Each definition compiles against its own fresh standard scope — no
 * shared global object exists for one script to pollute another's
 * prototypes. Cache keys include the authored {@code version}, so a bumped
 * version recompiles cleanly while an unchanged reload reuses the compiled
 * unit.</p>
 */
public final class ScriptHost {

    /** Observer fires every this-many interpreter instructions. */
    static final int INSTRUCTION_GRANULARITY = 512;

    /**
     * Java-bridge globals deleted from every fresh script scope. With the
     * shutter rejecting non-API classes these are belt-and-suspenders —
     * defense in depth against an engine API change re-exposing them.
     */
    private static final List<String> STRIPPED_GLOBALS = List.of(
            "Packages", "java", "javax", "org", "com", "edu", "net",
            "JavaAdapter", "JavaImporter", "importPackage", "importClass",
            "getClass", "XML", "XMLList", "Namespace", "QName",
            "Continuation", "loadClass");

    private record Compiled(Script unit, ScriptableObject scope) {}

    private final ContextFactory factory = new SandboxedFactory();
    private final Map<String, Compiled> compiled = new ConcurrentHashMap<>();
    private final ThreadLocal<Meter> activeMeter = new ThreadLocal<>();

    /** Whether the named function exists and is callable on the script's scope. */
    public boolean implementsHook(ScriptDefinition definition, ScriptHook hook, Meter meter) {
        return resolve(definition, meter).scope().get(hook.jsName(), null) instanceof Function;
    }

    /**
     * Executes the script's top-level body (defining its hook functions)
     * under {@code meter}, then invokes the hook function if present.
     * Compilation + top-level execution are cached per id+version+source
     * hash; the first invocation pays the metered compile cost.
     */
    public void invoke(ScriptDefinition definition, ScriptHook hook,
                       ScriptHostApi api, ScriptContext context, Meter meter) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(hook, "hook");
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(meter, "meter");
        Compiled unit = resolve(definition, meter);
        Object fn = unit.scope().get(hook.jsName(), unit.scope());
        if (!(fn instanceof Function function)) {
            return;
        }
        activeMeter.set(meter);
        try {
            factory.call(cx -> {
                Scriptable scope = unit.scope();
                ScriptableObject.putProperty(scope, "storynpcs",
                        Context.javaToJS(api, scope));
                ScriptableObject.putProperty(scope, "context",
                        Context.javaToJS(context, scope));
                function.call(cx, scope, scope, Context.emptyArgs);
                return null;
            });
        } catch (org.mozilla.javascript.WrappedException wrapped) {
            // Java exceptions crossing the JS→Java boundary arrive wrapped —
            // unwrap so a BudgetExceeded from the meter or a service-level
            // failure reaches the scheduler as its real type.
            if (wrapped.getWrappedException() instanceof RuntimeException inner) {
                throw inner;
            }
            throw wrapped;
        } finally {
            activeMeter.remove();
        }
    }

    /**
     * Eager compile at registration: engine class-loading and script
     * compilation are load-time work, not per-hook spend — absorbing them
     * here keeps the first dispatch's wall-clock honest. Top-level source
     * still executes under a standard instruction meter, so a runaway
     * top-level loop is bounded. A failed precompile leaves the cache empty;
     * the next dispatch retries inside its own meter and fails normally.
     */
    public void precompile(ScriptDefinition definition) {
        try {
            resolve(definition, new Meter(ScriptBudget.standard()));
        } catch (RuntimeException deferredToDispatch) {
            // The dispatch path re-attempts compilation inside the hook's own
            // meter — a broken script fails there where it is accounted.
        }
    }

    /** Drop a compiled unit — e.g. when a definition is deleted or replaced. */
    public void evict(String namespacedId) {
        compiled.keySet().removeIf(k -> k.startsWith(namespacedId + "|"));
    }

    public void evictAll() {
        compiled.clear();
    }

    private Compiled resolve(ScriptDefinition definition, Meter meter) {
        String key = definition.getId() + "|" + definition.getVersion()
                + "|" + definition.getSource().hashCode();
        return compiled.computeIfAbsent(key, k -> compile(definition, meter));
    }

    private Compiled compile(ScriptDefinition definition, Meter meter) {
        activeMeter.set(meter);
        try {
            return factory.call(cx -> {
                ScriptableObject scope = cx.initStandardObjects();
                for (String global : STRIPPED_GLOBALS) {
                    ScriptableObject.deleteProperty(scope, global);
                }
                Script unit = cx.compileString(definition.getSource(),
                        definition.getId().toString(), 1, null);
                // Top-level statements (function declarations, one-time
                // setup) execute once, inside the registration meter — a
                // runaway top-level loop is budgeted like any hook.
                unit.exec(cx, scope);
                return new Compiled(unit, scope);
            });
        } catch (org.mozilla.javascript.WrappedException wrapped) {
            if (wrapped.getWrappedException() instanceof RuntimeException inner) {
                throw inner;
            }
            throw wrapped;
        } finally {
            activeMeter.remove();
        }
    }

    private final class SandboxedFactory extends ContextFactory {
        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setClassShutter(this::classVisibleToScripts);
            // Interpreted mode is REQUIRED — the instruction observer and
            // stack-depth cap do not exist in compiled/optimizer mode.
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(Context.VERSION_ES6);
            // Native String/Boolean/Number returns: without this, host-API
            // values come back as Java wrappers whose member access trips the
            // class shutter (e.g. context.playerUuid() → java.lang.String).
            cx.getWrapFactory().setJavaPrimitiveWrap(false);
            cx.setMaximumInterpreterStackDepth(
                    ScriptBudget.standard().maxRecursionDepth());
            cx.setInstructionObserverThreshold(INSTRUCTION_GRANULARITY);
            return cx;
        }

        private boolean classVisibleToScripts(String fullClassName) {
            return fullClassName != null
                    && fullClassName.startsWith("com.storynpcs.script.api.");
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            Meter meter = activeMeter.get();
            if (meter != null) {
                meter.instructions(instructionCount);
            }
        }

        @Override
        protected boolean hasFeature(Context cx, int featureIndex) {
            if (featureIndex == Context.FEATURE_E4X) {
                return false;
            }
            return super.hasFeature(cx, featureIndex);
        }
    }
}
