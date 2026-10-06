package com.storynpcs.script.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.script.ScriptBudget.Meter;
import com.storynpcs.service.AuthorizedActionResult;
import com.storynpcs.service.CanonicalMutationResult;
import com.storynpcs.service.FactionProgressionMutationRequest;
import com.storynpcs.service.PlayerProgressionActionRequest;
import com.storynpcs.service.QuestCompletionMutationRequest;
import com.storynpcs.service.QuestProgressionMutationRequest;
import com.storynpcs.service.StoryNpcsApplicationService;

/**
 * The only object scripts can reach (P9-2): a typed host surface bound to one
 * dispatch. Exposed as the {@code storynpcs} global. Reads are free; every
 * mutating call counts one canonical op against the invocation meter and
 * routes through the service's scripted boundary — which re-checks the
 * authored grants server-side. A script can therefore never invoke an
 * operation its definition did not declare, and can never widen beyond
 * player-scoped progression surfaces: definition writes have no scripted
 * boundary at all.
 *
 * <p>All mutating calls return {@code true}/{@code false} applied status;
 * denials are observable via the canonical mutation events the service emits.</p>
 */
public final class ScriptHostApi {

    /** Receives bounded script log lines — implemented by the runtime. */
    public interface LogSink {
        void log(String scriptId, String line);
    }

    /** Schedules a named timer for the calling script — implemented by the runtime. */
    public interface TimerSink {
        boolean startTimer(String scriptId, String name, long delayTicks);
        boolean cancelTimer(String scriptId, String name);
    }

