package com.storynpcs.domain.command;

import java.util.List;

/**
 * Script-host parity matrix (issue #84 — P9-2). Every target hook named by
 * the acceptance contract — {@code init}, {@code tick}, {@code interact},
 * {@code damaged}, {@code killed}, {@code target}, {@code dialog},
 * {@code quest}, {@code timer} — maps to its StoryNPCs event/context seam
 * with an explicit evidence state. Engine, sandbox, and budget surfaces are
 * mapped the same way so the security posture is auditable in one place.
 *
 * <p>Evidence states reuse {@link CommandParityStatus}:
 * {@code SUPPORTED} = implemented + tested; {@code INTENTIONAL_DEVIATION} =
 * a documented, bounded difference from the target semantic.</p>
 */
public final class ScriptHookMatrix {

    public record Entry(String id, String targetSurface, String storyNpcsEquivalent,
                        CommandParityStatus status, String evidence) {}

    private static final List<Entry> ENTRIES = List.of(
            // ── The nine acceptance hooks ────────────────────────────────────
            new Entry("hook.init", "IScriptHandler init",
                    "ScriptRuntime.register → INIT on first live entity tick",
                    CommandParityStatus.SUPPORTED,
                    "Entity-bound scripts init on first aiStep; registration-time init for global dispatch paths. Metered standard-budget dispatch."),
            new Entry("hook.tick", "IScriptHandler tick",
                    "StoryNpcEntity.aiStep → TICK via dispatchFor",
                    CommandParityStatus.SUPPORTED,
                    "Per-tick per-NPC dispatch to bound scripts only; 1ms hook / 4ms aggregate windows + 20k-instruction meter."),
            new Entry("hook.interact", "IScriptHandler interact",
                    "StoryNpcEntity.mobInteract (server) → INTERACT",
                    CommandParityStatus.SUPPORTED,
                    "Fires before dialogue/follower flow; interacting player rides context.playerUuid."),
            new Entry("hook.damaged", "IScriptHandler damaged",
                    "StoryNpcEntity.hurt (server) → DAMAGED",
                    CommandParityStatus.SUPPORTED,
                    "Fires before threat/ability outcomes — pre-mitigation observation; attacker uuid exposed when a player."),
            new Entry("hook.killed", "IScriptHandler killed",
                    "StoryNpcEntity.die (server) → KILLED",
                    CommandParityStatus.SUPPORTED,
                    "Fires before DefeatResolution dispatch — observable for DIE/HIDE/FLEE modes alike."),
            new Entry("hook.target", "IScriptHandler target",
                    "StoryNpcEntity.publishAggroChange → TARGET",
                    CommandParityStatus.SUPPORTED,
                    "Dispatches on aggro acquisition transitions (isAggro=true); de-aggro stays event-only, disclosed."),
            new Entry("hook.dialog", "IScriptHandler dialog",
                    "DialogueOpenEvent / DialogueOptionSelectEvent → DIALOG (global)",
                    CommandParityStatus.SUPPORTED,
                    "Global dispatch to scripts declaring 'dialog'; dialogueId + playerUuid ride the context."),
            new Entry("hook.quest", "IScriptHandler quest",
                    "QuestStartEvent / QuestCompleteEvent → QUEST (global)",
                    CommandParityStatus.SUPPORTED,
                    "Global dispatch on quest lifecycle events; questId + playerUuid ride the context."),
            new Entry("hook.timer", "IScriptHandler timer",
                    "storynpcs.startTimer → ScriptRuntime.tick → TIMER",
                    CommandParityStatus.SUPPORTED,
                    "Named per-script timers (≤8/script), in-memory scheduling, fired on the overworld clock inside the aggregate budget."),

            // ── Engine + sandbox surfaces ────────────────────────────────────
            new Entry("engine.pin", "Nashorn ECMAScript (target runtime)",
                    "Rhino 1.9.1 interpreted ES6 (#125/ADR-008)",
                    CommandParityStatus.SUPPORTED,
                    "Pinned artifact resolved by factory at boot — Nashorn transitively present but never selected."),
            new Entry("sandbox.java_access", "unrestricted java.lang access",
                    "ClassShutter: com.storynpcs.script.api.* only",
                    CommandParityStatus.INTENTIONAL_DEVIATION,
                    "Every JavaMembers lookup outside the API package is denied — Class/ClassLoader/reflection/filesystem/process unreachable. Target allows broad Java access; StoryNPCs does not, disclosed."),
            new Entry("sandbox.globals", "global scope with Java bridges",
                    "Per-script fresh scope, bridges stripped",
                    CommandParityStatus.SUPPORTED,
                    "Packages/java/org/com/edu/net/JavaAdapter/importClass/getClass/XML/Continuation removed per scope; no shared globals between scripts."),
            new Entry("sandbox.reflection", "arbitrary reflection",
                    "denied — no reflective surface exposed",
                    CommandParityStatus.SUPPORTED,
                    "getClass() results are unreachable classes under the shutter; no Unsafe/MethodHandles exposure."),
            new Entry("sandbox.filesystem", "filesystem access",
                    "denied — no IO classes reachable",
                    CommandParityStatus.SUPPORTED,
                    "java.io/nio/files invisible under the shutter; scripts cannot read or write files."),
            new Entry("sandbox.commands", "OP command execution",
                    "denied — no command dispatcher surface",
                    CommandParityStatus.SUPPORTED,
                    "Scripts reach only ScriptHostApi methods; every mutation is a grant-checked canonical op, never a shell-out."),

            // ── Budget surfaces (acceptance numbers) ─────────────────────────
            new Entry("budget.tick_hook", "— (target has none)",
                    "1ms wall + 20k instructions per tick hook",
                    CommandParityStatus.SUPPORTED,
                    "ScriptScheduler.TICK_HOOK_NANOS + ScriptBudget.tick(); tunable via RuntimeTunables."),
            new Entry("budget.standard_hook", "—",
                    "5ms wall + 100k instructions per standard hook",
                    CommandParityStatus.SUPPORTED,
                    "ScriptScheduler.STANDARD_HOOK_NANOS + ScriptBudget.standard()."),
            new Entry("budget.aggregate", "—",
                    "4ms aggregate script budget per server tick",
                    CommandParityStatus.SUPPORTED,
                    "ScriptScheduler.beginTick window; over-budget dispatches skip as 'aggregate tick budget exhausted' (deferral observable)."),
            new Entry("budget.memory", "—",
                    "1MiB cooperative live-memory bound per invocation",
                    CommandParityStatus.SUPPORTED,
                    "Meter.allocate on host-visible allocations (log lines, args); engine-internal heap is not per-script accountable — honest bound, documented."),
            new Entry("budget.recursion", "—",
                    "recursion depth 16",
                    CommandParityStatus.SUPPORTED,
                    "setMaximumInterpreterStackDepth — deep recursion raises a catchable engine error, never a VM stack overflow."),
            new Entry("budget.canonical_ops", "—",
                    "32 canonical op calls per hook",
                    CommandParityStatus.SUPPORTED,
                    "Meter.canonicalOp() at every ScriptHostApi mutating method entry."),

            // ── Failure isolation + lifecycle ────────────────────────────────
            new Entry("lifecycle.quarantine", "—",
                    "3 consecutive failures → QUARANTINED, explicit unquarantine",
                    CommandParityStatus.SUPPORTED,
                    "Scheduler failure counter; quarantined scripts skip dispatch until /storynpcs script unquarantine or reload."),
            new Entry("lifecycle.reload", "noppes script reload (files + player + Forge + world data)",
                    "/storynpcs script reload — re-register from YAML registry",
                    CommandParityStatus.INTENTIONAL_DEVIATION,
                    "Reload re-registers enabled definitions and recompiles on version bump; there is no player/Forge/world-data script tier — one authoritative YAML tier, disclosed."),
            new Entry("lifecycle.versioning", "script storage is file-based",
                    "YAML ScriptDefinition + version field",
                    CommandParityStatus.SUPPORTED,
                    "Script storage is versioned YAML; compiled-unit cache keys on id+version+source hash."),
            new Entry("editor.surface", "GuiScriptInterface (client script editor)",
                    "P10-1 screens owner",
                    CommandParityStatus.INTENTIONAL_DEVIATION,
                    "The in-game script editor GUI is a client surface deferred to P10-1; scripts are authored as YAML today.")
    );

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static long count(CommandParityStatus status) {
        return ENTRIES.stream().filter(e -> e.status() == status).count();
    }

    private ScriptHookMatrix() {}
}
