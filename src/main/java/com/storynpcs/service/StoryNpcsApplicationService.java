package com.storynpcs.service;

import com.storynpcs.api.event.*;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.dialogue.*;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.npc.*;
import com.storynpcs.domain.progression.PlayerProgression;
import com.storynpcs.domain.progression.PendingQuestCompletion;
import com.storynpcs.domain.progression.QuestProgressState;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.domain.quest.QuestObjective;
import com.storynpcs.domain.quest.QuestReward;
import com.storynpcs.domain.quest.QuestSerde;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.yaml.CrossReferenceValidator;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.yaml.YamlDefinitionLoader;
import com.storynpcs.yaml.YamlDefinitionWriter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Canonical Application Service Layer.
 * Guarantees 100% parity across GUI, Commands, API, and Scripts by routing
 * all mutations through these 15 canonical operations with authoritative validation.
 */
public class StoryNpcsApplicationService {
    private final DefinitionRegistry registry;
    private final ProgressionRepository progressionRepository;
    private final EventPublisher eventPublisher;
    private final Map<UUID, DialogueSession> activeSessions = new ConcurrentHashMap<>();
    /** Revision clocks are scoped by definition kind; NPC and quest IDs may legally overlap. */
    private final Map<String, Long> definitionRevisions = new ConcurrentHashMap<>();
    private final Map<UUID, CompletedMutation> completedMutations = new ConcurrentHashMap<>();
    /** Recent quest progression receipts are process-local; persisted revisions reject replay after restart. */
    private final Map<UUID, CompletedQuestMutation> completedQuestMutations = new ConcurrentHashMap<>();
    /** Recent faction progression receipts are process-local; persisted revisions reject replay after restart. */
    private final Map<UUID, CompletedFactionMutation> completedFactionMutations = new ConcurrentHashMap<>();
    private final Object[] questMutationLocks = createQuestMutationLocks();
    private final Object[] progressionMutationLocks = createQuestMutationLocks();
    private final Object[] factionMutationLocks = createQuestMutationLocks();
    private final Object[] factionProgressionMutationLocks = createQuestMutationLocks();
    private final QuestEventQueue[] questEventQueues = createQuestEventQueues();
    /** Accessed only while holding canonicalMutationLock; also blocks same-thread reentrant callbacks. */
    private final Map<UUID, MutationRequest> inProgressMutationRequests = new HashMap<>();
    /** Serializes the check/execute/cache transaction; world mutations remain outside this path. */
    private final Object canonicalMutationLock = new Object();
    /** May be null in unit-test contexts — all code that uses this must null-check. */
    private final net.minecraft.server.MinecraftServer minecraftServer;
    private final com.storynpcs.persistence.DurableOperationJournal tradeOperationJournal;
    private com.storynpcs.persistence.TradeStateRepository tradeStateRepository;
    /** May be null in unit-test contexts — needed for VULN-57 file deletion on deleteNpc. */
    private YamlDefinitionLoader loader;
    /** Test seam: observes the non-atomic XP/item reward boundary for exactly-once proofs. */
    private volatile java.util.function.BiConsumer<UUID, QuestReward> rewardSideEffectOverride;

    void setRewardSideEffectOverride(java.util.function.BiConsumer<UUID, QuestReward> override) {
        this.rewardSideEffectOverride = override;
    }

    public void setLoader(YamlDefinitionLoader loader) {
        this.loader = loader;
    }

    public void setTradeStateRepository(com.storynpcs.persistence.TradeStateRepository tradeStateRepository) {
        this.tradeStateRepository = tradeStateRepository;
    }

    public StoryNpcsApplicationService(DefinitionRegistry registry,
                                       ProgressionRepository progressionRepository,
                                       EventPublisher eventPublisher) {
        this(registry, progressionRepository, eventPublisher, null);
    }