    /**
     * Deterministic actor identity for a script — the same value identifies
     * the script in the scheduler and in canonical mutation audit records.
     */
    public static UUID actorUuidFor(String scriptId) {
        return UUID.nameUUIDFromBytes(("storynpcs:script/" + scriptId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private final String scriptId;
    private final UUID scriptActor;
    private final ScriptContext context;
    private final Meter meter;
    private final Set<String> grants;
    private final StoryNpcsApplicationService service;
    private final TimerSink timers;
    private final LogSink logger;

    public ScriptHostApi(String scriptId, ScriptContext context, Meter meter,
                         Set<String> grants, StoryNpcsApplicationService service,
                         TimerSink timers, LogSink logger) {
        this.scriptId = Objects.requireNonNull(scriptId);
        this.scriptActor = actorUuidFor(scriptId);
        this.context = Objects.requireNonNull(context);
        this.meter = Objects.requireNonNull(meter);
        this.grants = Set.copyOf(grants);
        this.service = Objects.requireNonNull(service);
        this.timers = Objects.requireNonNull(timers);
        this.logger = Objects.requireNonNull(logger);
    }

    // ── Read views (free — no canonical op, no grant required) ───────────────

    public String hookName() { return context.hookName(); }
    public String npcId() { return context.npcId(); }
    public String playerUuid() { return context.playerUuid(); }
    public String dialogueId() { return context.dialogueId(); }
    public String questId() { return context.questId(); }
    public String timerName() { return context.timerName(); }
    public String levelKey() { return context.levelKey(); }
    public String args() { return context.args(); }

    /** Bounded tagged log line — truncated at 512 chars. */
    public void log(String message) {
        String line = message == null ? "null" : String.valueOf(message);
        if (line.length() > 512) line = line.substring(0, 512);
        meter.allocate(line.length() * 2L);
        logger.log(scriptId, line);
    }

    // ── Timers ───────────────────────────────────────────────────────────────

    /** Schedules a named {@code timer} hook for this script. */
    public boolean startTimer(String name, long delayTicks) {
        meter.canonicalOp();
        if (name == null || name.isBlank() || name.length() > 64 || delayTicks < 1) return false;
        return timers.startTimer(scriptId, name, delayTicks);
    }

    public boolean cancelTimer(String name) {
        meter.canonicalOp();
        if (name == null || name.isBlank()) return false;
        return timers.cancelTimer(scriptId, name);
    }

    // ── Faction progression (canonical scripted boundary) ────────────────────

    public boolean factionSet(String playerUuid, String factionId, int points) {
        meter.canonicalOp();
        var request = FactionProgressionMutationRequest.set("script", scriptActor,
                uuid(playerUuid), id(factionId), points,
                service.currentFactionProgressionRevision(uuid(playerUuid)),
                UUID.randomUUID(), 0);
        return applied(service.mutateFactionProgressionScripted(request, grants));
    }

    public boolean factionAdjust(String playerUuid, String factionId, int delta) {
        meter.canonicalOp();
        var request = FactionProgressionMutationRequest.adjust("script", scriptActor,
                uuid(playerUuid), id(factionId), delta,
                service.currentFactionProgressionRevision(uuid(playerUuid)),
                UUID.randomUUID(), 0);
        return applied(service.mutateFactionProgressionScripted(request, grants));
    }

    public boolean factionRemove(String playerUuid, String factionId) {
        meter.canonicalOp();
        var request = FactionProgressionMutationRequest.remove("script", scriptActor,
                uuid(playerUuid), id(factionId),
                service.currentFactionProgressionRevision(uuid(playerUuid)),
                UUID.randomUUID(), 0);
        return applied(service.mutateFactionProgressionScripted(request, grants));
    }

    // ── Quest progression (canonical scripted boundary) ──────────────────────

    public boolean questStart(String playerUuid, String questId) {
        meter.canonicalOp();
        var request = QuestProgressionMutationRequest.start("script", scriptActor,
                uuid(playerUuid), id(questId),
                service.currentQuestProgressionRevision(uuid(playerUuid)),
                UUID.randomUUID());
        return applied(service.mutateQuestProgressionScripted(request, grants));
    }

    public boolean questProgress(String playerUuid, String questId,
                                 String objectiveId, int amount) {
        meter.canonicalOp();
        var request = QuestProgressionMutationRequest.progress("script", scriptActor,
                uuid(playerUuid), id(questId), objectiveId, amount,
                service.currentQuestProgressionRevision(uuid(playerUuid)),
                UUID.randomUUID());
        return applied(service.mutateQuestProgressionScripted(request, grants));
    }

    public boolean questReset(String playerUuid, String questId) {
        meter.canonicalOp();
        var request = QuestProgressionMutationRequest.reset("script", scriptActor,
                uuid(playerUuid), id(questId),
                service.currentQuestProgressionRevision(uuid(playerUuid)),
                UUID.randomUUID(), -1);
        return applied(service.mutateQuestProgressionScripted(request, grants));
    }

    public boolean questComplete(String playerUuid, String questId) {
        meter.canonicalOp();
        var request = QuestCompletionMutationRequest.of("script", scriptActor,
                uuid(playerUuid), id(questId),
                service.currentQuestProgressionRevision(uuid(playerUuid)),
                UUID.randomUUID());
        var result = service.completeQuestScripted(request, grants);
        return result != null && result.outcome() == com.storynpcs.service.QuestCompletionResult.Outcome.COMPLETED;
    }

    // ── Dialogue read markers ────────────────────────────────────────────────

    public boolean markDialogueRead(String playerUuid, String dialogueId) {
        meter.canonicalOp();
        var request = progressionRequest("dialogue.mark.read", uuid(playerUuid));
        var result = service.markDialogueReadScripted(request, id(dialogueId), grants);
        return result != null && result.applied();
    }

    public boolean clearDialogueReadMarkers(String playerUuid, String dialogueId) {
        meter.canonicalOp();
        var request = progressionRequest("dialogue.mark.clear", uuid(playerUuid));
        var result = service.clearDialogueReadMarkersScripted(request, id(dialogueId), grants);
        return result != null && result.applied();
    }

    // ── Transport + mail ─────────────────────────────────────────────────────

    public boolean unlockTransport(String playerUuid, String locationId) {
        meter.canonicalOp();
        var request = progressionRequest("transport.unlock", uuid(playerUuid));
        var result = service.unlockTransportLocationScripted(request, id(locationId), grants);
        return result != null && result.applied();
    }

    public boolean sendMail(String playerUuid, String recipientUuid,
                            String senderLabel, String subject, String body) {
        meter.canonicalOp();
        var request = progressionRequest("mail.send", uuid(playerUuid));
        var result = service.sendMailScripted(request, uuid(recipientUuid),
                senderLabel, subject, body, grants);
        return result != null && result.applied();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private PlayerProgressionActionRequest progressionRequest(String operation, UUID subject) {
        return new PlayerProgressionActionRequest(operation, "script", scriptActor,
                subject, UUID.randomUUID(), 0);
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(Objects.requireNonNull(raw, "player uuid"));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("malformed uuid: " + raw);
        }
    }

    private static NamespacedId id(String raw) {
        try {
            return NamespacedId.of(Objects.requireNonNull(raw, "namespaced id"));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("malformed namespaced id: " + raw);
        }
    }

    private static boolean applied(CanonicalMutationResult result) {
        return result != null && result.applied();
    }
}