    public StoryNpcsApplicationService(DefinitionRegistry registry,
                                       ProgressionRepository progressionRepository,
                                       EventPublisher eventPublisher,
                                       net.minecraft.server.MinecraftServer minecraftServer) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.progressionRepository = Objects.requireNonNull(progressionRepository, "progressionRepository");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.minecraftServer = minecraftServer; // nullable — degrades gracefully
        this.tradeOperationJournal = new com.storynpcs.persistence.DurableOperationJournal(
                progressionRepository.storageDirectory().resolve("trade-operations"));
    }

    // ==========================================
    // 1. NPC Lifecycle Operations
    // ==========================================

    public void createNpc(NpcDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (definition.getId() == null) {
            throw new IllegalArgumentException("NPC definition must have an ID");
        }
        synchronized (canonicalMutationLock) {
            synchronized (this) {
                DefinitionRegistry snapshot = new DefinitionRegistry();
                snapshot.copyFrom(registry);
                snapshot.registerNpc(definition);
                ValidationResult validation = CrossReferenceValidator.validate(snapshot);
                if (validation.hasErrors()) {
                    throw new IllegalArgumentException(validation.formatReport());
                }
                registry.registerNpc(definition);
                definitionRevisions.putIfAbsent(revisionKey("npc", definition.getId()), 0L);
            }
        }
    }

    /**
     * Persists an NPC definition: validates it against a snapshot of the live registry
     * (so dialogueId/factionId references must resolve), writes YAML atomically, then
     * registers it live. Canonical path for `/storynpcs npc create` and future NPC
     * authoring surfaces — mirrors {@link #saveDialogue}.
     *
     * @return validation result; on errors nothing is written or registered.
     */
    public ValidationResult saveNpc(NpcDefinition definition) {
        synchronized (canonicalMutationLock) {
            return saveNpcUnderCanonicalLock(definition);
        }
    }

    /** Must be called while holding {@code canonicalMutationLock}; serializes with safe dialogue rollback. */
    private synchronized ValidationResult saveNpcUnderCanonicalLock(NpcDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        ValidationResult result = ValidationResult.valid();

        if (definition.getId() == null) {
            result.addError("NPC_ID_MISSING", "NPC definition must have an ID");
            return result;
        }

        DefinitionRegistry snapshot = new DefinitionRegistry();
        snapshot.copyFrom(registry);
        snapshot.registerNpc(definition);
        result.merge(CrossReferenceValidator.validate(snapshot));
        if (result.hasErrors()) {
            return result;
        }

        if (loader != null && loader.getLastLoadedRootPath() != null) {
            try {
                var savedFile = new YamlDefinitionWriter(loader.getDefinitionWriteCoordinator()).writeDefinition(
                        loader.getLastLoadedRootPath(), "npcs",
                        YamlDefinitionWriter.fileNameFor(definition.getId()), definition,
                        loader.getDefinitionFiles("npcs", definition.getId()));
                loader.recordDefinitionFile("npcs", definition.getId(), savedFile);
            } catch (IOException e) {
                result.addError("PERSIST_WRITE_FAILED", "Failed to write NPC file: " + e.getMessage());
                return result;
            }
        }

        registry.registerNpc(definition);
        definitionRevisions.merge(revisionKey("npc", definition.getId()), 1L, Long::sum);
        return result;
    }

    /** Creates and persists an NPC through the revisioned canonical request boundary. */
    public CanonicalMutationResult createNpc(MutationRequest request, NpcDefinition definition) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(definition, "definition");
        String payloadJson = NpcDefinitionSerde.toJson(definition);
        NpcDefinition payload = NpcDefinitionSerde.fromJson(payloadJson)
                .orElseThrow(() -> new IllegalArgumentException("Unable to snapshot NPC create payload"));
        return executeCanonicalMutation(request, "npc", "create",
                MutationPayloadFingerprint.ofJson("npc.create", payloadJson), () -> {
            if (!request.targetId().equals(payload.getId())) {
                ValidationResult result = ValidationResult.valid();
                result.addError("TARGET_ID_MISMATCH", "NPC ID does not match request target");
                return result;
            }
            if (registry.getNpc(request.targetId()).isPresent()) {
                ValidationResult result = ValidationResult.valid();
                result.addError("NPC_ALREADY_EXISTS", "NPC already exists: " + request.targetId());
                return result;
            }
            return saveNpc(payload);
        });
    }

    /**
     * Applies one canonical NPC mutation to a detached copy, then validates and
     * persists it. Adapters never mutate the live registry object before validation.
     */
    public ValidationResult mutateNpc(NamespacedId id, Consumer<NpcDefinition> mutation) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mutation, "mutation");
        MutationRequest request = new MutationRequest(
                "npc.mutate", "adapter", "npc.mutate", id,
                definitionRevisions.getOrDefault(revisionKey("npc", id), 0L), UUID.randomUUID());
        return mutateNpc(request, mutation).diagnostics();
    }

    /** Revision-checked callback mutation; same-ID retries fail closed because the callback has no payload intent. */
    public CanonicalMutationResult mutateNpc(MutationRequest request, Consumer<NpcDefinition> mutation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(mutation, "mutation");
        return executeCanonicalMutation(request, "npc", "mutate", () -> {
            NpcDefinition current = registry.getNpc(request.targetId())
                    .orElseThrow(() -> new NoSuchElementException("NPC not found: " + request.targetId()));
            NpcDefinition working = NpcDefinitionSerde.fromJson(NpcDefinitionSerde.toJson(current))
                    .orElseThrow(() -> new IllegalStateException("Unable to copy NPC for mutation: " + request.targetId()));
            mutation.accept(working);
            if (!request.targetId().equals(working.getId())) {
                ValidationResult result = ValidationResult.valid();
                result.addError("TARGET_ID_MISMATCH", "NPC mutation cannot change the request target ID");
                return result;
            }
            NpcDefinition committed = NpcDefinitionSerde.fromJson(NpcDefinitionSerde.toJson(working))
                    .orElseThrow(() -> new IllegalStateException("Unable to detach NPC mutation result: " + request.targetId()));
            return saveNpc(committed);
        });
    }

    /** Canonical full-definition replacement used by packet adapters after decoding detached input. */
    public CanonicalMutationResult replaceNpc(MutationRequest request, NpcDefinition replacement) {
        Objects.requireNonNull(replacement, "replacement");
        String payloadJson = NpcDefinitionSerde.toJson(replacement);
        NpcDefinition payload = NpcDefinitionSerde.fromJson(payloadJson)
                .orElseThrow(() -> new IllegalArgumentException("Unable to snapshot NPC replacement payload"));
        return executeCanonicalMutation(request, "npc", "replace",
                MutationPayloadFingerprint.ofJson("npc.replace", payloadJson), () -> {
            if (!request.targetId().equals(payload.getId())) {
                ValidationResult result = ValidationResult.valid();
                result.addError("TARGET_ID_MISMATCH", "Replacement ID does not match request target");
                return result;
            }
            return saveNpc(payload);
        });
    }

    private CanonicalMutationResult executeCanonicalMutation(
            MutationRequest request,
            String expectedFamily,
            String expectedAction,
            java.util.function.Supplier<ValidationResult> operation) {
        return executeCanonicalMutation(request, expectedFamily, expectedAction, null, operation);
    }

    private CanonicalMutationResult executeCanonicalMutation(
            MutationRequest request,
            String expectedFamily,
            String expectedAction,
            String payloadFingerprint,
            java.util.function.Supplier<ValidationResult> operation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(operation, "operation");
        synchronized (canonicalMutationLock) {
            String expectedOperation = expectedFamily + "." + expectedAction;
            boolean capabilityMatches = switch (expectedAction) {
                case "create", "mutate" -> Set.of(expectedFamily + ".mutate", expectedFamily + ".edit")
                        .contains(request.capability());
                case "replace" -> Set.of(expectedFamily + ".edit", expectedFamily + ".mutate")
                        .contains(request.capability());
                case "delete" -> request.capability().equals(expectedFamily + ".delete");
                default -> false;
            };
            if (!request.operation().equals(expectedOperation) || !capabilityMatches) {
                ValidationResult mismatch = ValidationResult.valid();
                mismatch.addError("MUTATION_ROUTE_MISMATCH",
                        "Request operation/capability do not match " + expectedOperation);
                CanonicalMutationResult rejected = new CanonicalMutationResult(false, false,
                        definitionRevisions.getOrDefault(revisionKey(request), 0L), mismatch,
                        List.of(request.operation() + ":rejected"), "REJECTED_NO_SIDE_EFFECTS");
                publishCanonicalEvent(request, rejected);
                return rejected;
            }
            AuthorizationDecision authorization = AuthorizationPolicy.evaluate(request);
            if (!authorization.allowed()) {
                ValidationResult denied = ValidationResult.valid();
                denied.addError(authorization.code(), authorization.message());
                CanonicalMutationResult rejected = new CanonicalMutationResult(false, false,
                        definitionRevisions.getOrDefault(revisionKey(request), 0L), denied,
                        List.of(request.operation() + ":authorization-denied"), "REJECTED_AUTHORIZATION");
                publishCanonicalEvent(request, rejected);
                return rejected;
            }
            if (!inProgressMutationRequests.isEmpty()) {
                ValidationResult reentrant = ValidationResult.valid();
                String code = inProgressMutationRequests.containsKey(request.requestId())
                        ? "MUTATION_IN_PROGRESS" : "REENTRANT_MUTATION";
                reentrant.addError(code, "Canonical mutation callbacks cannot execute nested mutations");
                return new CanonicalMutationResult(false, false,
                        definitionRevisions.getOrDefault(revisionKey(request), 0L), reentrant,
                        List.of(), "REJECTED_NO_SIDE_EFFECTS");
            }
            CompletedMutation cached = completedMutations.get(request.requestId());
            if (cached != null) {
                if (!cached.request().equals(request)) {
                    ValidationResult reused = ValidationResult.valid();
                    reused.addError("REQUEST_ID_REUSE", "Request ID is already bound to a different mutation request");
                    CanonicalMutationResult rejected = new CanonicalMutationResult(false, false,
                            definitionRevisions.getOrDefault(revisionKey(request), 0L), reused);
                    publishCanonicalEvent(request, rejected);
                    return rejected;
                }
                if (cached.payloadFingerprint() == null || payloadFingerprint == null) {
                    ValidationResult unbound = ValidationResult.valid();
                    unbound.addError("IDEMPOTENCY_PAYLOAD_UNBOUND",
                            "Cannot replay a mutation request without a verifiable payload fingerprint");
                    CanonicalMutationResult rejected = new CanonicalMutationResult(false, false,
                            definitionRevisions.getOrDefault(revisionKey(request), 0L), unbound,
                            List.of(request.operation() + ":payload-unbound"), "REJECTED_NO_SIDE_EFFECTS");
                    publishCanonicalEvent(request, rejected);
                    return rejected;
                }
                if (!cached.payloadFingerprint().equals(payloadFingerprint)) {
                    ValidationResult mismatch = ValidationResult.valid();
                    mismatch.addError("REQUEST_PAYLOAD_MISMATCH",
                            "Request ID is already bound to a different mutation payload");
                    CanonicalMutationResult rejected = new CanonicalMutationResult(false, false,
                            definitionRevisions.getOrDefault(revisionKey(request), 0L), mismatch,
                            List.of(request.operation() + ":payload-mismatch"), "REJECTED_NO_SIDE_EFFECTS");
                    publishCanonicalEvent(request, rejected);
                    return rejected;
                }
                CanonicalMutationResult result = cached.result();
                return new CanonicalMutationResult(result.applied(), true, result.revision(), result.diagnostics(),
                        result.events(), result.recoveryOutcome());
            }
            long currentRevision = definitionRevisions.getOrDefault(revisionKey(request), 0L);
            if (currentRevision != request.expectedRevision()) {
                ValidationResult stale = ValidationResult.valid();
                stale.addError("STALE_REVISION", "Expected revision " + request.expectedRevision()
                        + " but current revision is " + currentRevision + " for " + request.targetId());
                CanonicalMutationResult rejected = new CanonicalMutationResult(false, false, currentRevision, stale);
                completedMutations.put(request.requestId(),
                        new CompletedMutation(request, payloadFingerprint, rejected.snapshot()));
                publishCanonicalEvent(request, rejected);
                return rejected;
            }
            ValidationResult diagnostics;
            inProgressMutationRequests.put(request.requestId(), request);
            try {
                diagnostics = operation.get();
            } catch (RuntimeException exception) {
                diagnostics = ValidationResult.valid();
                diagnostics.addError("CANONICAL_MUTATION_REJECTED",
                        exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            } finally {
                inProgressMutationRequests.remove(request.requestId());
            }
            if (diagnostics == null) {
                diagnostics = ValidationResult.valid();
                diagnostics.addError("CANONICAL_MUTATION_REJECTED", "Canonical mutation returned no diagnostics");
            }
            long revision = definitionRevisions.getOrDefault(revisionKey(request), currentRevision);
            CanonicalMutationResult result = new CanonicalMutationResult(
                    !diagnostics.hasErrors(), false, revision, diagnostics,
                    List.of(request.operation() + (diagnostics.hasErrors() ? ":rejected" : ":applied")),
                    diagnostics.hasErrors() ? "REJECTED_NO_SIDE_EFFECTS" : "COMMITTED");
            completedMutations.put(request.requestId(),
                    new CompletedMutation(request, payloadFingerprint, result.snapshot()));
            publishCanonicalEvent(request, result);
            return result;
        }
    }

    private record CompletedMutation(
            MutationRequest request, String payloadFingerprint, CanonicalMutationResult result) {}

    private record CompletedQuestMutation(
            String payloadFingerprint, CanonicalMutationResult result, boolean completionPending) {}

    private record CompletedFactionMutation(
            String payloadFingerprint, CanonicalMutationResult result) {}

    private record QuestMutationExecution(
            CanonicalMutationResult result, int currentCount, int requiredCount, boolean shouldComplete) {
        private static QuestMutationExecution resultOnly(CanonicalMutationResult result) {
            return new QuestMutationExecution(result, 0, 0, false);
        }
    }

    private static final class QuestEventQueue {
        private final ArrayDeque<List<StoryNpcsEvent>> pending = new ArrayDeque<>();
        private boolean dispatching;
    }

    private static QuestEventQueue[] createQuestEventQueues() {
        QuestEventQueue[] queues = new QuestEventQueue[256];
        Arrays.setAll(queues, ignored -> new QuestEventQueue());
        return queues;
    }

    /**
     * Dispatches each player's quest notifications as an indivisible ordered group.
     * Reentrant listeners append later mutations behind the current group's events.
     */
    private QuestEventQueue enqueueQuestEvents(UUID playerUuid, List<? extends StoryNpcsEvent> events) {
        if (events.isEmpty()) return null;
        QuestEventQueue queue = questEventQueues[playerUuid.hashCode() & (questEventQueues.length - 1)];
        synchronized (queue) {
            queue.pending.addLast(List.copyOf(events));
            if (queue.dispatching) return null;
            queue.dispatching = true;
            return queue;
        }
    }

    private void dispatchQuestEvents(UUID playerUuid, List<? extends StoryNpcsEvent> events) {
        QuestEventQueue queue = enqueueQuestEvents(playerUuid, events);
        if (queue != null) drainQuestEvents(queue);
    }

    private void drainQuestEvents(QuestEventQueue queue) {
        while (true) {
            List<StoryNpcsEvent> next;
            synchronized (queue) {
                next = queue.pending.pollFirst();
                if (next == null) {
                    queue.dispatching = false;
                    return;
                }
            }
            for (int index = 0; index < next.size(); index++) {
                try {
                    eventPublisher.publish(next.get(index));
                } catch (Throwable failure) {
                    if (failure instanceof VirtualMachineError
                            || "java.lang.ThreadDeath".equals(failure.getClass().getName())) {
                        synchronized (queue) {
                            for (int remaining = next.size() - 1; remaining > index; remaining--) {
                                queue.pending.addFirst(List.of(next.get(remaining)));
                            }
                            queue.dispatching = false;
                        }
                        throw (Error) failure;
                    }
                    System.err.println("Error dispatching quest event " + next.get(index).getClass().getSimpleName()
                            + ": " + failure.getMessage());
                }
            }
        }
    }

    private static Object[] createQuestMutationLocks() {
        Object[] locks = new Object[256];
        Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    private void publishCanonicalEvent(MutationRequest request, CanonicalMutationResult result) {
        eventPublisher.publish(new CanonicalMutationEvent(
                request.operation(), request.actorType(), request.targetId(), request.requestId(),
                result.applied(), result.revision(), result.recoveryOutcome()));
    }

    /** Revision tokens for every definition of one kind, keyed by bare definition id. */
    public Map<String, Long> currentRevisions(String kind) {
        Objects.requireNonNull(kind, "kind");
        String prefix = kind + ":";
        Map<String, Long> revisions = new HashMap<>();
        definitionRevisions.forEach((key, value) -> {
            if (value != null && key.startsWith(prefix)) {
                revisions.put(key.substring(prefix.length()), value);
            }
        });
        return revisions;
    }

    public long currentRevision(String kind, NamespacedId id) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        return definitionRevisions.getOrDefault(revisionKey(kind, id), 0L);
    }

    private static String revisionKey(String kind, NamespacedId id) {
        return kind + ":" + id;
    }

    private static String revisionKey(MutationRequest request) {
        String operation = request.operation();
        int separator = operation.indexOf('.');
        String kind = separator > 0 ? operation.substring(0, separator) : operation;
        return revisionKey(kind, request.targetId());
    }

    private static void requireSuccessfulMutation(ValidationResult result) {
        if (result.hasErrors()) {
            throw new IllegalStateException(result.formatReport());
        }
    }

    /** Deletes the durable source first; a missing known source is safe, but I/O failure is not. */
    private boolean deleteDefinitionFileBeforeRegistryMutation(String type, NamespacedId id) {
        if (loader == null || loader.getLastLoadedRootPath() == null) {
            return true;
        }
        try {
            loader.deleteDefinitionFile(type, id);
            return true;
        } catch (IOException e) {
            System.err.println("[StoryNPCs] Refusing to remove " + type + " '" + id
                    + "' from the live registry because its YAML source could not be deleted: " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean deleteNpc(NamespacedId id) {
        Objects.requireNonNull(id, "id");
        if (registry.getNpc(id).isEmpty()) {
            return false;
        }
        if (!deleteDefinitionFileBeforeRegistryMutation("npc", id)) {
            return false;
        }
        registry.removeNpc(id);
        definitionRevisions.merge(revisionKey("npc", id), 1L, Long::sum);
        return true;
    }

    /** Replay-safe, revision-checked NPC deletion for command/network adapters. */
    public CanonicalMutationResult deleteNpc(MutationRequest request) {
        return executeCanonicalMutation(request, "npc", "delete",
                MutationPayloadFingerprint.of("npc.delete", request.targetId().toString()), () -> {
            if (registry.getNpc(request.targetId()).isEmpty()) {
                ValidationResult result = ValidationResult.valid();
                result.addError("NPC_NOT_FOUND", "NPC not found: " + request.targetId());
                return result;
            }
            if (!deleteNpc(request.targetId())) {
                ValidationResult failure = ValidationResult.valid();
                failure.addError(registry.getNpc(request.targetId()).isPresent()
                                ? "DEFINITION_DELETE_FAILED" : "NPC_NOT_FOUND",
                        "NPC '" + request.targetId() + "' could not be deleted");
                return failure;
            }
            return ValidationResult.valid();
        });
    }

    public void updateNpcDisplay(NamespacedId id, NpcDisplay display) {
        requireSuccessfulMutation(mutateNpc(id, npc -> npc.setDisplay(Objects.requireNonNull(display, "display"))));
    }

    public void updateNpcStats(NamespacedId id, NpcStats stats) {
        requireSuccessfulMutation(mutateNpc(id, npc -> npc.setStats(Objects.requireNonNull(stats, "stats"))));
    }

    public void updateNpcAi(NamespacedId id, NpcAi ai) {
        requireSuccessfulMutation(mutateNpc(id, npc -> npc.setAi(Objects.requireNonNull(ai, "ai"))));
    }

    public void updateNpcInventory(NamespacedId id, List<String> inventory) {
        requireSuccessfulMutation(mutateNpc(id, npc -> npc.setInventory(new ArrayList<>(inventory))));
    }

    public void setNpcMark(NamespacedId id, NpcMark mark) {
        requireSuccessfulMutation(mutateNpc(id, npc -> npc.setMark(mark)));
    }

    public void clearNpcMark(NamespacedId id) {
        setNpcMark(id, null);
    }

    public ValidationResult assignDialogue(NamespacedId npcId, NamespacedId dialogueId) {
        Objects.requireNonNull(npcId, "npcId");
        if (dialogueId != null && registry.getDialogue(dialogueId).isEmpty()) {
            ValidationResult result = ValidationResult.valid();
            result.addError("DIALOGUE_NOT_FOUND", "Dialogue not found: " + dialogueId);
            return result;
        }
        return mutateNpc(npcId, npc -> npc.setDialogueId(dialogueId));
    }

    public ValidationResult assignFaction(NamespacedId npcId, NamespacedId factionId) {
        Objects.requireNonNull(npcId, "npcId");
        if (factionId != null && registry.getFaction(factionId).isEmpty()) {
            ValidationResult result = ValidationResult.valid();
            result.addError("FACTION_NOT_FOUND", "Faction not found: " + factionId);
            return result;
        }
        return mutateNpc(npcId, npc -> npc.setFactionId(factionId));
    }


    // ==========================================
    // 2. Dialogue Directed-Graph Operations
    // ==========================================

    public DialogueView startDialogue(UUID playerUuid, NamespacedId dialogueId) {
        return startDialogue(playerUuid, dialogueId, null, null, 0.0, 0.0, 0.0);
    }

    public DialogueView startDialogue(UUID playerUuid, NamespacedId dialogueId, UUID npcEntityUuid, String dimensionId, double originX, double originY, double originZ) {
        DialogueGraph graph = registry.getDialogue(dialogueId)
                .orElseThrow(() -> new NoSuchElementException("Dialogue not found: " + dialogueId));

        DialogueSession session = new DialogueSession(playerUuid, graph, npcEntityUuid, dimensionId, originX, originY, originZ);
        session.setNpcDisplayName(resolveNpcDisplayName(npcEntityUuid));
        activeSessions.put(playerUuid, session);

        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        progression.recordDialogueNodeVisit(session.getCurrentNodeId());

        eventPublisher.publish(new DialogueOpenEvent(playerUuid, dialogueId, session.getCurrentNodeId()));
        return buildDialogueView(session, progression);
    }

    public Optional<DialogueSession> getActiveSession(UUID playerUuid) {
        return Optional.ofNullable(activeSessions.get(playerUuid));
    }

    public DialogueView chooseDialogueOption(UUID playerUuid, int optionIndex) {
        DialogueSession session = activeSessions.get(playerUuid);
        if (session == null || !session.isActive()) {
            return DialogueView.closed(session != null ? session.getDialogueId() : null);
        }

        DialogueNode currentNode = session.getCurrentNode();
        if (currentNode == null) {
            session.close();
            activeSessions.remove(playerUuid);
            return DialogueView.closed(session.getDialogueId());
        }

        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        // Must use the session-aware overload so onceOnly filtering matches buildDialogueView —
        // otherwise the displayed option index resolves to a different edge than the one shown.
        List<DialogueEdge> availableEdges = getAvailableEdges(currentNode, progression, session);

        if (optionIndex < 0 || optionIndex >= availableEdges.size()) {
            closeDialogue(playerUuid);
            return DialogueView.closed(session.getDialogueId());
        }

        DialogueEdge chosen = availableEdges.get(optionIndex);
        String fromNodeId = currentNode.getId();
        String toNodeId = chosen.getTargetNodeId();

        // Record selection
        if (chosen.isOnceOnly()) {
            session.recordOptionSelection(fromNodeId + "->" + toNodeId);
        }

        // Execute actions authoritatively
        if (chosen.getActions() != null) {
            for (DialogueAction action : chosen.getActions()) {
                executeAction(playerUuid, action);
            }
        }

        if (!session.isActive()) {
            return DialogueView.closed(session.getDialogueId());
        }

        eventPublisher.publish(new DialogueOptionSelectEvent(playerUuid, session.getDialogueId(), fromNodeId, toNodeId, optionIndex));

        // Advance graph
        session.advanceTo(toNodeId);
        progression.recordDialogueNodeVisit(toNodeId);

        DialogueView view = buildDialogueView(session, progression);
        if (view.isTerminal()) {
            activeSessions.remove(playerUuid);
            session.close();
        }
        return view;
    }

    public void closeDialogue(UUID playerUuid) {
        DialogueSession session = activeSessions.remove(playerUuid);
        if (session != null) {
            session.close();
        }
    }

    /**
     * Persists an edited dialogue graph: validates it against a snapshot of the live
     * registry (so cross-references to quests/factions resolve), writes it to YAML
     * atomically, then updates the live registry. Canonical mutation path for the
     * GUI editor's Save — packets/commands stay thin adapters over this.
     *
     * @return validation result; on errors nothing is written or registered.
     */
    public synchronized ValidationResult saveDialogue(NamespacedId expectedId, DialogueGraph graph) {
        Objects.requireNonNull(expectedId, "expectedId");
        Objects.requireNonNull(graph, "graph");
        ValidationResult result = ValidationResult.valid();

        if (graph.getId() == null || !graph.getId().equals(expectedId)) {
            result.addError("GRAPH_ID_MISMATCH",
                    "Graph id '" + graph.getId() + "' does not match requested dialogue '" + expectedId + "'");
            return result;
        }

        DefinitionRegistry snapshot = new DefinitionRegistry();
        snapshot.copyFrom(registry);
        snapshot.registerDialogue(graph);
        result.merge(CrossReferenceValidator.validate(snapshot));
        if (result.hasErrors()) {
            return result;
        }

        if (loader != null && loader.getLastLoadedRootPath() != null) {
            try {
                var savedFile = new YamlDefinitionWriter(loader.getDefinitionWriteCoordinator()).writeDefinition(
                        loader.getLastLoadedRootPath(), "dialogues",
                        YamlDefinitionWriter.fileNameFor(graph.getId()), graph,
                        loader.getDefinitionFiles("dialogues", graph.getId()));
                loader.recordDefinitionFile("dialogues", graph.getId(), savedFile);
            } catch (IOException e) {
                result.addError("PERSIST_WRITE_FAILED", "Failed to write dialogue file: " + e.getMessage());
                return result;
            }
        }

        registry.registerDialogue(graph);
        definitionRevisions.merge(revisionKey("dialogue", graph.getId()), 1L, Long::sum);
        return result;
    }

    /** Applies a dialogue graph mutation to a detached copy and commits after validation. */
    public ValidationResult mutateDialogue(NamespacedId id, Consumer<DialogueGraph> mutation) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mutation, "mutation");
        MutationRequest request = new MutationRequest(
                "dialogue.mutate", "adapter", "dialogue.mutate", id,
                definitionRevisions.getOrDefault(revisionKey("dialogue", id), 0L), UUID.randomUUID());
        return mutateDialogue(request, mutation).diagnostics();
    }

    /** Revision-checked callback mutation; same-ID retries fail closed because the callback has no payload intent. */
    public CanonicalMutationResult mutateDialogue(MutationRequest request, Consumer<DialogueGraph> mutation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(mutation, "mutation");
        return executeCanonicalMutation(request, "dialogue", "mutate", () -> {
            DialogueGraph current = registry.getDialogue(request.targetId())
                    .orElseThrow(() -> new NoSuchElementException("Dialogue not found: " + request.targetId()));
            DialogueGraph working = DialogueGraphSerde.fromJson(DialogueGraphSerde.toJson(current))
                    .orElseThrow(() -> new IllegalStateException("Unable to copy dialogue for mutation: " + request.targetId()));
            mutation.accept(working);
            if (!request.targetId().equals(working.getId())) {
                ValidationResult result = ValidationResult.valid();
                result.addError("TARGET_ID_MISMATCH", "Dialogue mutation cannot change the request target ID");
                return result;
            }
            DialogueGraph committed = DialogueGraphSerde.fromJson(DialogueGraphSerde.toJson(working))
                    .orElseThrow(() -> new IllegalStateException("Unable to detach dialogue mutation result: " + request.targetId()));
            return saveDialogue(request.targetId(), committed);
        });
    }

    /** Canonical full-graph replacement used by packet adapters after decoding detached input. */
    public CanonicalMutationResult replaceDialogue(MutationRequest request, DialogueGraph replacement) {
        Objects.requireNonNull(replacement, "replacement");
        String payloadJson = DialogueGraphSerde.toJson(replacement);
        DialogueGraph payload = DialogueGraphSerde.fromJson(payloadJson)
                .orElseThrow(() -> new IllegalArgumentException("Unable to snapshot dialogue replacement payload"));
        return executeCanonicalMutation(request, "dialogue", "replace",
                MutationPayloadFingerprint.ofJson("dialogue.replace", payloadJson), () -> {
                    if (!request.targetId().equals(payload.getId())) {
                        ValidationResult result = ValidationResult.valid();
                        result.addError("TARGET_ID_MISMATCH", "Replacement ID does not match request target");
                        return result;
                    }
                    return saveDialogue(request.targetId(), payload);
                });
    }

    /**
     * Persists a quest definition: validates it against a snapshot of the live registry
     * (so prerequisite/faction references must resolve), writes YAML atomically, then
     * registers it live. Canonical mutation path for `/storynpcs quest create/set/
     * objective/reward` — mirrors {@link #saveNpc}.
     *
     * @return validation result; on errors nothing is written or registered.
     */
    public synchronized ValidationResult saveQuest(Quest quest) {
        Objects.requireNonNull(quest, "quest");
        ValidationResult result = ValidationResult.valid();

        if (quest.getId() == null) {
            result.addError("QUEST_ID_MISSING", "Quest definition must have an ID");
            return result;
        }

        DefinitionRegistry snapshot = new DefinitionRegistry();
        snapshot.copyFrom(registry);
        snapshot.registerQuest(quest);
        result.merge(CrossReferenceValidator.validate(snapshot));
        if (result.hasErrors()) {
            return result;
        }

        if (loader != null && loader.getLastLoadedRootPath() != null) {
            try {
                var savedFile = new YamlDefinitionWriter(loader.getDefinitionWriteCoordinator()).writeDefinition(
                        loader.getLastLoadedRootPath(), "quests",
                        YamlDefinitionWriter.fileNameFor(quest.getId()), quest,
                        loader.getDefinitionFiles("quests", quest.getId()));
                loader.recordDefinitionFile("quests", quest.getId(), savedFile);
            } catch (IOException e) {
                result.addError("PERSIST_WRITE_FAILED", "Failed to write quest file: " + e.getMessage());
                return result;
            }
        }

        registry.registerQuest(quest);
        definitionRevisions.merge(revisionKey("quest", quest.getId()), 1L, Long::sum);
        return result;
    }

    /** Applies a quest mutation to a detached copy and commits only after validation. */
    public ValidationResult mutateQuest(NamespacedId id, Consumer<Quest> mutation) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mutation, "mutation");
        MutationRequest request = new MutationRequest(
                "quest.mutate", "adapter", "quest.mutate", id,
                definitionRevisions.getOrDefault(revisionKey("quest", id), 0L), UUID.randomUUID());
        return mutateQuest(request, mutation).diagnostics();
    }

    /** Revision-checked callback mutation; same-ID retries fail closed because the callback has no payload intent. */
    public CanonicalMutationResult mutateQuest(MutationRequest request, Consumer<Quest> mutation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(mutation, "mutation");
        return executeCanonicalMutation(request, "quest", "mutate", () -> {
            Quest current = registry.getQuest(request.targetId())
                    .orElseThrow(() -> new NoSuchElementException("Quest not found: " + request.targetId()));
            Quest working = QuestSerde.fromJson(QuestSerde.toJson(current))
                    .orElseThrow(() -> new IllegalStateException("Unable to copy quest for mutation: " + request.targetId()));
            mutation.accept(working);
            if (!request.targetId().equals(working.getId())) {
                ValidationResult result = ValidationResult.valid();
                result.addError("TARGET_ID_MISMATCH", "Quest mutation cannot change the request target ID");
                return result;
            }
            Quest committed = QuestSerde.fromJson(QuestSerde.toJson(working))
                    .orElseThrow(() -> new IllegalStateException("Unable to detach quest mutation result: " + request.targetId()));
            return saveQuest(committed);
        });
    }

    /** Canonical full-definition replacement used by packet adapters after decoding detached input. */
    public CanonicalMutationResult replaceQuest(MutationRequest request, Quest replacement) {
        Objects.requireNonNull(replacement, "replacement");
        String payloadJson = QuestSerde.toJson(replacement);
        Quest payload = QuestSerde.fromJson(payloadJson)
                .orElseThrow(() -> new IllegalArgumentException("Unable to snapshot quest replacement payload"));
        return executeCanonicalMutation(request, "quest", "replace",
                MutationPayloadFingerprint.ofJson("quest.replace", payloadJson), () -> {
            if (!request.targetId().equals(payload.getId())) {
                ValidationResult result = ValidationResult.valid();
                result.addError("TARGET_ID_MISMATCH", "Replacement ID does not match request target");
                return result;
            }
            return saveQuest(payload);
        });
    }

    /**
     * Scaffolds a minimal-but-valid quest and persists it. The cross-reference
     * validator rejects quests with no objectives, so the scaffold ships one
     * placeholder CUSTOM objective the admin replaces via `quest objective`.
     */
    public ValidationResult createQuest(NamespacedId id, String title) {
        Objects.requireNonNull(id, "id");
        if (registry.getQuest(id).isPresent()) {
            ValidationResult res = ValidationResult.valid();
            res.addError("QUEST_ALREADY_EXISTS", "Quest '" + id + "' already exists");
            return res;
        }
        String questTitle = (title != null && !title.isBlank()) ? title.trim() : id.getPath();
        Quest quest = new Quest(id, questTitle);
        quest.setObjectives(List.of(new QuestObjective("objective_1",
                QuestObjective.Type.CUSTOM, "describe_the_objective", 1)));
        return saveQuest(quest);
    }

    /** Creates a quest through the revisioned canonical request boundary. */
    public CanonicalMutationResult createQuest(MutationRequest request, String title) {
        Objects.requireNonNull(request, "request");
        return executeCanonicalMutation(request, "quest", "create",
                MutationPayloadFingerprint.of("quest.create.title", title), () -> {
            if (registry.getQuest(request.targetId()).isPresent()) {
                ValidationResult result = ValidationResult.valid();
                result.addError("QUEST_ALREADY_EXISTS", "Quest '" + request.targetId() + "' already exists");
                return result;
            }
            return createQuest(request.targetId(), title);
        });
    }

    /**
     * Persists a faction definition: validates, writes YAML atomically, then registers
     * it live. Canonical mutation path for `/storynpcs faction create/configure` —
     * mirrors {@link #saveQuest}.
     *
     * @return validation result; on errors nothing is written or registered.
     */
    public synchronized ValidationResult saveFaction(Faction faction) {
        Objects.requireNonNull(faction, "faction");
        ValidationResult result = ValidationResult.valid();

        if (faction.getId() == null) {
            result.addError("FACTION_ID_MISSING", "Faction definition must have an ID");
            return result;
        }
        if (faction.getHostileThreshold() >= faction.getFriendlyThreshold()) {
            result.addError("FACTION_THRESHOLDS_INCONSISTENT", String.format(
                    "Faction '%s': hostileThreshold (%d) must be below friendlyThreshold (%d)",
                    faction.getId(), faction.getHostileThreshold(), faction.getFriendlyThreshold()));
            return result;
        }

        DefinitionRegistry snapshot = new DefinitionRegistry();
        snapshot.copyFrom(registry);
        snapshot.registerFaction(faction);
        result.merge(CrossReferenceValidator.validate(snapshot));
        if (result.hasErrors()) {
            return result;
        }

        if (loader != null && loader.getLastLoadedRootPath() != null) {
            try {
                var savedFile = new YamlDefinitionWriter(loader.getDefinitionWriteCoordinator()).writeDefinition(
                        loader.getLastLoadedRootPath(), "factions",
                        YamlDefinitionWriter.fileNameFor(faction.getId()), faction,
                        loader.getDefinitionFiles("factions", faction.getId()));
                loader.recordDefinitionFile("factions", faction.getId(), savedFile);
            } catch (IOException e) {
                result.addError("PERSIST_WRITE_FAILED", "Failed to write faction file: " + e.getMessage());
                return result;
            }
        }

        registry.registerFaction(faction);
        definitionRevisions.merge(revisionKey("faction", faction.getId()), 1L, Long::sum);
        return result;
    }

    /** Applies a faction mutation to a detached copy and commits only after validation. */
    public ValidationResult mutateFaction(NamespacedId id, Consumer<Faction> mutation) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mutation, "mutation");
        MutationRequest request = new MutationRequest(
                "faction.mutate", "adapter", "faction.mutate", id,
                definitionRevisions.getOrDefault(revisionKey("faction", id), 0L), UUID.randomUUID());
        return mutateFaction(request, mutation).diagnostics();
    }

    /** Revision-checked callback mutation; same-ID retries fail closed because the callback has no payload intent. */
    public CanonicalMutationResult mutateFaction(MutationRequest request, Consumer<Faction> mutation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(mutation, "mutation");
        return executeCanonicalMutation(request, "faction", "mutate", () -> {
            Faction current = registry.getFaction(request.targetId())
                    .orElseThrow(() -> new NoSuchElementException("Faction not found: " + request.targetId()));
            Faction working = com.storynpcs.domain.faction.FactionSerde.fromJson(
                            com.storynpcs.domain.faction.FactionSerde.toJson(current))
                    .orElseThrow(() -> new IllegalStateException("Unable to copy faction for mutation: " + request.targetId()));
            mutation.accept(working);
            if (!request.targetId().equals(working.getId())) {
                ValidationResult result = ValidationResult.valid();
                result.addError("TARGET_ID_MISMATCH", "Faction mutation cannot change the request target ID");
                return result;
            }
            Faction committed = com.storynpcs.domain.faction.FactionSerde.fromJson(
                            com.storynpcs.domain.faction.FactionSerde.toJson(working))
                    .orElseThrow(() -> new IllegalStateException("Unable to detach faction mutation result: " + request.targetId()));
            return saveFaction(committed);
        });
    }

    /** Canonical full-definition replacement used by packet adapters after decoding detached input. */
    public CanonicalMutationResult replaceFaction(MutationRequest request, Faction replacement) {
        Objects.requireNonNull(replacement, "replacement");
        String payloadJson = com.storynpcs.domain.faction.FactionSerde.toJson(replacement);
        Faction payload = com.storynpcs.domain.faction.FactionSerde.fromJson(payloadJson)
                .orElseThrow(() -> new IllegalArgumentException("Unable to snapshot faction replacement payload"));
        return executeCanonicalMutation(request, "faction", "replace",
                MutationPayloadFingerprint.ofJson("faction.replace", payloadJson), () -> {
            if (!request.targetId().equals(payload.getId())) {
                ValidationResult result = ValidationResult.valid();
                result.addError("TARGET_ID_MISMATCH", "Replacement ID does not match request target");
                return result;
            }
            return saveFaction(payload);
        });
    }

    /**
     * Scaffolds a faction with domain defaults (defaultPoints 1000, hostile 500,
     * friendly 1500) and persists it. Canonical path for `/storynpcs faction create`.
     */
    public ValidationResult createFaction(NamespacedId id, String name) {
        Objects.requireNonNull(id, "id");
        if (registry.getFaction(id).isPresent()) {
            ValidationResult res = ValidationResult.valid();
            res.addError("FACTION_ALREADY_EXISTS", "Faction '" + id + "' already exists");
            return res;
        }
        String factionName = (name != null && !name.isBlank()) ? name.trim() : id.getPath();
        return saveFaction(new Faction(id, factionName, 1000, 500, 1500));
    }

    /** Creates a faction through the revisioned canonical request boundary. */
    public CanonicalMutationResult createFaction(MutationRequest request, String name) {
        Objects.requireNonNull(request, "request");
        return executeCanonicalMutation(request, "faction", "create",
                MutationPayloadFingerprint.of("faction.create.name", name), () -> {
            if (registry.getFaction(request.targetId()).isPresent()) {
                ValidationResult result = ValidationResult.valid();
                result.addError("FACTION_ALREADY_EXISTS", "Faction '" + request.targetId() + "' already exists");
                return result;
            }
            return createFaction(request.targetId(), name);
        });
    }

    /**
     * Removes a quest definition from the live registry and removes its YAML file from
     * disk. Mirrors {@link #deleteNpc}. Callers should check
     * {@link #findDialoguesStartingQuest(NamespacedId)} first — deleted quests leave
     * dangling START_QUEST actions in dialogue graphs.
     */
    public synchronized boolean deleteQuest(NamespacedId id) {
        Objects.requireNonNull(id, "id");
        if (registry.getQuest(id).isEmpty()) {
            return false;
        }
        if (!deleteDefinitionFileBeforeRegistryMutation("quest", id)) {
            return false;
        }
        registry.removeQuest(id);
        definitionRevisions.merge(revisionKey("quest", id), 1L, Long::sum);
        return true;
    }

    /** Replay-safe, revision-checked quest deletion for command/network adapters. */
    public CanonicalMutationResult deleteQuest(MutationRequest request) {
        return executeCanonicalMutation(request, "quest", "delete",
                MutationPayloadFingerprint.of("quest.delete", request.targetId().toString()), () -> {
            if (registry.getQuest(request.targetId()).isEmpty()) {
                ValidationResult result = ValidationResult.valid();
                result.addError("QUEST_NOT_FOUND", "Quest not found: " + request.targetId());
                return result;
            }
            if (!deleteQuest(request.targetId())) {
                ValidationResult failure = ValidationResult.valid();
                failure.addError(registry.getQuest(request.targetId()).isPresent()
                                ? "DEFINITION_DELETE_FAILED" : "QUEST_NOT_FOUND",
                        "Quest '" + request.targetId() + "' could not be deleted");
                return failure;
            }
            return ValidationResult.valid();
        });
    }

    /**
     * Finds all dialogue graphs that contain a START_QUEST action targeting the given
     * quest. Used by quest deletion to warn about dangling references.
     */
    public List<NamespacedId> findDialoguesStartingQuest(NamespacedId questId) {
        List<NamespacedId> referencing = new ArrayList<>();
        String target = questId.toString();
        for (DialogueGraph graph : registry.getAllDialogues()) {
            boolean refs = graph.getNodes().values().stream()
                    .flatMap(n -> n.getOptions().stream())
                    .flatMap(e -> e.getActions().stream())
                    .anyMatch(a -> a.getType() == DialogueAction.Type.START_QUEST
                            && target.equals(a.getTarget()));
            if (refs) {
                referencing.add(graph.getId());
            }
        }
        return referencing;
    }

    /**
     * Removes a faction definition from the live registry and removes its YAML file
     * from disk. Mirrors {@link #deleteNpc}. Callers should check
     * {@link #findNpcsReferencingFaction(NamespacedId)} first — NPCs bound to a
     * deleted faction lose their faction binding.
     */
    public synchronized boolean deleteFaction(NamespacedId id) {
        Objects.requireNonNull(id, "id");
        if (registry.getFaction(id).isEmpty()) {
            return false;
        }
        if (!deleteDefinitionFileBeforeRegistryMutation("faction", id)) {
            return false;
        }
        registry.removeFaction(id);
        definitionRevisions.merge(revisionKey("faction", id), 1L, Long::sum);
        return true;
    }

    /** Replay-safe, revision-checked faction deletion for command/network adapters. */
    public CanonicalMutationResult deleteFaction(MutationRequest request) {
        return executeCanonicalMutation(request, "faction", "delete",
                MutationPayloadFingerprint.of("faction.delete", request.targetId().toString()), () -> {
            if (registry.getFaction(request.targetId()).isEmpty()) {
                ValidationResult result = ValidationResult.valid();
                result.addError("FACTION_NOT_FOUND", "Faction not found: " + request.targetId());
                return result;
            }
            if (!deleteFaction(request.targetId())) {
                ValidationResult failure = ValidationResult.valid();
                failure.addError(registry.getFaction(request.targetId()).isPresent()
                                ? "DEFINITION_DELETE_FAILED" : "FACTION_NOT_FOUND",
                        "Faction '" + request.targetId() + "' could not be deleted");
                return failure;
            }
            return ValidationResult.valid();
        });
    }

    /**
     * Finds all NPCs bound to the given faction. Used by faction deletion to warn
     * about dangling faction bindings.
     */
    public List<NamespacedId> findNpcsReferencingFaction(NamespacedId factionId) {
        List<NamespacedId> referencing = new ArrayList<>();
        for (NpcDefinition npc : registry.getAllNpcs()) {
            if (factionId.equals(npc.getFactionId())) {
                referencing.add(npc.getId());
            }
        }
        return referencing;
    }

    /**
     * Scaffolds a valid starter dialogue graph, validates and persists it to YAML, and
     * registers it live in the registry. Canonical creation path for `/storynpcs dialogue create`.
     */
    public ValidationResult createDialogue(NamespacedId id, String title) {
        Objects.requireNonNull(id, "id");
        if (registry.getDialogue(id).isPresent()) {
            ValidationResult res = ValidationResult.valid();
            res.addError("DIALOGUE_ALREADY_EXISTS", "Dialogue '" + id + "' already exists");
            return res;
        }
        String dialogueTitle = (title != null && !title.isBlank()) ? title.trim() : id.getPath();
        DialogueGraph graph = new DialogueGraph(id, dialogueTitle, "start");
        DialogueNode startNode = new DialogueNode("start", "Hello, traveler!");
        graph.addNode(startNode);
        return saveDialogue(id, graph);
    }

    /** Creates a dialogue graph through the revisioned canonical request boundary. */
    public CanonicalMutationResult createDialogue(MutationRequest request, String title) {
        Objects.requireNonNull(request, "request");
        return executeCanonicalMutation(request, "dialogue", "create",
                MutationPayloadFingerprint.of("dialogue.create.title", title), () -> {
            if (registry.getDialogue(request.targetId()).isPresent()) {
                ValidationResult result = ValidationResult.valid();
                result.addError("DIALOGUE_ALREADY_EXISTS", "Dialogue '" + request.targetId() + "' already exists");
                return result;
            }
            return createDialogue(request.targetId(), title);
        });
    }

    /** Creates a caller-authored graph without exposing an intermediate scaffold state. */
    public CanonicalMutationResult createDialogue(MutationRequest request, DialogueGraph graph) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(graph, "graph");
        String payloadJson = DialogueGraphSerde.toJson(graph);
        DialogueGraph payload = DialogueGraphSerde.fromJson(payloadJson)
                .orElseThrow(() -> new IllegalArgumentException("Unable to snapshot dialogue create payload"));
        return executeCanonicalMutation(request, "dialogue", "create",
                MutationPayloadFingerprint.ofJson("dialogue.create.graph", payloadJson), () -> {
            if (!request.targetId().equals(payload.getId())) {
                ValidationResult result = ValidationResult.valid();
                result.addError("TARGET_ID_MISMATCH", "Dialogue ID does not match request target");
                return result;
            }
            if (registry.getDialogue(request.targetId()).isPresent()) {
                ValidationResult result = ValidationResult.valid();
                result.addError("DIALOGUE_ALREADY_EXISTS", "Dialogue '" + request.targetId() + "' already exists");
                return result;
            }
            return saveDialogue(request.targetId(), payload);
        });
    }

    /**
     * Removes a dialogue definition from the live registry and removes its YAML file from disk.
     * Mirrors {@link #deleteNpc}.
     */
    public synchronized boolean deleteDialogue(NamespacedId id) {
        Objects.requireNonNull(id, "id");
        if (registry.getDialogue(id).isEmpty()) {
            return false;
        }
        if (!deleteDefinitionFileBeforeRegistryMutation("dialogue", id)) {
            return false;
        }
        registry.removeDialogue(id);
        definitionRevisions.merge(revisionKey("dialogue", id), 1L, Long::sum);
        return true;
    }

    /** Replay-safe, revision-checked dialogue deletion for command/network adapters. */
    public CanonicalMutationResult deleteDialogue(MutationRequest request) {
        return executeCanonicalMutation(request, "dialogue", "delete",
                MutationPayloadFingerprint.of("dialogue.delete", request.targetId().toString()), () -> {
            if (registry.getDialogue(request.targetId()).isEmpty()) {
                ValidationResult result = ValidationResult.valid();
                result.addError("DIALOGUE_NOT_FOUND", "Dialogue not found: " + request.targetId());
                return result;
            }
            if (!deleteDialogue(request.targetId())) {
                ValidationResult failure = ValidationResult.valid();
                failure.addError(registry.getDialogue(request.targetId()).isPresent()
                                ? "DEFINITION_DELETE_FAILED" : "DIALOGUE_NOT_FOUND",
                        "Dialogue '" + request.targetId() + "' could not be deleted");
                return failure;
            }
            return ValidationResult.valid();
        });
    }

    /**
     * Compensates an unreferenced dialogue creation through the canonical delete boundary.
     * The reference check and delete share the canonical mutation lock so another typed
     * definition mutation cannot attach the graph between those two steps.
     */
    public CanonicalMutationResult deleteUnreferencedDialogue(MutationRequest request) {
        Objects.requireNonNull(request, "request");
        return executeCanonicalMutation(request, "dialogue", "delete",
                MutationPayloadFingerprint.of("dialogue.delete.unreferenced", request.targetId().toString()), () -> {
            synchronized (this) {
                if (registry.getDialogue(request.targetId()).isEmpty()) {
                    ValidationResult result = ValidationResult.valid();
                    result.addError("DIALOGUE_NOT_FOUND", "Dialogue not found: " + request.targetId());
                    return result;
                }
                List<NamespacedId> references = findNpcsReferencingDialogue(request.targetId());
                if (!references.isEmpty()) {
                    ValidationResult result = ValidationResult.valid();
                    result.addError("DIALOGUE_REFERENCED",
                            "Dialogue '" + request.targetId() + "' is still referenced by NPCs: " + references);
                    return result;
                }
                if (!deleteDialogue(request.targetId())) {
                    ValidationResult result = ValidationResult.valid();
                    result.addError("DEFINITION_DELETE_FAILED",
                            "Unreferenced dialogue '" + request.targetId() + "' could not be removed");
                    return result;
                }
            }
            return ValidationResult.valid();
        });
    }

    /**
     * Finds all NPCs in the registry that reference a given dialogue ID.
     * Used by deletion and info safety checks to prevent broken/dangling dialogue references.
     */
    public List<NamespacedId> findNpcsReferencingDialogue(NamespacedId dialogueId) {
        if (dialogueId == null) return List.of();
        return registry.getAllNpcs().stream()
                .filter(npc -> dialogueId.equals(npc.getDialogueId()))
                .map(NpcDefinition::getId)
                .toList();
    }


    /**
     * Resolves the speaking NPC's display name from its entity UUID (null-safe —
     * command-started dialogues and unit tests simply return null).
     */
    private String resolveNpcDisplayName(UUID npcEntityUuid) {
        if (npcEntityUuid == null || minecraftServer == null) {
            return null;
        }
        try {
            for (var level : minecraftServer.getAllLevels()) {
                var entity = level.getEntity(npcEntityUuid);
                if (entity instanceof com.storynpcs.entity.StoryNpcEntity npc) {
                    return npc.getDefinition()
                            .map(def -> def.getDisplay() != null ? def.getDisplay().getName() : null)
                            .orElse(null);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Best available speaker label: per-node override first, then NPC display
     * name, then the dialogue title.
     */
    private String speakerLabel(DialogueSession session) {
        DialogueNode node = session.getCurrentNode();
        if (node != null && node.getSpeaker() != null && !node.getSpeaker().isBlank()) {
            return node.getSpeaker();
        }
        if (session.getNpcDisplayName() != null && !session.getNpcDisplayName().isBlank()) {
            return session.getNpcDisplayName();
        }
        String title = session.getGraph().getTitle();
        return title != null ? title : "";
    }

    /**
     * Short player-facing summary of an option's consequences, e.g. "Quest: Bounty" or
     * "Reputation". Keeps choices informed without leaking implementation detail.
     */
    private String optionHint(DialogueEdge edge) {
        if (edge.getActions() == null || edge.getActions().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (DialogueAction action : edge.getActions()) {
            if (action == null || action.getType() == null) continue;
            String label = switch (action.getType()) {
                case START_QUEST -> questTitle(action.getTarget());
                case ADVANCE_QUEST -> "Progress quest";
                case COMPLETE_QUEST -> "Complete quest";
                case ADJUST_FACTION -> factionHint(action);
                case GIVE_ITEM -> "Item: " + itemName(action.getTarget());
                case EXECUTE_COMMAND -> "Command";
                case CLOSE_DIALOGUE -> null;
            };
            if (label != null && !parts.contains(label)) {
                parts.add(label);
            }
        }
        return String.join(", ", parts);
    }

    /** e.g. "Kingdom +50" / "Kingdom -20" — players see reputation shifts before choosing. */
    private String factionHint(DialogueAction action) {
        String name;
        try {
            NamespacedId fid = NamespacedId.of(action.getTarget());
            name = registry.getFaction(fid)
                    .map(f -> f.getName() != null && !f.getName().isBlank() ? f.getName() : fid.getPath())
                    .orElse(fid.getPath());
        } catch (Exception e) {
            name = action.getTarget();
        }
        String delta = action.getValue() != null ? action.getValue().trim() : "";
        if (!delta.isEmpty() && !delta.startsWith("-") && !delta.startsWith("+")) {
            delta = "+" + delta;
        }
        return "Reputation: " + name + (delta.isEmpty() ? "" : " " + delta);
    }

    /** Human-readable item name from a namespaced id, e.g. "minecraft:golden_apple" → "golden apple". */
    private String itemName(String target) {
        if (target == null || target.isBlank()) return "?";
        String path = target.contains(":") ? target.substring(target.indexOf(':') + 1) : target;
        return path.replace('_', ' ');
    }

    private String questTitle(String target) {
        try {
            NamespacedId qid = NamespacedId.of(target);
            return registry.getQuest(qid)
                    .map(q -> "Quest: " + (q.getTitle() != null && !q.getTitle().isBlank() ? q.getTitle() : qid.getPath()))
                    .orElse("Quest");
        } catch (Exception e) {
            return "Quest";
        }
    }

    private DialogueView buildDialogueView(DialogueSession session, PlayerProgression progression) {
        String speaker = speakerLabel(session);
        DialogueNode node = session.getCurrentNode();
        if (node == null || node.isTerminal()) {
            session.close();
            activeSessions.remove(session.getPlayerUuid());
            return new DialogueView(session.getDialogueId(), node != null ? node.getId() : "",
                    node != null ? node.getText() : "", node != null ? node.getSound() : "", List.of(), true,
                    speaker, List.of());
        }

        // VULN-46: pass session so onceOnly options are filtered after first selection
        List<DialogueEdge> availableEdges = getAvailableEdges(node, progression, session);
        List<String> optionTexts = availableEdges.stream().map(DialogueEdge::getText).toList();
        List<String> optionHints = availableEdges.stream().map(this::optionHint).toList();

        return new DialogueView(session.getDialogueId(), node.getId(), node.getText(), node.getSound(),
                optionTexts, false, speaker, optionHints);
    }

    private List<DialogueEdge> getAvailableEdges(DialogueNode node, PlayerProgression progression, DialogueSession session) {
        if (node.getOptions() == null) return List.of();
        List<DialogueEdge> result = new ArrayList<>();
        for (DialogueEdge edge : node.getOptions()) {
            // VULN-46: skip edges marked onceOnly that this session has already traversed
            if (edge.isOnceOnly() && session != null
                    && session.hasSelectedOption(node.getId() + "->" + edge.getTargetNodeId())) {
                continue;
            }
            if (evalConditions(edge.getConditions(), progression, session)) {
                result.add(edge);
            }
        }
        return result;
    }

    private boolean evalConditions(List<DialogueCondition> conditions, PlayerProgression progression, DialogueSession session) {
        if (conditions == null || conditions.isEmpty()) return true;
        for (DialogueCondition cond : conditions) {
            if (!evalCondition(cond, progression)) return false;
        }
        return true;
    }

    private boolean evalCondition(DialogueCondition cond, PlayerProgression progression) {
        if (cond == null || cond.getType() == null) return true;
        try {
            switch (cond.getType()) {
                case QUEST_STATUS -> {
                    NamespacedId qid = NamespacedId.of(cond.getTarget());
                    QuestProgressState state = progression.getQuestState(qid);
                    return state.getStatus().name().equalsIgnoreCase(cond.getValue());
                }
                case FACTION_STANDING -> {
                    NamespacedId fid = NamespacedId.of(cond.getTarget());
                    Faction faction = registry.getFaction(fid).orElse(null);
                    if (faction == null) return false;
                    int pts = progression.getFactionScore(fid, faction.getDefaultPoints());
                    return faction.getStandingForPoints(pts).name().equalsIgnoreCase(cond.getValue());
                }
                case FACTION_POINTS -> {
                    NamespacedId fid = NamespacedId.of(cond.getTarget());
                    Faction faction = registry.getFaction(fid).orElse(null);
                    int defPts = faction != null ? faction.getDefaultPoints() : 1000;
                    int pts = progression.getFactionScore(fid, defPts);
                    int targetVal = Integer.parseInt(cond.getValue() != null ? cond.getValue().trim() : "0");
                    return switch (cond.getOperator()) {
                        case ">=" -> pts >= targetVal;
                        case "<=" -> pts <= targetVal;
                        case ">" -> pts > targetVal;
                        case "<" -> pts < targetVal;
                        default -> pts == targetVal;
                    };
                }
                case HAS_ITEM -> {
                    // VULN-45: Check live player inventory for the required item and count
                    if (minecraftServer == null) {
                        System.err.println("[StoryNPCs] HAS_ITEM condition cannot be evaluated — server reference not available");
                        return false; // fail closed
                    }
                    net.minecraft.server.level.ServerPlayer player = minecraftServer.getPlayerList()
                            .getPlayer(progression.getPlayerUuid());
                    if (player == null) return false; // player not online
                    int required = 1;
                    try { required = Math.max(1, Integer.parseInt(cond.getValue().trim())); } catch (Exception ignored) {}
                    net.minecraft.resources.ResourceLocation itemRl = net.minecraft.resources.ResourceLocation.tryParse(cond.getTarget());
                    if (itemRl == null) return false;
                    var itemOpt = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(itemRl);
                    if (itemOpt.isEmpty()) return false;
                    final int req = required;
                    final net.minecraft.world.item.Item targetItem = itemOpt.get();
                    int count = player.getInventory().items.stream()
                            .filter(s -> !s.isEmpty() && s.getItem() == targetItem)
                            .mapToInt(net.minecraft.world.item.ItemStack::getCount)
                            .sum();
                    return count >= req;
                }
                case HAS_PERMISSION -> {
                    // VULN-45: Check player permission level (op level 2+) or NeoForge permission node
                    if (minecraftServer == null) {
                        System.err.println("[StoryNPCs] HAS_PERMISSION condition cannot be evaluated — server reference not available");
                        return false; // fail closed
                    }
                    net.minecraft.server.level.ServerPlayer player = minecraftServer.getPlayerList()
                            .getPlayer(progression.getPlayerUuid());
                    if (player == null) return false;
                    String permTarget = cond.getTarget() != null ? cond.getTarget().trim() : "";
                    if (permTarget.isEmpty()) return false;
                    // Support numeric OP-level targets ("2", "4") and string permission nodes
                    try {
                        int level = Integer.parseInt(permTarget);
                        return player.hasPermissions(level);
                    } catch (NumberFormatException e) {
                        // Treat as a NeoForge permission node name ("modid.node.path" dot-form).
                        // Nodes must have been registered via PermissionGatherEvent.Nodes — querying an
                        // unregistered node throws, so look it up in the registered set first.
                        for (var node : net.neoforged.neoforge.server.permission.PermissionAPI.getRegisteredNodes()) {
                            if (node.getNodeName().equals(permTarget)
                                    && node.getType() == net.neoforged.neoforge.server.permission.nodes.PermissionTypes.BOOLEAN) {
                                @SuppressWarnings("unchecked")
                                var boolNode = (net.neoforged.neoforge.server.permission.nodes.PermissionNode<Boolean>) node;
                                return Boolean.TRUE.equals(
                                        net.neoforged.neoforge.server.permission.PermissionAPI.getPermission(player, boolNode));
                            }
                        }
                        return false; // unregistered node — fail closed
                    }
                }
                default -> {
                    // VULN-45: unknown condition types default to false (fail closed) to prevent bypass
                    System.err.println("[StoryNPCs] Unknown dialogue condition type: " + cond.getType() + " — defaulting to false");
                    return false;
                }
            }
        } catch (Exception e) {
            System.err.println("Error evaluating dialogue condition " + cond.getType() + ": " + e.getMessage());
            return false;
        }
    }

    private void executeAction(UUID playerUuid, DialogueAction action) {
        if (action == null || action.getType() == null) return;
        try {
            switch (action.getType()) {
                case START_QUEST -> {
                    NamespacedId qid = NamespacedId.of(action.getTarget());
                    if (registry.getQuest(qid).isPresent()) {
                        startQuest(playerUuid, qid);
                    } else {
                        System.err.println("Warning: Dialogue action START_QUEST references unknown quest: " + action.getTarget());
                    }
                }
                case ADVANCE_QUEST -> {
                    NamespacedId qid = NamespacedId.of(action.getTarget());
                    if (registry.getQuest(qid).isPresent()) {
                        String obj = (action.getValue() != null && !action.getValue().isBlank()) ? action.getValue().trim() : "obj";
                        progressQuest(playerUuid, qid, obj, 1);
                    }
                }
                case COMPLETE_QUEST -> {
                    NamespacedId qid = NamespacedId.of(action.getTarget());
                    if (registry.getQuest(qid).isPresent()) {
                        completeQuest(playerUuid, qid);
                    } else {
                        System.err.println("Warning: Dialogue action COMPLETE_QUEST references unknown quest: " + action.getTarget());
                    }
                }
                case ADJUST_FACTION -> {
                    NamespacedId fid = NamespacedId.of(action.getTarget());
                    if (registry.getFaction(fid).isPresent()) {
                        int rawDelta = Integer.parseInt(action.getValue() != null ? action.getValue().trim() : "0");
                        // Clamp to the typed request's accepted range up front — the old untyped
                        // adjustFactionPoints() silently clamped the resulting sum instead of
                        // rejecting an out-of-range authored delta; preserve that tolerance here
                        // rather than let an author-supplied huge value throw mid-dialogue.
                        int delta = Math.max(-100_000, Math.min(100_000, rawDelta));
                        mutateFactionProgression(FactionProgressionMutationRequest.adjust("dialogue", playerUuid,
                                playerUuid, fid, delta, currentFactionProgressionRevision(playerUuid),
                                UUID.randomUUID(), -1));
                    } else {
                        System.err.println("Warning: Dialogue action ADJUST_FACTION references unknown faction: " + action.getTarget());
                    }
                }
                case GIVE_ITEM -> {
                    // VULN-47: give item to player inventory; drop at feet if inventory full
                    if (minecraftServer == null) {
                        System.err.println("[StoryNPCs] GIVE_ITEM cannot execute — server reference not available");
                        break;
                    }
                    net.minecraft.server.level.ServerPlayer player = minecraftServer.getPlayerList().getPlayer(playerUuid);
                    if (player == null) break;
                    net.minecraft.resources.ResourceLocation itemRl = net.minecraft.resources.ResourceLocation.tryParse(
                            action.getTarget() != null ? action.getTarget().trim() : "");
                    if (itemRl == null) { System.err.println("[StoryNPCs] GIVE_ITEM: invalid item id: " + action.getTarget()); break; }
                    var itemOpt = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(itemRl);
                    if (itemOpt.isEmpty()) { System.err.println("[StoryNPCs] GIVE_ITEM: unknown item: " + itemRl); break; }
                    int amount = 1;
                    try { amount = Math.max(1, Integer.parseInt(action.getValue().trim())); } catch (Exception ignored) {}
                    net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(itemOpt.get(), amount);
                    if (!player.getInventory().add(stack)) {
                        // Inventory full — drop at player's feet
                        player.drop(stack, false);
                    }
                }
                case EXECUTE_COMMAND -> {
                    // VULN-47: execute server command; sanitize %player% to prevent injection
                    if (minecraftServer == null) {
                        System.err.println("[StoryNPCs] EXECUTE_COMMAND cannot execute — server reference not available");
                        break;
                    }
                    net.minecraft.server.level.ServerPlayer player = minecraftServer.getPlayerList().getPlayer(playerUuid);
                    String cmd = action.getTarget() != null ? action.getTarget().trim() : "";
                    if (cmd.isEmpty()) break;
                    if (player != null) {
                        // Sanitize player name — only [a-zA-Z0-9_]{2,16} allowed to prevent injection
                        String safeName = player.getScoreboardName().replaceAll("[^a-zA-Z0-9_]", "");
                        if (safeName.length() < 2 || safeName.length() > 16) safeName = "unknown";
                        cmd = cmd.replace("%player%", safeName);
                    }
                    // Strip leading slash if present (runCommand expects no leading slash)
                    if (cmd.startsWith("/")) cmd = cmd.substring(1);
                    minecraftServer.getCommands().performPrefixedCommand(
                            minecraftServer.createCommandSourceStack().withSuppressedOutput().withMaximumPermission(4),
                            cmd
                    );
                }
                case CLOSE_DIALOGUE -> closeDialogue(playerUuid);
                default -> System.err.println("[StoryNPCs] Unhandled dialogue action type: " + action.getType());
            }
        } catch (Exception e) {
            System.err.println("Failed to execute dialogue action " + action.getType() + " for player " + playerUuid + ": " + e.getMessage());
        }
    }

    private void saveProgression(UUID playerUuid) {
        try {
            progressionRepository.save(playerUuid);
        } catch (IOException e) {
            System.err.println("Failed to persist progression for " + playerUuid + ": " + e.getMessage());
        }
    }

    private void saveProgression(UUID playerUuid, PlayerProgression progression) {
        try {
            progressionRepository.save(playerUuid, progression);
        } catch (IOException e) {
            System.err.println("Failed to persist progression for " + playerUuid + ": " + e.getMessage());
        }
    }

    // ==========================================
    // 3. Faction Operations
    // ==========================================

    public void setFactionPoints(UUID playerUuid, NamespacedId factionId, int points) {
        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        Faction faction = registry.getFaction(factionId)
                .orElseThrow(() -> new NoSuchElementException("Faction not found: " + factionId));
        int clamped = Math.max(-100_000, Math.min(100_000, points));
        final int oldPoints;
        // Mutate and commit the SAME instance under its monitor — a cache
        // eviction must never turn this write into a silent no-op.
        synchronized (progression) {
            oldPoints = progression.getFactionScore(factionId, faction.getDefaultPoints());
            progression.setFactionScore(factionId, clamped);
            saveProgression(playerUuid, progression);
        }

        eventPublisher.publish(new FactionReputationChangeEvent(playerUuid, factionId, oldPoints, clamped));
    }

    public void adjustFactionPoints(UUID playerUuid, NamespacedId factionId, int delta) {
        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        Faction faction = registry.getFaction(factionId)
                .orElseThrow(() -> new NoSuchElementException("Faction not found: " + factionId));
        final int oldPoints;
        final int newPoints;
        // Read-modify-write inside the monitor — a concurrent reputation change
        // must not be lost between the delta base read and the committed write.
        synchronized (progression) {
            oldPoints = progression.getFactionScore(factionId, faction.getDefaultPoints());
            long sum = (long) oldPoints + (long) delta;
            newPoints = (int) Math.max(-100_000, Math.min(100_000, sum));
            progression.setFactionScore(factionId, newPoints);
            saveProgression(playerUuid, progression);
        }

        eventPublisher.publish(new FactionReputationChangeEvent(playerUuid, factionId, oldPoints, newPoints));
    }

    /**
     * Applies one typed, player-scoped, revision-checked, replay-safe faction reputation
     * mutation. This is the canonical entry point for command/packet/API adapters; the
     * untyped {@link #setFactionPoints} / {@link #adjustFactionPoints} above remain for
     * internal callers (e.g. quest-completion reward commits) that are already inside
     * their own atomic, locked, revisioned transaction.
     */
    public CanonicalMutationResult mutateFactionProgression(FactionProgressionMutationRequest request) {
        Objects.requireNonNull(request, "request");
        AuthorizationDecision authorization = AuthorizationPolicy.evaluate(request);
        String fingerprint = factionMutationFingerprint(request);
        Object playerLock = factionProgressionMutationLocks[request.playerUuid().hashCode()
                & (factionProgressionMutationLocks.length - 1)];
        if (!authorization.allowed()) {
            CanonicalMutationResult denied = factionMutationFailure(0, authorization.code(), authorization.message());
            eventPublisher.publish(factionMutationEvent(request, denied));
            return denied;
        }

        Object requestLock = factionMutationLocks[request.requestId().hashCode() & (factionMutationLocks.length - 1)];
        CanonicalMutationResult result;
        FactionReputationChangeEvent reputationEvent = null;
        synchronized (requestLock) {
            synchronized (playerLock) {
                PlayerProgression progression;
                try {
                    progression = progressionRepository.getOrCreate(request.playerUuid());
                } catch (RuntimeException unavailable) {
                    System.err.println("[StoryNPCs] faction mutation blocked for " + request.playerUuid()
                            + ": " + unavailable.getMessage());
                    progression = null;
                }
                if (progression == null) {
                    // Fail closed: an unrecoverable durable record must reject every
                    // mutation instead of silently initializing empty progression.
                    result = factionMutationFailure(0, "PROGRESSION_UNAVAILABLE",
                            "Player progression is blocked pending durable-state recovery");
                } else
                synchronized (progression) {
                    CompletedFactionMutation completed = completedFactionMutations.get(request.requestId());
                    if (completed != null) {
                        if (!completed.payloadFingerprint().equals(fingerprint)) {
                            result = factionMutationFailure(progression.getFactionRevision(),
                                    "REQUEST_PAYLOAD_MISMATCH", "Request ID is already bound to a different faction mutation payload");
                        } else {
                            CanonicalMutationResult prior = completed.result();
                            result = new CanonicalMutationResult(prior.applied(), true, prior.revision(),
                                    prior.diagnostics(), prior.events(), prior.recoveryOutcome());
                        }
                    } else {
                        Faction faction = registry.getFaction(request.factionId()).orElse(null);
                        long currentRevision = progression.getFactionRevision();
                        if (faction == null) {
                            result = factionMutationFailure(currentRevision, "FACTION_NOT_FOUND",
                                    "Faction not found: " + request.factionId());
                            rememberFactionMutation(request, fingerprint, result);
                        } else if (currentRevision != request.expectedRevision()) {
                            result = factionMutationFailure(currentRevision, "STALE_REVISION",
                                    "Expected player progression revision " + request.expectedRevision()
                                            + " but current revision is " + currentRevision);
                            rememberFactionMutation(request, fingerprint, result);
                        } else {
                            PlayerProgression snapshot = progression.copy();
                            try {
                                int oldPoints = progression.getFactionScore(request.factionId(), faction.getDefaultPoints());
                                if (request.action() == FactionProgressionMutationRequest.Action.SET) {
                                    progression.setFactionScore(request.factionId(), request.value());
                                } else {
                                    progression.adjustFactionScore(request.factionId(), request.value(), faction.getDefaultPoints());
                                }
                                int newPoints = progression.getFactionScore(request.factionId(), faction.getDefaultPoints());
                                progression.setFactionRevision(Math.addExact(currentRevision, 1L));
                                progressionRepository.save(request.playerUuid(), progression);
                                result = new CanonicalMutationResult(true, false, progression.getFactionRevision(),
                                        ValidationResult.valid(), List.of("FactionReputationChangeEvent"), "COMMITTED");
                                reputationEvent = new FactionReputationChangeEvent(
                                        request.playerUuid(), request.factionId(), oldPoints, newPoints);
                            } catch (Exception failure) {
                                progression.restoreFrom(snapshot);
                                result = factionMutationFailure(currentRevision, "PROGRESSION_COMMIT_FAILED",
                                        "Could not durably save faction progression: " + failure.getMessage());
                            }
                            rememberFactionMutation(request, fingerprint, result);
                        }
                    }
                }
            }
        }

        // A cache-hit replay must not re-publish the canonical audit event or the
        // reputation-change event a second time for the same request ID.
        if (!result.duplicate()) eventPublisher.publish(factionMutationEvent(request, result));
        if (reputationEvent != null) eventPublisher.publish(reputationEvent);
        return result;
    }

    private void rememberFactionMutation(FactionProgressionMutationRequest request, String fingerprint,
                                          CanonicalMutationResult result) {
        completedFactionMutations.put(request.requestId(), new CompletedFactionMutation(fingerprint, result.snapshot()));
        while (completedFactionMutations.size() > 4096) {
            UUID oldest = completedFactionMutations.keySet().iterator().next();
            completedFactionMutations.remove(oldest);
        }
    }

    private static CanonicalMutationResult factionMutationFailure(long revision, String code, String message) {
        ValidationResult diagnostics = ValidationResult.valid();
        diagnostics.addError(code, message == null || message.isBlank() ? code : message);
        return new CanonicalMutationResult(false, false, revision, diagnostics, List.of(),
                Set.of("PERMISSION_DENIED", "PLAYER_SUBJECT_MISMATCH", "SCRIPT_CAPABILITY_REQUIRED").contains(code)
                        ? "REJECTED_AUTHORIZATION" : "REJECTED_NO_SIDE_EFFECTS");
    }

    private static String factionMutationFingerprint(FactionProgressionMutationRequest request) {
        String payload = String.join("\u0000", request.actorType(),
                request.actorId() == null ? "" : request.actorId().toString(),
                request.playerUuid().toString(), request.factionId().toString(), request.action().name(),
                Integer.toString(request.value()), Long.toString(request.expectedRevision()));
        return MutationPayloadFingerprint.of(request.operation(), payload);
    }

    private CanonicalMutationEvent factionMutationEvent(
            FactionProgressionMutationRequest request, CanonicalMutationResult result) {
        return new CanonicalMutationEvent(request.operation(), request.actorType(), request.factionId(),
                request.requestId(), result.applied(), result.revision(), result.recoveryOutcome(),
                request.actorId(), request.playerUuid());
    }

    public long currentFactionProgressionRevision(UUID playerUuid) {
        UUID subject = Objects.requireNonNull(playerUuid, "playerUuid");
        Object playerLock = factionProgressionMutationLocks[subject.hashCode() & (factionProgressionMutationLocks.length - 1)];
        synchronized (playerLock) {
            PlayerProgression progression = progressionRepository.getOrCreate(subject);
            synchronized (progression) {
                return progression.getFactionRevision();
            }
        }
    }

    // ==========================================
    // Transport Location Operations (issue #72 — transport locations foundation)
    // ==========================================
    //
    // Scope boundary: this defines and validates the destination CONTRACT and
    // per-player UNLOCK STATE only. Executing a live, safe teleport (loaded-chunk
    // check, non-obstructed landing, cross-dimension timeout/recovery) requires a
    // real ServerLevel and is intentionally NOT implemented here.

    /** Creates a transport location definition after validating its destination contract. */
    public ValidationResult createTransportLocation(
            com.storynpcs.domain.transport.TransportLocation location) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(location.getId(), "location.id");
        ValidationResult result = ValidationResult.valid();
        if (registry.getTransportLocation(location.getId()).isPresent()) {
            result.addError("TRANSPORT_LOCATION_ALREADY_EXISTS",
                    "Transport location '" + location.getId() + "' already exists");
            return result;
        }
        for (String error : location.validateDestinationContract()) {
            result.addError("TRANSPORT_LOCATION_INVALID", error);
        }
        if (result.hasErrors()) return result;
        registry.registerTransportLocation(location);
        return result;
    }

    /** All defined transport locations, regardless of unlock state. */
    public java.util.Collection<com.storynpcs.domain.transport.TransportLocation> getAllTransportLocations() {
        return registry.getAllTransportLocations();
    }

    /**
     * Locations currently selectable by this player: every location that does not
     * require an unlock, plus every location this player has unlocked.
     */
    public java.util.List<com.storynpcs.domain.transport.TransportLocation> listAvailableTransportLocations(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        java.util.Set<NamespacedId> unlocked;
        synchronized (progression) {
            unlocked = new java.util.HashSet<>(progression.getUnlockedTransportLocations());
        }
        java.util.List<com.storynpcs.domain.transport.TransportLocation> available = new ArrayList<>();
        for (var location : registry.getAllTransportLocations()) {
            if (!location.isRequiresUnlock() || unlocked.contains(location.getId())) {
                available.add(location);
            }
        }
        return available;
    }

    public boolean isTransportLocationUnlocked(UUID playerUuid, NamespacedId locationId) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(locationId, "locationId");
        var location = registry.getTransportLocation(locationId).orElse(null);
        if (location == null) return false;
        if (!location.isRequiresUnlock()) return true;
        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        synchronized (progression) {
            return progression.getUnlockedTransportLocations().contains(locationId);
        }
    }

    /** Unlocks a transport location for a player. Idempotent — unlocking twice is a no-op. Fails if the location doesn't exist. */
    public boolean unlockTransportLocation(UUID playerUuid, NamespacedId locationId) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(locationId, "locationId");
        if (registry.getTransportLocation(locationId).isEmpty()) return false;
        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        synchronized (progression) {
            boolean added = progression.getUnlockedTransportLocations().add(locationId);
            if (added) saveProgression(playerUuid, progression);
        }
        return true;
    }

    // ==========================================
    // Mail Operations (issue #71 — postman/mailbox role foundation)
    // ==========================================

    private static final int MAIL_SUBJECT_MAX_LENGTH = 128;
    private static final int MAIL_BODY_MAX_LENGTH = 2048;
    private static final int MAIL_SENDER_MAX_LENGTH = 128;
    private static final int MAILBOX_MAX_MESSAGES = 256;

    /**
     * Delivers a durable mail message to a player's mailbox, evicting the oldest
     * message first if the mailbox is at capacity. Returns the delivered message
     * (with its generated ID) so the caller can reference it.
     */
    public com.storynpcs.domain.progression.MailMessage deliverMail(
            UUID playerUuid, String sender, String subject, String body) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        String boundedSender = bound(sender, MAIL_SENDER_MAX_LENGTH, "mail sender");
        String boundedSubject = bound(subject, MAIL_SUBJECT_MAX_LENGTH, "mail subject");
        String boundedBody = bound(body, MAIL_BODY_MAX_LENGTH, "mail body");

        com.storynpcs.domain.progression.MailMessage message = new com.storynpcs.domain.progression.MailMessage(
                UUID.randomUUID(), boundedSender, boundedSubject, boundedBody, System.currentTimeMillis());

        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        synchronized (progression) {
            List<com.storynpcs.domain.progression.MailMessage> mailbox = progression.getMailbox();
            mailbox.add(message);
            while (mailbox.size() > MAILBOX_MAX_MESSAGES) {
                mailbox.remove(0);
            }
            saveProgression(playerUuid, progression);
        }
        return message;
    }

    /** Read-only snapshot of a player's mailbox, newest-last. */
    public List<com.storynpcs.domain.progression.MailMessage> getMailbox(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        synchronized (progression) {
            List<com.storynpcs.domain.progression.MailMessage> copy = new ArrayList<>();
            for (var m : progression.getMailbox()) copy.add(m.copy());
            return copy;
        }
    }

    /** Marks a mail message read. Returns false if no message with that ID exists. */
    public boolean markMailRead(UUID playerUuid, UUID mailId) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(mailId, "mailId");
        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        synchronized (progression) {
            for (var m : progression.getMailbox()) {
                if (m.getId().equals(mailId)) {
                    if (m.isRead()) return true; // idempotent no-op
                    m.setRead(true);
                    saveProgression(playerUuid, progression);
                    return true;
                }
            }
            return false;
        }
    }

    /** Deletes a mail message. Returns false if no message with that ID exists. */
    public boolean deleteMail(UUID playerUuid, UUID mailId) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(mailId, "mailId");
        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        synchronized (progression) {
            boolean removed = progression.getMailbox().removeIf(m -> m.getId().equals(mailId));
            if (removed) saveProgression(playerUuid, progression);
            return removed;
        }
    }

    private static String bound(String raw, int maxLength, String label) {
        String value = raw == null ? "" : raw;
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(label + " cannot contain a null character");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(label + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    // ==========================================
    // 4. Quest Operations
    // ==========================================

    /** Applies one typed, player-scoped quest start/progress operation. */
    public CanonicalMutationResult mutateQuestProgression(QuestProgressionMutationRequest request) {
        Objects.requireNonNull(request, "request");
        AuthorizationDecision authorization = AuthorizationPolicy.evaluate(request);
        String fingerprint = questMutationFingerprint(request);
        Object playerLock = progressionMutationLocks[request.playerUuid().hashCode()
                & (progressionMutationLocks.length - 1)];
        if (!authorization.allowed()) {
            CanonicalMutationResult denied = questMutationFailure(0, authorization.code(), authorization.message());
            QuestEventQueue dispatchQueue;
            synchronized (playerLock) {
                dispatchQueue = enqueueQuestEvents(request.playerUuid(), List.of(questMutationEvent(request, denied)));
            }
            if (dispatchQueue != null) drainQuestEvents(dispatchQueue);
            return denied;
        }

        Object requestLock = questMutationLocks[request.requestId().hashCode() & (questMutationLocks.length - 1)];
        QuestMutationExecution execution;
        CanonicalMutationResult result;
        List<StoryNpcsEvent> notifications = new ArrayList<>();
        QuestEventQueue dispatchQueue;
        synchronized (requestLock) {
            synchronized (playerLock) {
                PlayerProgression progression;
                try {
                    progression = progressionRepository.getOrCreate(request.playerUuid());
                } catch (RuntimeException unavailable) {
                    System.err.println("[StoryNPCs] quest mutation blocked for " + request.playerUuid()
                            + ": " + unavailable.getMessage());
                    progression = null;
                }
                if (progression == null) {
                    // Fail closed: an unrecoverable durable record must reject every
                    // mutation instead of silently initializing empty progression.
                    result = questMutationFailure(0, "PROGRESSION_UNAVAILABLE",
                            "Player progression is blocked pending durable-state recovery");
                    notifications.add(questMutationEvent(request, result));
                } else
                synchronized (progression) {
                    execution = applyQuestProgressionMutation(request, fingerprint, progression);
                    result = execution.result();
                    boolean publishCanonical = !result.duplicate();
                    if (result.newlyApplied()) {
                        if (request.action() == QuestProgressionMutationRequest.Action.START) {
                            notifications.add(new QuestStartEvent(request.playerUuid(), request.questId()));
                        } else {
                            notifications.add(new QuestObjectiveProgressEvent(request.playerUuid(), request.questId(),
                                    request.objectiveId(), execution.currentCount(), execution.requiredCount()));
                        }
                    }
                    if (execution.shouldComplete()) {
                        Quest quest = registry.getQuest(request.questId()).orElse(null);
                        if (quest == null) {
                            result = questCompletionPending(result, progression.getQuestRevision(),
                                    "QUEST_NOT_FOUND", "Quest definition is unavailable while completion is pending");
                        } else {
                            List<FactionReputationChangeEvent> factionEvents = new ArrayList<>();
                            QuestCompletionResult completion = completeQuestUnderLock(
                                    request.playerUuid(), quest, progression, factionEvents);
                            if (completion.outcome() == QuestCompletionResult.Outcome.COMPLETED) {
                                notifications.addAll(factionEvents);
                                notifications.add(new QuestCompleteEvent(request.playerUuid(), request.questId()));
                                result = new CanonicalMutationResult(true, result.duplicate(),
                                        progression.getQuestRevision(), ValidationResult.valid(), result.events(),
                                        result.duplicate() ? "COMPLETION_REPLAYED" : "COMMITTED");
                                rememberQuestMutation(request, fingerprint, result, false);
                            } else if (completion.outcome() != QuestCompletionResult.Outcome.ALREADY_COMPLETED) {
                                result = questCompletionPending(result, progression.getQuestRevision(),
                                        completion.code(), "Progress committed; quest completion remains pending ("
                                                + completion.code() + ")");
                                rememberQuestMutation(request, fingerprint, result, true);
                            } else {
                                result = new CanonicalMutationResult(true, result.duplicate(),
                                        progression.getQuestRevision(), ValidationResult.valid(), result.events(),
                                        "COMPLETION_ALREADY_COMMITTED");
                                rememberQuestMutation(request, fingerprint, result, false);
                            }
                        }
                    }
                    if (publishCanonical) notifications.add(0, questMutationEvent(request, result));
                }
                // Enqueue before releasing the per-player commit lock so concurrent commits cannot reorder groups.
                dispatchQueue = enqueueQuestEvents(request.playerUuid(), notifications);
            }
        }

        if (dispatchQueue != null) drainQuestEvents(dispatchQueue);
        return result;
    }

    private QuestMutationExecution applyQuestProgressionMutation(
            QuestProgressionMutationRequest request, String fingerprint, PlayerProgression progression) {
        CompletedQuestMutation completed = completedQuestMutations.get(request.requestId());
        if (completed != null) {
            if (!completed.payloadFingerprint().equals(fingerprint)) {
                return QuestMutationExecution.resultOnly(questMutationFailure(progression.getQuestRevision(),
                        "REQUEST_PAYLOAD_MISMATCH", "Request ID is already bound to a different quest mutation payload"));
            }
            CanonicalMutationResult prior = completed.result();
            CanonicalMutationResult replay = new CanonicalMutationResult(
                    prior.applied(), true, prior.revision(), prior.diagnostics(), prior.events(), prior.recoveryOutcome());
            if (!completed.completionPending()) return QuestMutationExecution.resultOnly(replay);
            return retryPendingQuestCompletion(request, fingerprint, progression, replay);
        }

        PendingQuestCompletion persistedPending = progression.getPendingQuestCompletions().get(request.questId());
        if (persistedPending != null && persistedPending.requestId().equals(request.requestId())) {
            if (!persistedPending.payloadFingerprint().equals(fingerprint)) {
                return QuestMutationExecution.resultOnly(questMutationFailure(progression.getQuestRevision(),
                        "REQUEST_PAYLOAD_MISMATCH", "Pending request ID is bound to a different quest mutation payload"));
            }
            String eventName = request.action() == QuestProgressionMutationRequest.Action.START
                    ? "QuestStartEvent" : "QuestObjectiveProgressEvent";
            CanonicalMutationResult replay = new CanonicalMutationResult(true, true,
                    progression.getQuestRevision(), ValidationResult.valid(), List.of(eventName),
                    "PROGRESSION_COMMITTED_COMPLETION_PENDING");
            return retryPendingQuestCompletion(request, fingerprint, progression, replay);
        }

        long currentRevision = progression.getQuestRevision();
        if (currentRevision != request.expectedRevision()) {
            CanonicalMutationResult stale = questMutationFailure(currentRevision, "STALE_REVISION",
                    "Expected player progression revision " + request.expectedRevision()
                            + " but current revision is " + currentRevision);
            rememberQuestMutation(request, fingerprint, stale);
            return QuestMutationExecution.resultOnly(stale);
        }

        Quest quest = registry.getQuest(request.questId()).orElse(null);
        if (quest == null) {
            CanonicalMutationResult missing = questMutationFailure(currentRevision, "QUEST_NOT_FOUND",
                    "Quest not found: " + request.questId());
            rememberQuestMutation(request, fingerprint, missing);
            return QuestMutationExecution.resultOnly(missing);
        }

        QuestProgressState current = progression.getQuests().get(request.questId());
        if (request.action() == QuestProgressionMutationRequest.Action.START) {
            if (current != null && current.getStatus() == QuestProgressState.Status.COMPLETED
                    && !com.storynpcs.domain.quest.QuestRepeatPolicy.canRestart(quest.getRepeatType(),
                            current.getLastCompletedAtEpochMillis(), System.currentTimeMillis(), java.time.ZoneId.systemDefault())) {
                ValidationResult diagnostics = ValidationResult.valid();
                diagnostics.addWarning("QUEST_ALREADY_COMPLETED",
                        quest.getRepeatType() == Quest.RepeatType.ONCE
                                ? "This quest is non-repeatable and is already complete"
                                : "This quest's repeat cooldown (" + quest.getRepeatType() + ") has not elapsed yet");
                CanonicalMutationResult unchanged = new CanonicalMutationResult(false, false, currentRevision,
                        diagnostics, List.of(), "NO_CHANGE");
                rememberQuestMutation(request, fingerprint, unchanged);
                return QuestMutationExecution.resultOnly(unchanged);
            }
            if (quest.getPrerequisites() != null) {
                for (NamespacedId prerequisite : quest.getPrerequisites()) {
                    QuestProgressState prerequisiteState = progression.getQuests().get(prerequisite);
                    if (prerequisiteState == null || prerequisiteState.getStatus() != QuestProgressState.Status.COMPLETED) {
                        CanonicalMutationResult rejected = questMutationFailure(currentRevision,
                                "QUEST_PREREQUISITE_INCOMPLETE", "Prerequisite quest not completed: " + prerequisite);
                        rememberQuestMutation(request, fingerprint, rejected);
                        return QuestMutationExecution.resultOnly(rejected);
                    }
                }
            }
        } else {
            if (current == null || current.getStatus() != QuestProgressState.Status.IN_PROGRESS) {
                ValidationResult diagnostics = ValidationResult.valid();
                diagnostics.addWarning("QUEST_NOT_IN_PROGRESS", "Quest is not currently in progress");
                CanonicalMutationResult unchanged = new CanonicalMutationResult(false, false, currentRevision,
                        diagnostics, List.of(), "NO_CHANGE");
                rememberQuestMutation(request, fingerprint, unchanged);
                return QuestMutationExecution.resultOnly(unchanged);
            }
            boolean objectiveExists = quest.getObjectives() != null && quest.getObjectives().stream()
                    .anyMatch(objective -> objective != null && objective.getId().equals(request.objectiveId()));
            if (!objectiveExists) {
                CanonicalMutationResult rejected = questMutationFailure(currentRevision, "OBJECTIVE_NOT_FOUND",
                        "Objective not found: " + request.objectiveId());
                rememberQuestMutation(request, fingerprint, rejected);
                return QuestMutationExecution.resultOnly(rejected);
            }
        }

        PlayerProgression snapshot = progression.copy();
        int currentCount = 0;
        int requiredCount = 0;
        boolean shouldComplete = false;
        try {
            QuestProgressState state = progression.getQuestState(request.questId());
            if (request.action() == QuestProgressionMutationRequest.Action.START) {
                state.setStatus(QuestProgressState.Status.IN_PROGRESS);
            } else {
                state.incrementCount(request.objectiveId(), request.amount());
                QuestObjective objective = quest.getObjectives().stream()
                        .filter(candidate -> candidate != null
                                && Objects.equals(candidate.getId(), request.objectiveId()))
                        .findFirst().orElseThrow();
                currentCount = state.getCount(request.objectiveId());
                requiredCount = objective.getRequiredCount();
                shouldComplete = quest.getObjectives().stream()
                        .allMatch(candidate -> candidate != null
                                && state.getCount(candidate.getId()) >= candidate.getRequiredCount());
            }
            state.advanceStateRevision();
            progression.setQuestRevision(Math.addExact(currentRevision, 1L));
            progression.getPendingQuestCompletions().remove(request.questId());
            if (shouldComplete) {
                progression.getPendingQuestCompletions().put(request.questId(), new PendingQuestCompletion(
                        request.requestId(), request.questId(), fingerprint, state.getStateRevision()));
            }
            progressionRepository.save(request.playerUuid(), progression);
        } catch (Exception failure) {
            progression.restoreFrom(snapshot);
            CanonicalMutationResult rejected = questMutationFailure(currentRevision, "PROGRESSION_COMMIT_FAILED",
                    "Could not durably save quest progression: " + failure.getMessage());
            return QuestMutationExecution.resultOnly(rejected);
        }

        String event = request.action() == QuestProgressionMutationRequest.Action.START
                ? "QuestStartEvent" : "QuestObjectiveProgressEvent";
        CanonicalMutationResult applied = new CanonicalMutationResult(true, false, progression.getQuestRevision(),
                ValidationResult.valid(), List.of(event), "COMMITTED");
        rememberQuestMutation(request, fingerprint, applied, shouldComplete);
        if (request.action() == QuestProgressionMutationRequest.Action.START) {
            return QuestMutationExecution.resultOnly(applied);
        }
        return new QuestMutationExecution(applied, currentCount, requiredCount, shouldComplete);
    }

    private QuestMutationExecution retryPendingQuestCompletion(
            QuestProgressionMutationRequest request, String fingerprint, PlayerProgression progression,
            CanonicalMutationResult replay) {
        PendingQuestCompletion pending = progression.getPendingQuestCompletions().get(request.questId());
        Quest quest = registry.getQuest(request.questId()).orElse(null);
        QuestProgressState state = progression.getQuests().get(request.questId());
        if (pending == null || !pending.requestId().equals(request.requestId())
                || !pending.payloadFingerprint().equals(fingerprint)
                || !isPendingQuestCompletionEligible(quest, state, pending)) {
            ValidationResult diagnostics = ValidationResult.valid();
            diagnostics.addWarning("QUEST_COMPLETION_NO_LONGER_ELIGIBLE",
                    "The original progress committed, but the current quest state no longer matches its pending completion");
            CanonicalMutationResult stale = new CanonicalMutationResult(true, true,
                    progression.getQuestRevision(), diagnostics, replay.events(), "COMPLETION_NO_LONGER_ELIGIBLE");
            rememberQuestMutation(request, fingerprint, stale, false);
            return QuestMutationExecution.resultOnly(stale);
        }
        QuestObjective requestedObjective = quest.getObjectives().stream()
                .filter(objective -> objective != null && Objects.equals(objective.getId(), request.objectiveId()))
                .findFirst().orElse(null);
        int currentCount = requestedObjective == null ? 0 : state.getCount(request.objectiveId());
        int requiredCount = requestedObjective == null ? 0 : requestedObjective.getRequiredCount();
        return new QuestMutationExecution(replay, currentCount, requiredCount, true);
    }

    private static boolean isPendingQuestCompletionEligible(
            Quest quest, QuestProgressState state, PendingQuestCompletion pending) {
        if (quest == null || state == null || pending == null
                || state.getStatus() != QuestProgressState.Status.IN_PROGRESS
                || state.getStateRevision() != pending.questStateRevision()
                || quest.getObjectives() == null || quest.getObjectives().isEmpty()) {
            return false;
        }
        return quest.getObjectives().stream().allMatch(objective -> objective != null
                && objective.getId() != null
                && state.getCount(objective.getId()) >= objective.getRequiredCount());
    }

    private void rememberQuestMutation(QuestProgressionMutationRequest request, String fingerprint,
                                       CanonicalMutationResult result) {
        rememberQuestMutation(request, fingerprint, result, false);
    }

    private void rememberQuestMutation(QuestProgressionMutationRequest request, String fingerprint,
                                       CanonicalMutationResult result, boolean completionPending) {
        completedQuestMutations.put(request.requestId(),
                new CompletedQuestMutation(fingerprint, result.snapshot(), completionPending));
        while (completedQuestMutations.size() > 4096) {
            UUID oldest = completedQuestMutations.keySet().iterator().next();
            completedQuestMutations.remove(oldest);
        }
    }

    private static CanonicalMutationResult questMutationFailure(long revision, String code, String message) {
        ValidationResult diagnostics = ValidationResult.valid();
        diagnostics.addError(code, message == null || message.isBlank() ? code : message);
        return new CanonicalMutationResult(false, false, revision, diagnostics, List.of(),
                Set.of("PERMISSION_DENIED", "PLAYER_SUBJECT_MISMATCH", "SCRIPT_CAPABILITY_REQUIRED").contains(code)
                        ? "REJECTED_AUTHORIZATION" : "REJECTED_NO_SIDE_EFFECTS");
    }

    private static CanonicalMutationResult questCompletionPending(
            CanonicalMutationResult prior, long revision, String code, String message) {
        ValidationResult diagnostics = ValidationResult.valid();
        diagnostics.addError("QUEST_COMPLETION_PENDING", message + "; cause=" + code);
        return new CanonicalMutationResult(prior.applied(), prior.duplicate(), revision, diagnostics,
                prior.events(), "PROGRESSION_COMMITTED_COMPLETION_PENDING");
    }

    private static String questMutationFingerprint(QuestProgressionMutationRequest request) {
        String payload = String.join("\u0000", request.actorType(),
                request.actorId() == null ? "" : request.actorId().toString(),
                request.playerUuid().toString(), request.questId().toString(), request.action().name(),
                request.objectiveId(), Integer.toString(request.amount()), Long.toString(request.expectedRevision()));
        return MutationPayloadFingerprint.of(request.operation(), payload);
    }

    private CanonicalMutationEvent questMutationEvent(
            QuestProgressionMutationRequest request, CanonicalMutationResult result) {
        return new CanonicalMutationEvent(request.operation(), request.actorType(), request.questId(),
                request.requestId(), result.applied(), result.revision(), result.recoveryOutcome(),
                request.actorId(), request.playerUuid());
    }

    public long currentQuestProgressionRevision(UUID playerUuid) {
        UUID subject = Objects.requireNonNull(playerUuid, "playerUuid");
        Object playerLock = progressionMutationLocks[subject.hashCode() & (progressionMutationLocks.length - 1)];
        synchronized (playerLock) {
            PlayerProgression progression = progressionRepository.getOrCreate(subject);
            synchronized (progression) {
                return progression.getQuestRevision();
            }
        }
    }

    public void startQuest(UUID playerUuid, NamespacedId questId) {
        CanonicalMutationResult result = mutateQuestProgression(QuestProgressionMutationRequest.start(
                "system", null, playerUuid, questId, currentQuestProgressionRevision(playerUuid), UUID.randomUUID()));
        if (result.hasErrors()) throw new IllegalStateException(result.formatReport());
    }

    public void progressQuest(UUID playerUuid, NamespacedId questId, String objectiveId, int amount) {
        CanonicalMutationResult result = mutateQuestProgression(QuestProgressionMutationRequest.progress(
                "system", null, playerUuid, questId, objectiveId, amount,
                currentQuestProgressionRevision(playerUuid), UUID.randomUUID()));
        if (result.hasErrors()) throw new IllegalStateException(result.formatReport());
    }

    public QuestCompletionResult completeQuest(UUID playerUuid, NamespacedId questId) {
        Quest quest = registry.getQuest(questId)
                .orElseThrow(() -> new NoSuchElementException("Quest not found: " + questId));

        Object playerLock = progressionMutationLocks[playerUuid.hashCode() & (progressionMutationLocks.length - 1)];
        List<FactionReputationChangeEvent> factionEvents = new ArrayList<>();
        List<StoryNpcsEvent> notifications = new ArrayList<>();
        QuestCompletionResult result;
        QuestEventQueue dispatchQueue;
        synchronized (playerLock) {
            PlayerProgression progression;
            try {
                progression = progressionRepository.getOrCreate(playerUuid);
            } catch (RuntimeException unavailable) {
                System.err.println("[StoryNPCs] quest completion blocked for " + playerUuid
                        + ": " + unavailable.getMessage());
                progression = null;
            }
            if (progression == null) {
                result = QuestCompletionResult.failed("PROGRESSION_UNAVAILABLE", 0);
            } else {
                synchronized (progression) {
                    result = completeQuestUnderLock(playerUuid, quest, progression, factionEvents);
                }
                if (result.outcome() == QuestCompletionResult.Outcome.COMPLETED) {
                    notifications.addAll(factionEvents);
                    notifications.add(new QuestCompleteEvent(playerUuid, questId));
                }
            }
            // Enqueue under the player lock; listeners are dispatched after releasing it.
            dispatchQueue = enqueueQuestEvents(playerUuid, notifications);
        }
        if (dispatchQueue != null) drainQuestEvents(dispatchQueue);
        return result;
    }

    private QuestCompletionResult completeQuestUnderLock(
            UUID playerUuid, Quest quest, PlayerProgression progression,
            List<FactionReputationChangeEvent> factionEvents) {
        NamespacedId questId = quest.getId();
        PlayerProgression snapshot = progression.copy();
        QuestProgressState state = progression.getQuestState(questId);

        // Guard against duplicate completion and infinite rewards exploit (VULN-22)
        if (state.getStatus() == QuestProgressState.Status.COMPLETED) {
            return QuestCompletionResult.alreadyCompleted();
        }

        QuestCompletionResult validationFailure = validateQuestRewards(playerUuid, quest);
        if (validationFailure != null) return validationFailure;

        // Durable completion intent before any non-atomic side effect: a crash
        // must leave evidence that this quest's completion was in flight.
        if (!progression.getPendingQuestCompletions().containsKey(questId)) {
            progression.getPendingQuestCompletions().put(questId, new PendingQuestCompletion(
                    UUID.randomUUID(), questId,
                    MutationPayloadFingerprint.of("quest.complete", playerUuid + " " + questId),
                    state.getStateRevision()));
            try {
                progressionRepository.save(playerUuid, progression);
            } catch (Exception e) {
                progression.restoreFrom(snapshot);
                System.err.println("Failed to persist quest completion intent for " + questId
                        + " for player " + playerUuid + ": " + e.getMessage());
                return QuestCompletionResult.failed("COMPLETION_INTENT_FAILED", 0);
            }
        }

        int rewardsApplied = 0;
        try {
            // Non-atomic rewards (XP/item grants) are marked durably BEFORE their
            // side effect runs; a retry after a failed commit skips marked rewards,
            // so each is delivered exactly once. Faction rewards ride the terminal
            // commit — they are atomic with the completed state.
            if (quest.getRewards() != null) {
                int index = 0;
                for (QuestReward reward : quest.getRewards()) {
                    String rewardKey = index++ + "|" + reward.getType()
                            + "|" + reward.getTarget() + "|" + reward.getAmount();
                    switch (reward.getType()) {
                        case EXPERIENCE, ITEM -> {
                            Set<String> delivered = progression.getDeliveredQuestRewards().get(questId);
                            if (delivered != null && delivered.contains(rewardKey)) {
                                break; // granted before a failed commit — never deliver twice
                            }
                            PlayerProgression markSnapshot = progression.copy();
                            progression.getDeliveredQuestRewards()
                                    .computeIfAbsent(questId, key -> new HashSet<>())
                                    .add(rewardKey);
                            try {
                                progressionRepository.save(playerUuid, progression);
                            } catch (Exception markFailure) {
                                // Nothing was delivered — in-memory state must match disk.
                                progression.restoreFrom(markSnapshot);
                                throw markFailure;
                            }
                            // If delivery throws now the durable mark stays: a retry may
                            // lose this reward but can never grant it twice.
                            deliverQuestReward(playerUuid, reward);
                            rewardsApplied++;
                        }
                        case COMMAND -> throw new IllegalStateException("command rewards are non-atomic");
                        default -> { }
                    }
                }
            }

            rewardsApplied = commitQuestCompletion(
                    playerUuid, quest, progression, state, factionEvents, rewardsApplied);
        } catch (Exception e) {
            System.err.println("Failed to complete quest " + questId + " for player " + playerUuid + ": " + e.getMessage());
            return QuestCompletionResult.failed("REWARD_EXECUTION_FAILED", rewardsApplied);
        }

        return QuestCompletionResult.completed(rewardsApplied);
    }

    /**
     * Terminal completion commit: faction-point rewards, the COMPLETED status,
     * and cleanup of the pending intent and reward-delivery marks are one
     * atomic durable write. On failure the cached state is restored to the
     * pre-commit snapshot — durable marks stay — so a retry can never
     * duplicate an already-delivered XP/item reward.
     */
    private int commitQuestCompletion(
            UUID playerUuid, Quest quest, PlayerProgression progression,
            QuestProgressState state, List<FactionReputationChangeEvent> factionEvents,
            int rewardsApplied) throws IOException {
        NamespacedId questId = quest.getId();
        PlayerProgression commitSnapshot = progression.copy();
        try {
            if (quest.getRewards() != null) {
                for (QuestReward reward : quest.getRewards()) {
                    if (reward.getType() != QuestReward.Type.FACTION_POINTS) continue;
                    NamespacedId factionId = NamespacedId.of(reward.getTarget());
                    Faction faction = registry.getFaction(factionId).orElseThrow();
                    int oldPoints = progression.getFactionScore(factionId, faction.getDefaultPoints());
                    progression.adjustFactionScore(factionId, reward.getAmount(), faction.getDefaultPoints());
                    int newPoints = progression.getFactionScore(factionId, faction.getDefaultPoints());
                    factionEvents.add(new FactionReputationChangeEvent(
                            playerUuid, factionId, oldPoints, newPoints));
                    rewardsApplied++;
                }
                // Keep the typed faction-mutation revision consistent with this internal,
                // already-canonical (locked, revisioned quest-completion) reward commit so a
                // client's cached faction revision never silently goes stale after rewards land.
                progression.setFactionRevision(Math.addExact(progression.getFactionRevision(), 1L));
            }
            state.setStatus(QuestProgressState.Status.COMPLETED);
            state.setLastCompletedAtEpochMillis(System.currentTimeMillis());
            state.advanceStateRevision();
            progression.getPendingQuestCompletions().remove(questId);
            progression.getDeliveredQuestRewards().remove(questId);
            progression.setQuestRevision(Math.addExact(progression.getQuestRevision(), 1L));
            progressionRepository.save(playerUuid, progression);
            return rewardsApplied;
        } catch (IOException | RuntimeException commitFailure) {
            progression.restoreFrom(commitSnapshot);
            throw commitFailure;
        }
    }

    /**
     * Runs one non-atomic reward side effect. {@code rewardSideEffectOverride}
     * lets tests observe delivery; production grants through the live server.
     */
    private void deliverQuestReward(UUID playerUuid, QuestReward reward) {
        var override = rewardSideEffectOverride;
        if (override != null) {
            override.accept(playerUuid, reward);
            return;
        }
        if (minecraftServer == null) return;
        var player = minecraftServer.getPlayerList().getPlayer(playerUuid);
        if (player == null) throw new IllegalStateException("player is offline");
        switch (reward.getType()) {
            case EXPERIENCE -> player.giveExperiencePoints(reward.getAmount());
            case ITEM -> {
                var itemRl = net.minecraft.resources.ResourceLocation.tryParse(reward.getTarget().trim());
                var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(itemRl).orElseThrow();
                net.minecraft.world.item.ItemStack stack =
                        new net.minecraft.world.item.ItemStack(item, Math.max(1, reward.getAmount()));
                if (!player.getInventory().add(stack)) player.drop(stack, false);
            }
            default -> throw new IllegalStateException("unsupported reward side effect: " + reward.getType());
        }
    }

    /**
     * Validates the entire reward fan-out before any reward or quest state is
     * changed. Command rewards are rejected until a journal-backed command
     * adapter can provide an explicit non-atomic/retry contract.
     */
    private QuestCompletionResult validateQuestRewards(UUID playerUuid, Quest quest) {
        if (quest.getRewards() == null) return null;
        for (QuestReward reward : quest.getRewards()) {
            if (reward == null || reward.getType() == null) return QuestCompletionResult.rejected("INVALID_REWARD");
            try {
                switch (reward.getType()) {
                    case FACTION_POINTS -> {
                        if (reward.getTarget() == null || reward.getTarget().isBlank()) {
                            return QuestCompletionResult.rejected("FACTION_REWARD_TARGET_MISSING");
                        }
                        NamespacedId factionId = NamespacedId.of(reward.getTarget());
                        if (registry.getFaction(factionId).isEmpty()) {
                            return QuestCompletionResult.rejected("FACTION_NOT_FOUND");
                        }
                    }
                    case EXPERIENCE -> {
                        if (reward.getAmount() < 0) return QuestCompletionResult.rejected("NEGATIVE_EXPERIENCE");
                        if (minecraftServer != null && minecraftServer.getPlayerList().getPlayer(playerUuid) == null) {
                            return QuestCompletionResult.rejected("PLAYER_OFFLINE");
                        }
                    }
                    case ITEM -> {
                        if (reward.getTarget() == null || reward.getTarget().isBlank() || reward.getAmount() <= 0) {
                            return QuestCompletionResult.rejected("INVALID_ITEM_REWARD");
                        }
                        if (minecraftServer != null) {
                            if (minecraftServer.getPlayerList().getPlayer(playerUuid) == null) {
                                return QuestCompletionResult.rejected("PLAYER_OFFLINE");
                            }
                            var itemRl = net.minecraft.resources.ResourceLocation.tryParse(reward.getTarget().trim());
                            if (itemRl == null || net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(itemRl).isEmpty()) {
                                return QuestCompletionResult.rejected("ITEM_NOT_FOUND");
                            }
                        }
                    }
                    case COMMAND -> {
                        return QuestCompletionResult.rejected("NON_ATOMIC_COMMAND_REWARD");
                    }
                }
            } catch (RuntimeException e) {
                return QuestCompletionResult.rejected("INVALID_REWARD");
            }
        }
        return null;
    }

    // ==========================================
    // 5. Role & Subsystem Operations
    // ==========================================

    public boolean executeTrade(UUID playerUuid, NamespacedId npcId,
                                com.storynpcs.domain.role.trader.TradeListing trade) {
        return executeTrade(playerUuid, npcId, -1, trade, UUID.randomUUID());
    }

    /** Replay-aware trade entry point used by network adapters carrying a request ID. */
    public boolean executeTrade(UUID playerUuid, NamespacedId npcId, int listingIndex,
                                com.storynpcs.domain.role.trader.TradeListing trade,
                                UUID requestId) {
        if (playerUuid == null || npcId == null || trade == null || requestId == null) return false;
        if (listingIndex < 0) {
            // No durable listing state exists for unindexed trades — a journal
            // record here could never be reconciled, so run the mutation directly.
            return executeTradeMutation(playerUuid, npcId, listingIndex, trade)
                    == TradeMutationOutcome.COMMITTED;
        }
        // Serialize the prepare/commit/recover decision per request id — recovery
        // must not abort a prepared record between its durable intent and the
        // listing-use commit decision.
        var outcome = tradeOperationJournal.withOperationLock(requestId,
                () -> executeTradeJournaled(playerUuid, npcId, listingIndex, trade, requestId));
        return outcome != null && outcome;
    }

    private boolean executeTradeJournaled(UUID playerUuid, NamespacedId npcId, int listingIndex,
                                          com.storynpcs.domain.role.trader.TradeListing trade,
                                          UUID requestId) {
        String listingId = trade.ensureStableId();
        String requiredFactionId = trade.getRequiredFaction() == null
                ? "" : trade.getRequiredFaction().toString();
        final String subject = playerUuid + "|" + npcId + "|" + listingIndex
                + "|" + listingId + "|" + trade.getOfferItemId() + "|" + Math.max(1, trade.getOfferCount())
                + "|" + trade.getPriceItemId() + "|" + Math.max(1, trade.getPriceCount())
                + "|" + Math.max(0, trade.getMaxUses()) + "|" + requiredFactionId
                + "|" + Math.max(0, trade.getRequiredFactionPoints());
        try {
            var existing = tradeOperationJournal.read(requestId);
            if (existing != null) {
                var replay = tradeOperationJournal.begin(requestId, "trade.execute", subject);
                return replay.status() == com.storynpcs.persistence.DurableOperationJournal.BeginStatus.COMMITTED;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }

        com.storynpcs.persistence.TradeOperationIntent intent;
        try {
            intent = new com.storynpcs.persistence.TradeOperationIntent(
                    playerUuid, npcId.toString(), listingIndex, listingId,
                    trade.getOfferItemId(), Math.max(1, trade.getOfferCount()),
                    trade.getPriceItemId(), Math.max(1, trade.getPriceCount()),
                    Math.max(0, trade.getMaxUses()), Math.max(0, trade.getUses()),
                    requiredFactionId, Math.max(0, trade.getRequiredFactionPoints()));
        } catch (RuntimeException invalid) {
            return false;
        }
        String intentJson = com.storynpcs.domain.role.RoleSerde.toJson(intent);
        if (intentJson.length() > com.storynpcs.persistence.DurableOperationJournal.MAX_DETAIL_LENGTH) return false;
        try {
            var started = tradeOperationJournal.begin(requestId, "trade.execute", subject, intentJson);
            if (started.status() != com.storynpcs.persistence.DurableOperationJournal.BeginStatus.STARTED) {
                return started.status() == com.storynpcs.persistence.DurableOperationJournal.BeginStatus.COMMITTED;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }

        var mutationOutcome = executeTradeMutation(playerUuid, npcId, listingIndex, trade);
        if (mutationOutcome != TradeMutationOutcome.COMMITTED) {
            if (mutationOutcome == TradeMutationOutcome.REJECTED) {
                try {
                    tradeOperationJournal.abort(requestId, "REJECTED", "trade validation or delivery failed");
                } catch (IOException | RuntimeException ignored) {
                    // The prepared record remains visible for explicit recovery.
                }
            }
            // RECOVERY_REQUIRED leaves the record prepared: a durable reservation
            // survived but its rollback could not be proven — never claim a clean abort.
            return false;
        }
        try {
            tradeOperationJournal.commit(requestId, "APPLIED", Integer.toString(trade.getUses()));
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return true;
    }

    /**
     * Result of one trade mutation attempt: {@code COMMITTED} means every leg
     * landed, {@code REJECTED} means nothing durable survives and the journal may
     * abort cleanly, {@code RECOVERY_REQUIRED} means a durable reservation may
     * still stand because its rollback could not be proven — the journal record
     * must stay pending for explicit recovery rather than claim a clean abort.
     */
    private enum TradeMutationOutcome { COMMITTED, REJECTED, RECOVERY_REQUIRED }

    private TradeMutationOutcome executeTradeMutation(UUID playerUuid, NamespacedId npcId, int listingIndex,
                                         com.storynpcs.domain.role.trader.TradeListing trade) {
        if (tradeStateRepository != null && listingIndex >= 0) {
            String listingId = trade.ensureStableId();
            synchronized (trade) {
                int expectedUses = -1;
                boolean reserved = false;
                try {
                    expectedUses = tradeStateRepository.getUses(npcId.toString(), listingId);
                    trade.setUses(expectedUses);
                    if (!tradeStateRepository.reserveUse(npcId.toString(), listingId,
                            expectedUses, Math.max(0, trade.getMaxUses()))) {
                        return TradeMutationOutcome.REJECTED;
                    }
                    reserved = true;
                    boolean executed = executeTradeMutationCore(playerUuid, npcId, trade);
                    if (!executed) {
                        boolean rolledBack = tradeStateRepository.rollbackUse(
                                npcId.toString(), listingId, expectedUses + 1, expectedUses);
                        trade.setUses(expectedUses);
                        if (!rolledBack) {
                            System.err.println("[StoryNPCs] Trade use rollback requires recovery for "
                                    + npcId + " listing " + listingIndex);
                            return TradeMutationOutcome.RECOVERY_REQUIRED;
                        }
                        return TradeMutationOutcome.REJECTED;
                    }
                    return TradeMutationOutcome.COMMITTED;
                } catch (IOException | RuntimeException e) {
                    if (reserved && expectedUses >= 0) {
                        try {
                            tradeStateRepository.rollbackUse(
                                    npcId.toString(), listingId, expectedUses + 1, expectedUses);
                            trade.setUses(expectedUses);
                            return TradeMutationOutcome.REJECTED;
                        } catch (IOException | RuntimeException rollbackFailure) {
                            System.err.println("[StoryNPCs] Trade use rollback failed for "
                                    + npcId + " listing " + listingIndex + ": " + rollbackFailure.getMessage());
                            trade.setUses(Math.max(0, expectedUses));
                            return TradeMutationOutcome.RECOVERY_REQUIRED;
                        }
                    }
                    trade.setUses(Math.max(0, expectedUses));
                    return TradeMutationOutcome.REJECTED;
                }
            }
        }
        try {
            return executeTradeMutationCore(playerUuid, npcId, trade)
                    ? TradeMutationOutcome.COMMITTED
                    : TradeMutationOutcome.REJECTED;
        } catch (RuntimeException e) {
            // Blocked/unreadable progression — reject without granting anything.
            return TradeMutationOutcome.REJECTED;
        }
    }

    private boolean executeTradeMutationCore(UUID playerUuid, NamespacedId npcId,
                                              com.storynpcs.domain.role.trader.TradeListing trade) {
        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        int currentFactionScore = 0;
        if (trade.getRequiredFaction() != null) {
            var factionOpt = registry.getFaction(trade.getRequiredFaction());
            int defaultPts = factionOpt.map(com.storynpcs.domain.faction.Faction::getDefaultPoints).orElse(0);
            currentFactionScore = progression.getFactionScore(trade.getRequiredFaction(), defaultPts);
        }

        if (!trade.isAvailable(currentFactionScore)) {
            return false;
        }

        // VULN-48: resolve BOTH sides of the exchange before mutating anything, then
        // reserve a specific listing use. Payment is deducted only inside the
        // owned reservation boundary and is compensated if output delivery fails.
        if (minecraftServer != null) {
            var player = minecraftServer.getPlayerList().getPlayer(playerUuid);
            if (player == null) return false;

            net.minecraft.world.item.Item priceItem = null;
            int required = 0;
            if (trade.getPriceItemId() != null && !trade.getPriceItemId().isBlank()) {
                var priceRl = net.minecraft.resources.ResourceLocation.tryParse(trade.getPriceItemId().trim());
                if (priceRl == null) return false;
                var priceItemOpt = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(priceRl);
                if (priceItemOpt.isEmpty()) return false;
                final var resolvedPrice = priceItemOpt.get();
                priceItem = resolvedPrice;
                required = Math.max(1, trade.getPriceCount());
                int held = player.getInventory().items.stream()
                        .filter(s -> !s.isEmpty() && s.getItem() == resolvedPrice)
                        .mapToInt(net.minecraft.world.item.ItemStack::getCount)
                        .sum();
                if (held < required) {
                    return false; // insufficient items — abort without recording
                }
            }

            net.minecraft.world.item.Item offerItem = null;
            if (trade.getOfferItemId() != null && !trade.getOfferItemId().isBlank()) {
                var offerRl = net.minecraft.resources.ResourceLocation.tryParse(trade.getOfferItemId().trim());
                if (offerRl == null) return false; // broken listing — never charge the player
                var offerOpt = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(offerRl);
                if (offerOpt.isEmpty()) return false; // unregistered offer item — abort before payment
                offerItem = offerOpt.get();
            }

            // Reserve a use first: if another thread exhausted the listing, abort
            // with nothing mutated. The owned reservation can be rolled back if
            // payment or output delivery fails later in this exchange.
            var reservation = trade.reserveTrade();
            if (reservation == null) {
                return false;
            }

            java.util.List<net.minecraft.world.item.ItemStack> paidStacks = new java.util.ArrayList<>();
            java.util.List<Integer> paidCounts = new java.util.ArrayList<>();
            try {
                if (priceItem != null) {
                    int toRemove = required;
                    for (net.minecraft.world.item.ItemStack slot : player.getInventory().items) {
                        if (!slot.isEmpty() && slot.getItem() == priceItem && toRemove > 0) {
                            int take = Math.min(slot.getCount(), toRemove);
                            slot.shrink(take);
                            paidStacks.add(slot);
                            paidCounts.add(take);
                            toRemove -= take;
                        }
                    }
                }
                if (offerItem != null) {
                    net.minecraft.world.item.ItemStack offerStack =
                            new net.minecraft.world.item.ItemStack(offerItem, Math.max(1, trade.getOfferCount()));
                    if (!player.getInventory().add(offerStack)) {
                        player.drop(offerStack, false);
                    }
                }
                reservation.commit();
            } catch (RuntimeException failure) {
                for (int i = 0; i < paidStacks.size(); i++) {
                    paidStacks.get(i).grow(paidCounts.get(i));
                }
                reservation.rollback();
                System.err.println("Failed to execute trade for player " + playerUuid + ": " + failure.getMessage());
                return false;
            }
            eventPublisher.publish(new com.storynpcs.api.event.TradeExecutedEvent(playerUuid, npcId, trade));
            return true;
        }

        // Headless path (unit tests — no server, no inventories): claim + publish only.
        boolean recorded = trade.recordTrade();
        if (recorded) {
            eventPublisher.publish(new com.storynpcs.api.event.TradeExecutedEvent(playerUuid, npcId, trade));
        }
        return recorded;
    }

    public boolean setFollowerState(UUID playerUuid, NamespacedId npcId, com.storynpcs.domain.role.follower.FollowerRole role, com.storynpcs.domain.role.follower.FollowerRole.State newState) {
        if (!role.isOwnedBy(playerUuid)) {
            return false;
        }
        var oldState = role.getState();
        role.setState(newState);
        eventPublisher.publish(new com.storynpcs.api.event.FollowerStateChangeEvent(playerUuid, npcId, oldState, newState));
        return true;
    }

    public boolean setFollowerFormation(
            UUID playerUuid,
            NamespacedId npcId,
            com.storynpcs.domain.role.follower.FollowerRole role,
            com.storynpcs.domain.role.follower.FormationType newFormation,
            int slotIndex,
            double spacing
    ) {
        if (role == null || !role.isOwnedBy(playerUuid)) {
            return false;
        }
        var oldFormation = role.getFormation();
        role.setFormation(newFormation);
        role.setFormationSlot(slotIndex);
        if (spacing > 0.0) {
            role.setFormationSpacing(spacing);
        }
        eventPublisher.publish(new com.storynpcs.api.event.FollowerFormationChangeEvent(
                playerUuid, npcId, oldFormation, newFormation, slotIndex, role.getFormationSpacing()));
        return true;
    }

    public boolean depositToBank(UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo, int tab, int slot, String itemId, int count) {
        // VULN-54: delegate to tagged overload with null tag — callers that have tag data should use the overload below
        return depositToBank(playerUuid, bankRepo, tab, slot, itemId, count, null);
    }

    /**
     * Deposit an item into the player's bank vault preserving its NBT/DataComponent tag.
     * VULN-54: This overload must be used by GUI and command code that has access to the full ItemStack tag.
     */
    public boolean depositToBank(UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo, int tab, int slot, String itemId, int count, String tag) {
        if (bankRepo == null) return false;
        var result = bankRepo.transact(playerUuid, vault -> {
            boolean changed = vault.deposit(tab, slot, itemId, count, tag);
            return changed
                    ? com.storynpcs.persistence.BankRepository.BankMutation.changed(true)
                    : com.storynpcs.persistence.BankRepository.BankMutation.unchanged(false);
        });
        if (result.committed()) {
            eventPublisher.publish(new com.storynpcs.api.event.BankTransactionEvent(
                    playerUuid, com.storynpcs.api.event.BankTransactionEvent.Type.DEPOSIT, tab, itemId, count));
        }
        return result.committed() && Boolean.TRUE.equals(result.value());
    }

    /**
     * Deposit into the first compatible slot of the given tab (merge or first free slot).
     * Returns the slot index used, or -1 on failure (locked tab, full tab, invalid input).
     */
    public int depositToBankAuto(UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo, int tab, String itemId, int count, String tag) {
        if (bankRepo == null) return -1;
        var result = bankRepo.transact(playerUuid, vault -> {
            int slot = vault.depositAuto(tab, itemId, count, tag);
            return slot >= 0
                    ? com.storynpcs.persistence.BankRepository.BankMutation.changed(slot)
                    : com.storynpcs.persistence.BankRepository.BankMutation.unchanged(-1);
        });
        if (result.committed()) {
            eventPublisher.publish(new com.storynpcs.api.event.BankTransactionEvent(
                    playerUuid, com.storynpcs.api.event.BankTransactionEvent.Type.DEPOSIT, tab, itemId, count));
        }
        return result.committed() ? result.value() : -1;
    }

    /**
     * Journaled server-side deposit of the player's main-hand stack. The bank
     * commit carries a vault-side operation marker before the inventory stack
     * is removed, so a crash can prove which side reached durable storage.
     */
    public BankDepositOperationResult depositHeldToBank(
            UUID playerUuid,
            com.storynpcs.persistence.BankRepository bankRepo,
            int tab,
            UUID requestId) {
        if (playerUuid == null || bankRepo == null || requestId == null) {
            return BankDepositOperationResult.rejected("INVALID_REQUEST");
        }
        // Serialize the prepare/commit/recover decision per request id — recovery
        // must not abort a request between its durable prepare and vault commit.
        var result = bankRepo.operationJournal().withOperationLock(requestId,
                () -> depositHeldToBankJournaled(playerUuid, bankRepo, tab, requestId));
        return result != null ? result : BankDepositOperationResult.recoveryRequired("JOURNAL_UNAVAILABLE");
    }

    private BankDepositOperationResult depositHeldToBankJournaled(
            UUID playerUuid,
            com.storynpcs.persistence.BankRepository bankRepo,
            int tab,
            UUID requestId) {
        final String operationType = "bank.deposit_held";
        final String subject = playerUuid.toString();
        var journal = bankRepo.operationJournal();

        try {
            var existing = journal.read(requestId);
            if (existing != null) {
                var classification = journal.begin(requestId, operationType, subject);
                return classifyHeldDepositReplay(playerUuid, bankRepo, requestId, classification);
            }
        } catch (IOException | RuntimeException e) {
            return BankDepositOperationResult.recoveryRequired("JOURNAL_UNAVAILABLE");
        }

        if (minecraftServer == null) return BankDepositOperationResult.rejected("SERVER_UNAVAILABLE");
        var player = minecraftServer.getPlayerList().getPlayer(playerUuid);
        if (player == null) return BankDepositOperationResult.rejected("PLAYER_OFFLINE");
        var held = player.getMainHandItem();
        if (held.isEmpty()) return BankDepositOperationResult.rejected("EMPTY_MAIN_HAND");

        String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        String tag = held.getComponentsPatch().isEmpty()
                ? null
                : held.save(player.level().registryAccess()).toString();
        int count = held.getCount();

        final com.storynpcs.domain.role.banker.BankVault vault;
        try {
            vault = bankRepo.getOrCreate(playerUuid);
        } catch (RuntimeException unavailable) {
            return BankDepositOperationResult.rejected("VAULT_UNAVAILABLE");
        }
        var before = vault.copy();
        var candidate = before.copy();
        int plannedSlot = candidate.depositAuto(tab, itemId, count, tag);
        if (plannedSlot < 0) return BankDepositOperationResult.rejected("TAB_LOCKED_OR_FULL");
        int beforeCount = before.getTabItems(tab).stream()
                .filter(item -> item.getSlot() == plannedSlot
                        && itemId.equals(item.getItemId())
                        && java.util.Objects.equals(tag, item.getTag()))
                .mapToInt(com.storynpcs.domain.role.banker.BankVault.VaultItem::getCount)
                .findFirst().orElse(0);
        int expectedCount = candidate.getTabItems(tab).stream()
                .filter(item -> item.getSlot() == plannedSlot)
                .mapToInt(com.storynpcs.domain.role.banker.BankVault.VaultItem::getCount)
                .findFirst().orElse(0);
        com.storynpcs.persistence.BankOperationIntent intent;
        try {
            intent = new com.storynpcs.persistence.BankOperationIntent(
                    playerUuid, operationType, tab, plannedSlot, itemId, tag,
                    count, beforeCount, expectedCount, true, before.getRevision());
        } catch (IllegalArgumentException e) {
            return BankDepositOperationResult.rejected("INVALID_BANK_INTENT");
        }
        String intentJson = com.storynpcs.domain.role.RoleSerde.toJson(intent);
        if (intentJson.length() > com.storynpcs.persistence.DurableOperationJournal.MAX_DETAIL_LENGTH) {
            return BankDepositOperationResult.rejected("BANK_INTENT_TOO_LARGE");
        }

        final com.storynpcs.persistence.DurableOperationJournal.BeginResult started;
        try {
            started = journal.begin(requestId, operationType, subject, intentJson);
            if (started.status() != com.storynpcs.persistence.DurableOperationJournal.BeginStatus.STARTED) {
                return classifyHeldDepositReplay(playerUuid, bankRepo, requestId, started);
            }
        } catch (IOException | RuntimeException e) {
            return BankDepositOperationResult.recoveryRequired("JOURNAL_START_FAILED");
        }

        var result = bankRepo.transact(playerUuid, candidateVault -> {
            boolean marked = candidateVault.markOperation(requestId, operationType, intentJson);
            boolean changed = marked && candidateVault.deposit(tab, plannedSlot, itemId, count, tag);
            return changed
                    ? com.storynpcs.persistence.BankRepository.BankMutation.changed(true)
                    : com.storynpcs.persistence.BankRepository.BankMutation.unchanged(false);
        });
        if (!result.committed()) {
            try {
                journal.abort(requestId, "REJECTED", result.failureReason());
                return BankDepositOperationResult.rejected("BANK_COMMIT_FAILED");
            } catch (IOException | RuntimeException e) {
                return BankDepositOperationResult.recoveryRequired("BANK_ABORT_FAILED");
            }
        }

        try {
            var committed = journal.commit(requestId, "APPLIED", Integer.toString(plannedSlot));
            eventPublisher.publish(new com.storynpcs.api.event.BankTransactionEvent(
                    playerUuid, com.storynpcs.api.event.BankTransactionEvent.Type.DEPOSIT,
                    tab, itemId, count));
            return finalizeCommittedHeldDeposit(playerUuid, bankRepo, requestId, committed, false);
        } catch (IOException | RuntimeException e) {
            return BankDepositOperationResult.recoveryRequired("BANK_COMMIT_REQUIRES_RECOVERY");
        }
    }

    private BankDepositOperationResult classifyHeldDepositReplay(
            UUID playerUuid,
            com.storynpcs.persistence.BankRepository bankRepo,
            UUID requestId,
            com.storynpcs.persistence.DurableOperationJournal.BeginResult result) {
        return switch (result.status()) {
            case COMMITTED -> finalizeCommittedHeldDeposit(playerUuid, bankRepo, requestId,
                    result.record(), true);
            case ABORTED -> BankDepositOperationResult.rejected(
                    result.record().outcomeCode() == null ? "ABORTED" : result.record().outcomeCode());
            case PENDING, STARTED -> reconcilePendingHeldDeposit(
                    playerUuid, bankRepo, requestId, result.record());
        };
    }

    private BankDepositOperationResult reconcilePendingHeldDeposit(
            UUID playerUuid,
            com.storynpcs.persistence.BankRepository bankRepo,
            UUID requestId,
            com.storynpcs.persistence.DurableOperationJournal.OperationRecord record) {
        try {
            var marker = bankRepo.getOrCreate(playerUuid).getOperationMarker(requestId);
            if (marker == null) {
                var intentJson = record.preparedIntent() != null
                        ? record.preparedIntent() : record.detail();
                var intent = intentJson == null
                        ? java.util.Optional.<com.storynpcs.persistence.BankOperationIntent>empty()
                        : com.storynpcs.domain.role.RoleSerde.bankOperationIntentFromJson(intentJson);
                if (intent.isPresent() && intent.get().inventoryDeferred()) {
                    bankRepo.operationJournal().abort(requestId, "BANK_NOT_COMMITTED",
                            "vault operation marker was not durable");
                    return BankDepositOperationResult.rejected("BANK_NOT_COMMITTED");
                }
                return BankDepositOperationResult.recoveryRequired("OPERATION_PENDING_RECOVERY");
            }
            var committed = bankRepo.operationJournal().commit(requestId, "APPLIED",
                    Integer.toString(readIntentSlot(marker.getIntent())));
            return finalizeCommittedHeldDeposit(playerUuid, bankRepo, requestId, committed, true);
        } catch (IOException | RuntimeException e) {
            return BankDepositOperationResult.recoveryRequired("PENDING_RECOVERY_FAILED");
        }
    }

    private BankDepositOperationResult finalizeCommittedHeldDeposit(
            UUID playerUuid,
            com.storynpcs.persistence.BankRepository bankRepo,
            UUID requestId,
            com.storynpcs.persistence.DurableOperationJournal.OperationRecord record,
            boolean replay) {
        try {
            int slot = Integer.parseInt(record.detail());
            var marker = bankRepo.getOrCreate(playerUuid).getOperationMarker(requestId);
            if (marker == null) {
                if (!replay) {
                    return BankDepositOperationResult.recoveryRequired("BANK_MARKER_MISSING");
                }
                // A backup restore can erase a committed deposit together with its
                // marker — verify the vault still holds the committed stack before
                // reporting a replay, or a rolled-back deposit would be misresolved
                // while the held items were already taken.
                var intentJson = record.preparedIntent() != null
                        ? record.preparedIntent() : record.detail();
                var intent = com.storynpcs.domain.role.RoleSerde.bankOperationIntentFromJson(intentJson);
                if (intent.isEmpty()) {
                    return BankDepositOperationResult.recoveryRequired("BANK_MARKER_MISSING");
                }
                var operation = intent.get();
                boolean stackPresent = bankRepo.getOrCreate(playerUuid).getTabItems(operation.tab())
                        .stream().anyMatch(operation::matches);
                return stackPresent
                        ? BankDepositOperationResult.replayed(slot)
                        : BankDepositOperationResult.recoveryRequired("BANK_MARKER_MISSING");
            }
            if (marker.isInventoryApplied()) {
                var cleanupApplied = bankRepo.transact(playerUuid, vault ->
                        vault.removeOperationMarker(requestId)
                                ? com.storynpcs.persistence.BankRepository.BankMutation.changed(true)
                                : com.storynpcs.persistence.BankRepository.BankMutation.unchanged(false));
                if (!cleanupApplied.committed()) {
                    return BankDepositOperationResult.recoveryRequired("BANK_MARKER_CLEANUP_FAILED");
                }
                return BankDepositOperationResult.replayed(slot);
            }
            var intentJson = record.preparedIntent() != null
                    ? record.preparedIntent() : marker.getIntent();
            var intent = com.storynpcs.domain.role.RoleSerde.bankOperationIntentFromJson(intentJson)
                    .orElseThrow(() -> new IOException("invalid bank operation intent"));
            if (minecraftServer == null) {
                return BankDepositOperationResult.recoveryRequired("SERVER_UNAVAILABLE");
            }
            var player = minecraftServer.getPlayerList().getPlayer(playerUuid);
            if (player == null) {
                return BankDepositOperationResult.recoveryRequired("PLAYER_OFFLINE");
            }
            var held = player.getMainHandItem();
            if (!heldStackMatchesIntent(player, held, intent)) {
                return BankDepositOperationResult.recoveryRequired("INVENTORY_RECONCILIATION_REQUIRED");
            }
            held.shrink(intent.count());
            var markedApplied = bankRepo.transact(playerUuid, vault ->
                    vault.markOperationInventoryApplied(requestId)
                            ? com.storynpcs.persistence.BankRepository.BankMutation.changed(true)
                            : com.storynpcs.persistence.BankRepository.BankMutation.unchanged(false));
            if (!markedApplied.committed()) {
                return BankDepositOperationResult.recoveryRequired("INVENTORY_MARKER_WRITE_FAILED");
            }
            var cleanup = bankRepo.transact(playerUuid, vault ->
                    vault.removeOperationMarker(requestId)
                            ? com.storynpcs.persistence.BankRepository.BankMutation.changed(true)
                            : com.storynpcs.persistence.BankRepository.BankMutation.unchanged(false));
            if (!cleanup.committed()) {
                return BankDepositOperationResult.recoveryRequired("BANK_MARKER_CLEANUP_FAILED");
            }
            return replay
                    ? BankDepositOperationResult.replayed(slot)
                    : BankDepositOperationResult.applied(slot);
        } catch (IOException | RuntimeException e) {
            return BankDepositOperationResult.recoveryRequired("COMMITTED_RESULT_INVALID");
        }
    }

    private boolean heldStackMatchesIntent(
            net.minecraft.server.level.ServerPlayer player,
            net.minecraft.world.item.ItemStack held,
            com.storynpcs.persistence.BankOperationIntent intent) {
        // Exact count only: the deposit consumed the whole held stack, so an
        // un-shrunk replay presents exactly intent.count(). A larger stack can
        // only be re-acquired items — shrinking it would destroy player items.
        if (held == null || held.isEmpty() || held.getCount() != intent.count()) return false;
        String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(held.getItem()).toString();
        if (!intent.itemId().equals(itemId)) return false;
        if (intent.tag() == null || intent.tag().isBlank()) {
            return held.getComponentsPatch().isEmpty();
        }
        try {
            var parsed = net.minecraft.nbt.TagParser.parseTag(intent.tag());
            var expected = net.minecraft.world.item.ItemStack.parse(
                    player.level().registryAccess(), parsed).orElse(null);
            return expected != null
                    && net.minecraft.world.item.ItemStack.isSameItemSameComponents(held, expected);
        } catch (Exception ignored) {
            return false;
        }
    }

    private int readIntentSlot(String intentJson) throws IOException {
        return com.storynpcs.domain.role.RoleSerde.bankOperationIntentFromJson(intentJson)
                .map(com.storynpcs.persistence.BankOperationIntent::slot)
                .orElseThrow(() -> new IOException("invalid bank operation intent"));
    }

    /**
     * Reconciles a prepared whole-stack withdrawal after a restart. A vault
     * item is not proof that a withdrawal was never delivered: an operator or
     * another gameplay action could have restored an identical stack after
     * the bank-side removal. Therefore only the exact original stack and
     * captured vault revision, with a valid full-stack intent, is auto-aborted; every other state remains
     * explicitly pending for manual recovery rather than risking duplication.
     */
    private boolean reconcilePendingBankWithdrawal(
            UUID playerUuid,
            com.storynpcs.persistence.BankRepository bankRepo,
            UUID operationId) {
        com.storynpcs.persistence.DurableOperationJournal.OperationRecord record;
        try {
            record = bankRepo.operationJournal().read(operationId);
        } catch (IOException | RuntimeException failure) {
            return false;
        }
        if (record == null) return false;
        if (record.state() != com.storynpcs.persistence.DurableOperationJournal.State.PREPARED) return true;
        if (!"bank.withdraw".equals(record.operationType())) return false;
        String intentJson = record.preparedIntent() != null
                ? record.preparedIntent() : record.detail();
        var intent = intentJson == null
                ? java.util.Optional.<com.storynpcs.persistence.BankOperationIntent>empty()
                : com.storynpcs.domain.role.RoleSerde.bankOperationIntentFromJson(intentJson);
        if (intent.isEmpty()) return false;

        var withdrawal = intent.get();
        if (!playerUuid.equals(withdrawal.playerUuid())
                || !"withdraw".equals(withdrawal.action())
                || withdrawal.count() != withdrawal.beforeCount()
                || withdrawal.count() != withdrawal.expectedCount()
                || withdrawal.vaultRevision() == null) {
            return false;
        }

        boolean exactOriginalStackStillPresent;
        try {
            var vault = bankRepo.getOrCreate(playerUuid).copy();
            exactOriginalStackStillPresent = vault.getRevision() == withdrawal.vaultRevision()
                    && vault.getTabItems(withdrawal.tab()).stream()
                    .anyMatch(item -> item.getSlot() == withdrawal.slot()
                            && withdrawal.itemId().equals(item.getItemId())
                            && java.util.Objects.equals(withdrawal.tag(), item.getTag())
                            && item.getCount() == withdrawal.expectedCount());
        } catch (RuntimeException failure) {
            return false;
        }
        if (!exactOriginalStackStillPresent) return false;

        try {
            bankRepo.operationJournal().abort(operationId, "BANK_NOT_COMMITTED",
                    "exact withdrawal stack remains in the vault");
            return true;
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    /**
     * Replays held-item bank operations after the bank cache and player
     * inventory have been restored. Pending withdrawal records are also
     * reconciled: only an exact untouched source stack at the captured vault
     * revision is auto-aborted;
     * ambiguous post-removal states remain pending and are reported.
     * Both pending journal records and durable vault markers are scanned so
     * interrupted marker cleanup is retried too.
     *
     * @return the number of operations still requiring manual recovery
     */
    public int recoverBankOperations(
            UUID playerUuid,
            com.storynpcs.persistence.BankRepository bankRepo) {
        if (playerUuid == null || bankRepo == null) return 0;
        java.util.List<com.storynpcs.persistence.DurableOperationJournal.OperationRecord> pendingRecords;
        java.util.Set<UUID> depositRequestIds = new java.util.LinkedHashSet<>();
        java.util.Set<UUID> unlockRequestIds = new java.util.LinkedHashSet<>();
        try {
            String playerSubject = playerUuid.toString();
            String compoundSubjectPrefix = playerSubject + "|";
            pendingRecords = bankRepo.operationJournal().pending().stream()
                    .filter(record -> playerSubject.equals(record.subject())
                            || record.subject().startsWith(compoundSubjectPrefix))
                    .toList();
            pendingRecords.stream()
                    .filter(record -> "bank.deposit_held".equals(record.operationType()))
                    .map(com.storynpcs.persistence.DurableOperationJournal.OperationRecord::operationId)
                    .forEach(depositRequestIds::add);
            pendingRecords.stream()
                    .filter(record -> "bank.unlock_tab".equals(record.operationType()))
                    .map(com.storynpcs.persistence.DurableOperationJournal.OperationRecord::operationId)
                    .forEach(unlockRequestIds::add);
            bankRepo.getOrCreate(playerUuid).getOperationMarkers().values().stream()
                    .filter(marker -> "bank.deposit_held".equals(marker.getOperationType()))
                    .map(com.storynpcs.domain.role.banker.BankVault.OperationMarker::getOperationId)
                    .forEach(depositRequestIds::add);
            bankRepo.getOrCreate(playerUuid).getOperationMarkers().values().stream()
                    .filter(marker -> "bank.unlock_tab".equals(marker.getOperationType()))
                    .map(com.storynpcs.domain.role.banker.BankVault.OperationMarker::getOperationId)
                    .forEach(unlockRequestIds::add);
        } catch (IOException | RuntimeException e) {
            return 1;
        }
        int unresolved = 0;
        for (var record : pendingRecords) {
            if ("bank.withdraw".equals(record.operationType())) {
                boolean resolved;
                try {
                    resolved = bankRepo.operationJournal().withOperationLock(record.operationId(),
                            () -> reconcilePendingBankWithdrawal(
                                    playerUuid, bankRepo, record.operationId()));
                } catch (RuntimeException failure) {
                    resolved = false;
                }
                if (!resolved) unresolved++;
            }
        }
        for (UUID requestId : depositRequestIds) {
            BankDepositOperationResult result = depositHeldToBank(playerUuid, bankRepo, 0, requestId);
            if (result.outcome() == BankDepositOperationResult.Outcome.RECOVERY_REQUIRED) {
                unresolved++;
            }
        }
        for (UUID requestId : unlockRequestIds) {
            boolean resolved;
            try {
                resolved = Boolean.TRUE.equals(bankRepo.operationJournal().withOperationLock(requestId,
                        () -> reconcileBankUnlockRequest(playerUuid, bankRepo, requestId)));
            } catch (RuntimeException failure) {
                resolved = false;
            }
            if (!resolved) unresolved++;
        }
        return unresolved;
    }

    /**
     * Recovery entry for one tab-unlock request: a still-prepared record is
     * reconciled against the vault marker; anything else (including a committed
     * record whose marker cleanup was interrupted) runs the payment finalize.
     */
    private boolean reconcileBankUnlockRequest(
            UUID playerUuid,
            com.storynpcs.persistence.BankRepository bankRepo,
            UUID requestId) {
        try {
            var record = bankRepo.operationJournal().read(requestId);
            if (record != null
                    && record.state() == com.storynpcs.persistence.DurableOperationJournal.State.PREPARED
                    && "bank.unlock_tab".equals(record.operationType())) {
                return reconcilePendingBankUnlock(playerUuid, bankRepo, requestId);
            }
            return finalizeCommittedBankUnlock(playerUuid, bankRepo, requestId, true);
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    /**
     * Reconciles pending trade.execute records for one player after a restart.
     * The durable listing use count is the ground truth: when it already
     * advanced past the captured intent the reservation committed and the
     * operation stays pending (payment/output ambiguity is never guessed);
     * when it is unchanged the trade provably never committed and is aborted.
     *
     * @return the number of operations still requiring manual recovery
     */
    public int recoverTradeOperations(UUID playerUuid) {
        if (playerUuid == null) return 0;
        java.util.List<com.storynpcs.persistence.DurableOperationJournal.OperationRecord> pending;
        try {
            String subjectPrefix = playerUuid + "|";
            pending = tradeOperationJournal.pending().stream()
                    .filter(record -> "trade.execute".equals(record.operationType()))
                    .filter(record -> record.subject() != null && record.subject().startsWith(subjectPrefix))
                    .toList();
        } catch (IOException | RuntimeException e) {
            return 1;
        }
        int unresolved = 0;
        for (var record : pending) {
            boolean resolved;
            try {
                resolved = Boolean.TRUE.equals(tradeOperationJournal.withOperationLock(
                        record.operationId(),
                        () -> reconcilePendingTrade(playerUuid, record.operationId())));
            } catch (RuntimeException failure) {
                resolved = false;
            }
            if (!resolved) unresolved++;
        }
        return unresolved;
    }

    private boolean reconcilePendingTrade(UUID playerUuid, UUID operationId) {
        com.storynpcs.persistence.DurableOperationJournal.OperationRecord record;
        try {
            record = tradeOperationJournal.read(operationId);
        } catch (IOException | RuntimeException failure) {
            return false;
        }
        if (record == null || !"trade.execute".equals(record.operationType())) return false;
        if (record.state() != com.storynpcs.persistence.DurableOperationJournal.State.PREPARED) {
            return true; // already committed or aborted — nothing to reconcile
        }
        String intentJson = record.preparedIntent() != null ? record.preparedIntent() : record.detail();
        var intent = intentJson == null
                ? java.util.Optional.<com.storynpcs.persistence.TradeOperationIntent>empty()
                : com.storynpcs.domain.role.RoleSerde.tradeOperationIntentFromJson(intentJson);
        if (intent.isEmpty() || !playerUuid.equals(intent.get().playerUuid())) {
            return false; // malformed or foreign intent — never guess
        }
        var operation = intent.get();
        if (operation.listingIndex() < 0 || operation.listingId() == null || operation.listingId().isBlank()
                || tradeStateRepository == null) {
            return false; // no durable listing state to reconcile against — stays pending
        }
        final int durableUses;
        try {
            durableUses = tradeStateRepository.getUses(operation.npcId(), operation.listingId());
        } catch (IOException | RuntimeException failure) {
            return false; // durable listing state unreadable — fail closed
        }
        if (durableUses == operation.usesBefore()) {
            try {
                tradeOperationJournal.abort(operationId, "TRADE_NOT_COMMITTED",
                        "durable listing use count was unchanged");
                return true;
            } catch (IOException | RuntimeException failure) {
                return false;
            }
        }
        return false; // reservation committed or diverged — stays pending for explicit recovery
    }

    /**
     * Unlock the next bank tab for a player at a banker NPC. Costs {@code tabUpgradeCost}
     * emeralds deducted from the player's inventory (free when cost is 0). Fails when the
     * vault already has all of the banker's tabs unlocked or the player cannot pay.
     *
     * <p>The operation is journaled across the two non-atomic stores: the vault
     * unlock and a durable operation marker commit in one vault write first,
     * then the inventory payment leg runs. A crash between them is reconciled
     * by {@link #recoverBankOperations} — payment is never taken twice, and an
     * ambiguous inventory state stays pending for explicit recovery instead of
     * guessing.
     */
    public boolean unlockBankTab(UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo,
                                 com.storynpcs.domain.role.banker.BankerRole banker) {
        return unlockBankTab(playerUuid, bankRepo, banker, UUID.randomUUID());
    }

    /** Replay-safe overload used by request-id-carrying adapters. */
    public boolean unlockBankTab(UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo,
                                 com.storynpcs.domain.role.banker.BankerRole banker, UUID requestId) {
        if (playerUuid == null || bankRepo == null || banker == null || requestId == null) return false;
        var journal = bankRepo.operationJournal();
        return Boolean.TRUE.equals(journal.withOperationLock(requestId,
                () -> unlockBankTabJournaled(playerUuid, bankRepo, banker, requestId, journal)));
    }

    private boolean unlockBankTabJournaled(
            UUID playerUuid,
            com.storynpcs.persistence.BankRepository bankRepo,
            com.storynpcs.domain.role.banker.BankerRole banker,
            UUID requestId,
            com.storynpcs.persistence.DurableOperationJournal journal) {
        final String operationType = "bank.unlock_tab";
        final String subject = playerUuid.toString();
        try {
            var existing = journal.read(requestId);
            if (existing != null) {
                if (!subject.equals(existing.subject())) {
                    return false; // request id is bound to a different subject — fail closed
                }
                var classification = journal.begin(requestId, operationType, subject);
                return switch (classification.status()) {
                    case COMMITTED -> finalizeCommittedBankUnlock(playerUuid, bankRepo, requestId, true);
                    case ABORTED -> false;
                    case PENDING, STARTED -> reconcilePendingBankUnlock(playerUuid, bankRepo, requestId);
                };
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }

        final int unlocked;
        final int cost;
        final int emeraldsBefore;
        final long vaultRevision;
        try {
            var vault = bankRepo.getOrCreate(playerUuid);
            unlocked = vault.getUnlockedTabs();
            vaultRevision = vault.getRevision();
        } catch (RuntimeException unavailable) {
            return false;
        }
        if (unlocked >= Math.max(1, banker.getMaxTabs())) return false;
        cost = Math.max(0, banker.getTabUpgradeCost());
        if (cost > 0) {
            if (minecraftServer == null) return false;
            var player = minecraftServer.getPlayerList().getPlayer(playerUuid);
            if (player == null) return false;
            emeraldsBefore = countHeldEmeralds(player);
            if (emeraldsBefore < cost) return false;
        } else {
            emeraldsBefore = 0;
        }

        final com.storynpcs.persistence.BankOperationIntent intent;
        try {
            intent = new com.storynpcs.persistence.BankOperationIntent(
                    playerUuid, com.storynpcs.persistence.BankOperationIntent.ACTION_UNLOCK_TAB,
                    unlocked, -1, "minecraft:emerald", null,
                    cost, emeraldsBefore, emeraldsBefore - cost, cost > 0, vaultRevision);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        String intentJson = com.storynpcs.domain.role.RoleSerde.toJson(intent);
        if (intentJson.length() > com.storynpcs.persistence.DurableOperationJournal.MAX_DETAIL_LENGTH) {
            return false;
        }
        try {
            var started = journal.begin(requestId, operationType, subject, intentJson);
            if (started.status() != com.storynpcs.persistence.DurableOperationJournal.BeginStatus.STARTED) {
                return switch (started.status()) {
                    case COMMITTED -> finalizeCommittedBankUnlock(playerUuid, bankRepo, requestId, true);
                    case ABORTED -> false;
                    default -> reconcilePendingBankUnlock(playerUuid, bankRepo, requestId);
                };
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }

        // Vault leg: unlock + durable operation marker in ONE atomic commit.
        var vaultResult = bankRepo.transact(playerUuid, candidate -> {
            if (candidate.getUnlockedTabs() != unlocked) {
                return com.storynpcs.persistence.BankRepository.BankMutation.unchanged(false);
            }
            // A surviving marker bound to a different intent means this request id
            // already committed under different terms — never overwrite it.
            if (!candidate.markOperation(requestId, operationType, intentJson)) {
                return com.storynpcs.persistence.BankRepository.BankMutation.unchanged(false);
            }
            candidate.setUnlockedTabs(unlocked + 1);
            return com.storynpcs.persistence.BankRepository.BankMutation.changed(true);
        });
        if (!vaultResult.committed() || !Boolean.TRUE.equals(vaultResult.value())) {
            try {
                journal.abort(requestId, "VAULT_COMMIT_FAILED", vaultResult.failureReason());
            } catch (IOException | RuntimeException ignored) {
                // The prepared record stays pending for explicit recovery.
            }
            return false;
        }
        try {
            journal.commit(requestId, "APPLIED", Integer.toString(unlocked + 1));
        } catch (IOException | RuntimeException e) {
            // The vault unlock is durable; the payment leg is recovered via the marker.
        }
        return finalizeCommittedBankUnlock(playerUuid, bankRepo, requestId, false);
    }

    /**
     * Reconciles a prepared unlock after a crash: no vault marker means the
     * vault commit never landed (abort — nothing was paid); a marker means the
     * vault leg is durable and only the payment leg may still be owed.
     */
    private boolean reconcilePendingBankUnlock(
            UUID playerUuid,
            com.storynpcs.persistence.BankRepository bankRepo,
            UUID requestId) {
        try {
            var marker = bankRepo.getOrCreate(playerUuid).getOperationMarker(requestId);
            if (marker == null) {
                bankRepo.operationJournal().abort(requestId, "BANK_NOT_COMMITTED",
                        "vault unlock marker was not durable");
                return true;
            }
            bankRepo.operationJournal().commit(requestId, "APPLIED",
                    Integer.toString(bankRepo.getOrCreate(playerUuid).getUnlockedTabs()));
            return finalizeCommittedBankUnlock(playerUuid, bankRepo, requestId, true);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Completes the inventory-payment leg of a vault-committed tab unlock.
     * Payment is deducted only when the held emerald count still matches the
     * captured intent exactly — a drifted inventory can never prove whether a
     * crashed attempt already took payment, so it stays pending for manual
     * recovery rather than double-charging or granting a free unlock.
     */
    private boolean finalizeCommittedBankUnlock(
            UUID playerUuid,
            com.storynpcs.persistence.BankRepository bankRepo,
            UUID requestId,
            boolean replay) {
        try {
            var marker = bankRepo.getOrCreate(playerUuid).getOperationMarker(requestId);
            if (marker == null) {
                if (!replay) return false;
                // A backup restore can erase a committed unlock together with its
                // marker. Cross-check the vault against the journal's committed
                // outcome before declaring the request resolved — a rolled-back
                // unlock must stay pending, not silently vanish after payment.
                var record = bankRepo.operationJournal().read(requestId);
                if (record != null && record.detail() != null) {
                    int committedTabs = Integer.parseInt(record.detail().trim());
                    if (bankRepo.getOrCreate(playerUuid).getUnlockedTabs() < committedTabs) {
                        return false;
                    }
                }
                return true; // marker cleaned by a previous finalize — fully applied
            }
            var intent = marker.getIntent() == null
                    ? java.util.Optional.<com.storynpcs.persistence.BankOperationIntent>empty()
                    : com.storynpcs.domain.role.RoleSerde.bankOperationIntentFromJson(marker.getIntent());
            if (intent.isEmpty()
                    || !com.storynpcs.persistence.BankOperationIntent.ACTION_UNLOCK_TAB.equals(intent.get().action())
                    || !playerUuid.equals(intent.get().playerUuid())) {
                return false; // foreign or malformed marker — leave for manual recovery
            }
            var operation = intent.get();
            if (marker.isInventoryApplied()) {
                return cleanupBankUnlockMarker(playerUuid, bankRepo, requestId, replay);
            }
            if (!operation.inventoryDeferred() || operation.count() <= 0) {
                // Free unlock — nothing owed; mark then clean. The event is
                // published once by the finalizer that observed the unapplied
                // marker; replays that find no marker return early.
                var marked = bankRepo.transact(playerUuid, vault ->
                        vault.markOperationInventoryApplied(requestId)
                                ? com.storynpcs.persistence.BankRepository.BankMutation.changed(true)
                                : com.storynpcs.persistence.BankRepository.BankMutation.unchanged(false));
                if (!marked.committed()) return false;
                if (!cleanupBankUnlockMarker(playerUuid, bankRepo, requestId, replay)) {
                    return false;
                }
                eventPublisher.publish(new com.storynpcs.api.event.BankTransactionEvent(
                        playerUuid, com.storynpcs.api.event.BankTransactionEvent.Type.UNLOCK_TAB,
                        operation.tab() + 1, "minecraft:emerald", operation.count()));
                return true;
            }
            if (minecraftServer == null) return false;
            var player = minecraftServer.getPlayerList().getPlayer(playerUuid);
            if (player == null) return false;
            int held = countHeldEmeralds(player);
            if (held != operation.expectedCount() + operation.count()) {
                return false; // inventory drifted — cannot prove payment was not taken already
            }
            deductEmeralds(player, operation.count());
            var markedApplied = bankRepo.transact(playerUuid, vault ->
                    vault.markOperationInventoryApplied(requestId)
                            ? com.storynpcs.persistence.BankRepository.BankMutation.changed(true)
                            : com.storynpcs.persistence.BankRepository.BankMutation.unchanged(false));
            if (!markedApplied.committed()) {
                // Payment was taken but the durable proof failed — refund immediately.
                refundEmeralds(player, operation.count());
                return false;
            }
            if (!cleanupBankUnlockMarker(playerUuid, bankRepo, requestId, replay)) {
                return false;
            }
            eventPublisher.publish(new com.storynpcs.api.event.BankTransactionEvent(
                    playerUuid, com.storynpcs.api.event.BankTransactionEvent.Type.UNLOCK_TAB,
                    operation.tab() + 1, "minecraft:emerald", operation.count()));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private boolean cleanupBankUnlockMarker(
            UUID playerUuid,
            com.storynpcs.persistence.BankRepository bankRepo,
            UUID requestId,
            boolean replay) {
        var cleanup = bankRepo.transact(playerUuid, vault ->
                vault.removeOperationMarker(requestId)
                        ? com.storynpcs.persistence.BankRepository.BankMutation.changed(true)
                        : com.storynpcs.persistence.BankRepository.BankMutation.unchanged(false));
        return cleanup.committed() || replay;
    }

    private static int countHeldEmeralds(net.minecraft.server.level.ServerPlayer player) {
        var emerald = net.minecraft.world.item.Items.EMERALD;
        return (int) player.getInventory().items.stream()
                .filter(s -> !s.isEmpty() && s.getItem() == emerald)
                .mapToLong(net.minecraft.world.item.ItemStack::getCount)
                .sum();
    }

    private static void deductEmeralds(net.minecraft.server.level.ServerPlayer player, int cost) {
        var emerald = net.minecraft.world.item.Items.EMERALD;
        int toRemove = cost;
        for (net.minecraft.world.item.ItemStack slot : player.getInventory().items) {
            if (!slot.isEmpty() && slot.getItem() == emerald && toRemove > 0) {
                int take = Math.min(slot.getCount(), toRemove);
                slot.shrink(take);
                toRemove -= take;
            }
        }
        if (toRemove > 0) throw new IllegalStateException("emerald count drifted during payment");
    }

    private static void refundEmeralds(net.minecraft.server.level.ServerPlayer player, int count) {
        var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, count);
        if (!player.getInventory().add(stack)) player.drop(stack, false);
    }

    public java.util.Optional<com.storynpcs.domain.role.banker.BankVault.VaultItem> withdrawFromBank(
            UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo, int tab, int slot, int count) {
        if (bankRepo == null) return java.util.Optional.empty();
        var result = bankRepo.transact(playerUuid, vault -> {
            var itemOpt = vault.withdraw(tab, slot, count);
            return itemOpt.isPresent()
                    ? com.storynpcs.persistence.BankRepository.BankMutation.changed(itemOpt)
                    : com.storynpcs.persistence.BankRepository.BankMutation.unchanged(itemOpt);
        });
        if (!result.committed()) return java.util.Optional.empty();
        var itemOpt = result.value();
        itemOpt.ifPresent(item -> eventPublisher.publish(new com.storynpcs.api.event.BankTransactionEvent(
                playerUuid, com.storynpcs.api.event.BankTransactionEvent.Type.WITHDRAW,
                tab, item.getItemId(), item.getCount())));
        return itemOpt;
    }

    /**
     * Canonical whole-stack withdrawal used by network/UI adapters. The adapter
     * supplies only the player, tab, and slot; it cannot inspect or choose the
     * amount from the live vault. The service snapshots the current item and
     * removes that exact stack inside the repository transaction.
     */
    public BankWithdrawalOperationResult withdrawEntireStackFromBank(
            UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo, int tab, int slot) {
        return withdrawEntireStackFromBank(playerUuid, bankRepo, tab, slot, null, null);
    }

    private BankWithdrawalOperationResult withdrawEntireStackFromBank(
            UUID playerUuid,
            com.storynpcs.persistence.BankRepository bankRepo,
            int tab,
            int slot,
            Long expectedVaultRevision,
            com.storynpcs.domain.role.banker.BankVault.VaultItem expectedItem) {
        if (playerUuid == null || bankRepo == null) {
            return BankWithdrawalOperationResult.rejected("INVALID_REQUEST");
        }
        if (tab < 0 || slot < 0 || slot >= 54) {
            return BankWithdrawalOperationResult.rejected(
                    tab < 0 ? "INVALID_TAB" : "INVALID_SLOT");
        }
        var result = bankRepo.transact(playerUuid, vault -> {
            if (expectedVaultRevision != null && vault.getRevision() != expectedVaultRevision) {
                return com.storynpcs.persistence.BankRepository.BankMutation.unchanged(
                        BankWithdrawalOperationResult.rejected("STALE_VAULT"));
            }
            if (tab >= vault.getUnlockedTabs()) {
                return com.storynpcs.persistence.BankRepository.BankMutation.unchanged(
                        BankWithdrawalOperationResult.rejected("TAB_LOCKED"));
            }
            var current = vault.getTabItems(tab).stream()
                    .filter(item -> item.getSlot() == slot)
                    .findFirst();
            if (current.isEmpty()) {
                return com.storynpcs.persistence.BankRepository.BankMutation.unchanged(
                        BankWithdrawalOperationResult.rejected("SLOT_EMPTY"));
            }
            if (expectedItem != null
                    && (!expectedItem.getItemId().equals(current.get().getItemId())
                    || !java.util.Objects.equals(expectedItem.getTag(), current.get().getTag())
                    || expectedItem.getCount() != current.get().getCount())) {
                return com.storynpcs.persistence.BankRepository.BankMutation.unchanged(
                        BankWithdrawalOperationResult.rejected("STALE_VAULT"));
            }
            var withdrawn = vault.withdraw(tab, slot, current.get().getCount());
            return withdrawn.isPresent()
                    ? com.storynpcs.persistence.BankRepository.BankMutation.changed(
                            BankWithdrawalOperationResult.applied(withdrawn.get()))
                    : com.storynpcs.persistence.BankRepository.BankMutation.unchanged(
                            BankWithdrawalOperationResult.rejected("WITHDRAW_REJECTED"));
        });
        if (!result.committed()) {
            return result.value() != null
                    ? result.value()
                    : BankWithdrawalOperationResult.rejected("DURABLE_COMMIT_FAILED");
        }
        var outcome = result.value();
        if (outcome != null && outcome.accepted() && outcome.item() != null) {
            return outcome;
        }
        return outcome == null
                ? BankWithdrawalOperationResult.rejected("WITHDRAW_REJECTED")
                : outcome;
    }

    /**
     * Service-owned delivery boundary for a whole-stack withdrawal. The bank
     * mutation, item materialization, compensation, and success event are kept
     * out of the packet adapter. The durable request journal retains ambiguous
     * post-removal states for explicit recovery.
     */
    public BankWithdrawalOperationResult withdrawAndDeliverFromBank(
            UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo, int tab, int slot) {
        return withdrawAndDeliverFromBank(playerUuid, bankRepo, tab, slot, null);
    }

    /** Replay-safe withdrawal entry point used by the network adapter. */
    public BankWithdrawalOperationResult withdrawAndDeliverFromBank(
            UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo,
            int tab, int slot, UUID requestId) {
        if (requestId == null) {
            return withdrawAndDeliverUnjournaled(playerUuid, bankRepo, tab, slot);
        }
        if (playerUuid == null || bankRepo == null || tab < 0 || slot < 0 || slot >= 54) {
            return BankWithdrawalOperationResult.rejected("INVALID_REQUEST");
        }
        var journal = bankRepo.operationJournal();
        return journal.withOperationLock(requestId,
                () -> withdrawAndDeliverJournaled(playerUuid, bankRepo, tab, slot, requestId, journal));
    }

    private BankWithdrawalOperationResult withdrawAndDeliverJournaled(
            UUID playerUuid,
            com.storynpcs.persistence.BankRepository bankRepo,
            int tab,
            int slot,
            UUID requestId,
            com.storynpcs.persistence.DurableOperationJournal journal) {
        String requestPrefix = playerUuid + "|" + tab + "|" + slot + "|";
        try {
            var existing = journal.read(requestId);
            if (existing != null) {
                if (!existing.subject().startsWith(requestPrefix)) {
                    return BankWithdrawalOperationResult.rejected("REQUEST_ID_CONFLICT");
                }
                return switch (existing.state()) {
                    case COMMITTED -> BankWithdrawalOperationResult.replayed();
                    case PREPARED -> BankWithdrawalOperationResult.recoveryRequired("RECOVERY_REQUIRED");
                    case ABORTED -> BankWithdrawalOperationResult.rejected("REPLAYED_REJECTED");
                };
            }
        } catch (IOException | RuntimeException failure) {
            return BankWithdrawalOperationResult.rejected("JOURNAL_FAILED");
        }
        final com.storynpcs.domain.role.banker.BankVault vaultSnapshot;
        try {
            vaultSnapshot = bankRepo.getOrCreate(playerUuid).copy();
        } catch (RuntimeException unavailable) {
            return BankWithdrawalOperationResult.rejected("VAULT_UNAVAILABLE");
        }
        var current = vaultSnapshot.getTabItems(tab).stream()
                .filter(item -> item.getSlot() == slot)
                .findFirst();
        if (current.isEmpty()) {
            return tab >= vaultSnapshot.getUnlockedTabs()
                    ? BankWithdrawalOperationResult.rejected("TAB_LOCKED")
                    : BankWithdrawalOperationResult.rejected("SLOT_EMPTY");
        }
        final String intentJson;
        try {
            intentJson = com.storynpcs.domain.role.RoleSerde.toJson(
                    new com.storynpcs.persistence.BankOperationIntent(
                            playerUuid, "withdraw", tab, slot, current.get().getItemId(),
                            current.get().getTag(), current.get().getCount(),
                            current.get().getCount(), current.get().getCount(), false,
                            vaultSnapshot.getRevision()));
            if (intentJson.length() > com.storynpcs.persistence.DurableOperationJournal.MAX_DETAIL_LENGTH) {
                return BankWithdrawalOperationResult.rejected("INTENT_TOO_LARGE");
            }
        } catch (RuntimeException invalid) {
            return BankWithdrawalOperationResult.rejected("INVALID_INTENT");
        }
        String subject = playerUuid + "|" + tab + "|" + slot + "|"
                + current.get().getItemId() + "|" + current.get().getCount();
        try {
            var started = journal.begin(requestId, "bank.withdraw", subject, intentJson);
            if (started.status() != com.storynpcs.persistence.DurableOperationJournal.BeginStatus.STARTED) {
                return started.status() == com.storynpcs.persistence.DurableOperationJournal.BeginStatus.COMMITTED
                        ? BankWithdrawalOperationResult.replayed()
                        : BankWithdrawalOperationResult.recoveryRequired("RECOVERY_REQUIRED");
            }
        } catch (IOException | RuntimeException failure) {
            return BankWithdrawalOperationResult.rejected("JOURNAL_FAILED");
        }

        BankWithdrawalOperationResult withdrawal = withdrawAndDeliverUnjournaled(
                playerUuid, bankRepo, tab, slot, vaultSnapshot.getRevision(), current.get());
        if (withdrawal.accepted()) {
            try {
                journal.commit(requestId, "APPLIED", Integer.toString(withdrawal.item().getCount()));
                return withdrawal;
            } catch (IOException | RuntimeException failure) {
                return BankWithdrawalOperationResult.recoveryRequired("COMMIT_RECOVERY_REQUIRED");
            }
        }
        if ("DELIVERY_RECOVERY_REQUIRED".equals(withdrawal.code())) {
            return withdrawal;
        }
        try {
            journal.abort(requestId, withdrawal.code(), "withdrawal rejected");
        } catch (IOException | RuntimeException ignored) {
            return BankWithdrawalOperationResult.recoveryRequired("ABORT_RECOVERY_REQUIRED");
        }
        return withdrawal;
    }

    private BankWithdrawalOperationResult withdrawAndDeliverUnjournaled(
            UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo, int tab, int slot) {
        return withdrawAndDeliverUnjournaled(playerUuid, bankRepo, tab, slot, null, null);
    }

    private BankWithdrawalOperationResult withdrawAndDeliverUnjournaled(
            UUID playerUuid,
            com.storynpcs.persistence.BankRepository bankRepo,
            int tab,
            int slot,
            Long expectedVaultRevision,
            com.storynpcs.domain.role.banker.BankVault.VaultItem expectedItem) {
        BankWithdrawalOperationResult withdrawal = withdrawEntireStackFromBank(
                playerUuid, bankRepo, tab, slot, expectedVaultRevision, expectedItem);
        if (!withdrawal.accepted() || withdrawal.item() == null) return withdrawal;
        if (minecraftServer == null) {
            eventPublisher.publish(new com.storynpcs.api.event.BankTransactionEvent(
                    playerUuid, com.storynpcs.api.event.BankTransactionEvent.Type.WITHDRAW,
                    tab, withdrawal.item().getItemId(), withdrawal.item().getCount()));
            return withdrawal;
        }
        var player = minecraftServer.getPlayerList().getPlayer(playerUuid);
        if (player != null && materializeVaultItem(player, withdrawal.item())) {
            eventPublisher.publish(new com.storynpcs.api.event.BankTransactionEvent(
                    playerUuid, com.storynpcs.api.event.BankTransactionEvent.Type.WITHDRAW,
                    tab, withdrawal.item().getItemId(), withdrawal.item().getCount()));
            return withdrawal;
        }
        boolean restored = depositToBank(playerUuid, bankRepo, tab, slot,
                withdrawal.item().getItemId(), withdrawal.item().getCount(), withdrawal.item().getTag());
        return BankWithdrawalOperationResult.rejected(
                restored ? "DELIVERY_FAILED_RESTORED" : "DELIVERY_RECOVERY_REQUIRED");
    }

    private static boolean materializeVaultItem(net.minecraft.server.level.ServerPlayer player,
                                                com.storynpcs.domain.role.banker.BankVault.VaultItem item) {
        net.minecraft.world.item.ItemStack stack = null;
        if (item.getTag() != null && !item.getTag().isBlank()) {
            try {
                var parsed = net.minecraft.nbt.TagParser.parseTag(item.getTag());
                stack = net.minecraft.world.item.ItemStack.parse(
                        player.level().registryAccess(), parsed).orElse(null);
                if (stack != null) {
                    stack.setCount(item.getCount());
                }
            } catch (Exception ignored) {
                // Component-bearing items must not silently degrade to a plain item.
                return false;
            }
            if (stack == null || stack.isEmpty()) return false;
        } else {
            var rl = net.minecraft.resources.ResourceLocation.tryParse(item.getItemId());
            var itemOpt = rl != null
                    ? net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(rl)
                    : java.util.Optional.<net.minecraft.world.item.Item>empty();
            stack = itemOpt.map(i -> new net.minecraft.world.item.ItemStack(i, item.getCount()))
                    .orElse(net.minecraft.world.item.ItemStack.EMPTY);
        }
        if (stack.isEmpty()) return false;
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        return true;
    }

    public void adjustFactionReputation(UUID playerUuid, NamespacedId factionId, int delta) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(factionId, "factionId");
        PlayerProgression prog = progressionRepository.getOrCreate(playerUuid);
        int defaultPoints = registry.getFaction(factionId).map(Faction::getDefaultPoints).orElse(0);
        final int oldScore;
        final int newScore;
        synchronized (prog) {
            oldScore = prog.getFactionScore(factionId, defaultPoints);
            prog.adjustFactionScore(factionId, delta, defaultPoints);
            newScore = prog.getFactionScore(factionId, defaultPoints);
            try {
                progressionRepository.save(playerUuid, prog);
            } catch (IOException e) {
                System.err.println("Failed to persist progression for " + playerUuid + ": " + e.getMessage());
            }
        }
        eventPublisher.publish(new FactionReputationChangeEvent(playerUuid, factionId, oldScore, newScore));
    }
}

