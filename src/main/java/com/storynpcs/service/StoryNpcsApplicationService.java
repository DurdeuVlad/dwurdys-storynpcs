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
import com.storynpcs.domain.quest.RepeatSchedule;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.yaml.CrossReferenceValidator;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.yaml.YamlDefinitionLoader;
import com.storynpcs.yaml.YamlDefinitionWriter;

import java.io.IOException;
import java.time.Instant;
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
    private static final int MAX_REPLAY_RECORDS = 4096;
    private static final int MUTATION_LOCK_STRIPES = 256;

    private final BoundedReplayCache<UUID, CompletedMutation> completedMutations =
            new BoundedReplayCache<>(MAX_REPLAY_RECORDS);
    /** Recent quest progression receipts are process-local; persisted revisions reject replay after restart. */
    private final BoundedReplayCache<UUID, CompletedQuestMutation> completedQuestMutations =
            new BoundedReplayCache<>(MAX_REPLAY_RECORDS);
    /** Recent faction progression receipts are process-local; persisted revisions reject replay after restart. */
    private final BoundedReplayCache<UUID, CompletedFactionMutation> completedFactionMutations =
            new BoundedReplayCache<>(MAX_REPLAY_RECORDS);
    private final BoundedReplayCache<UUID, CompletedQuestCompletion> completedQuestCompletions =
            new BoundedReplayCache<>(MAX_REPLAY_RECORDS);
    private final BoundedReplayCache<UUID, CompletedFollowerMutation> completedFollowerMutations =
            new BoundedReplayCache<>(MAX_REPLAY_RECORDS);
    /** Recent progression-action receipts; replays return the recorded outcome instead of re-applying. */
    private final BoundedReplayCache<UUID, CompletedProgressionAction> completedProgressionActions =
            new BoundedReplayCache<>(MAX_REPLAY_RECORDS);
    /** Durable request journal for transport — survives restart, unlike a replay cache. */
    private final com.storynpcs.persistence.DurableOperationJournal transportOperationJournal;
    private final Object[] progressionActionLocks = createMutationLocks();

    /**
     * Bounded live runtime configuration (P9-4) — shared with the owning mod
     * instance via {@link #setRuntimeTunables}; mutated only through
     * {@link #mutateRuntimeTunables}, which stages changes inside a
     * {@link com.storynpcs.admin.ConfigTransaction}.
     */
    private volatile com.storynpcs.admin.RuntimeTunables runtimeTunables =
            new com.storynpcs.admin.RuntimeTunables();
    /** Opaque single-use choice tokens for every rendered dialogue view (P5-2). */
    private final DialogueChoiceProtocol choiceProtocol =
            new DialogueChoiceProtocol(this::runtimeTunables);
    /**
     * Tick source for choice-token expiry. Defaults to wall-clock 50 ms ticks so
     * headless runs get real 30-second expiry; production may bind the server's
     * tick counter via {@link #setChoiceTickSource}.
     */
    private volatile java.util.function.LongSupplier choiceTickSource = () -> System.currentTimeMillis() / 50;
    private final Object[] questMutationLocks = createMutationLocks();
    private final Object[] progressionMutationLocks = createMutationLocks();
    private final Object[] tradeMutationLocks = createMutationLocks();
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
    /**
     * Repeat-boundary policy applied at the canonical quest START gate:
     * UTC day starts and Monday week starts — deterministic across servers.
     */
    private static final RepeatSchedule QUEST_REPEAT_SCHEDULE = RepeatSchedule.utcDefault();

    void setRewardSideEffectOverride(java.util.function.BiConsumer<UUID, QuestReward> override) {
        this.rewardSideEffectOverride = override;
    }

    public void setLoader(YamlDefinitionLoader loader) {
        this.loader = loader;
    }

    public void setTradeStateRepository(com.storynpcs.persistence.TradeStateRepository tradeStateRepository) {
        this.tradeStateRepository = tradeStateRepository;
    }

    /** Read-only runtime tunable view backing dialogue/script budget consumers. */
    public com.storynpcs.admin.RuntimeTunablesView runtimeTunables() {
        return runtimeTunables.readOnlyView();
    }

    /** Wiring-time injection of the shared store — must run before sessions start. */
    public void setRuntimeTunables(com.storynpcs.admin.RuntimeTunables runtimeTunables) {
        this.runtimeTunables = java.util.Objects.requireNonNull(runtimeTunables, "runtimeTunables");
    }

    /**
     * Canonical runtime-config mutation: authorization + expected-revision
     * staging through {@link com.storynpcs.admin.ConfigTransaction}. A stale
     * revision or failed validation rolls the transaction back — the live
     * tunable map is never partially applied.
     */
    public CanonicalMutationResult mutateRuntimeTunables(
            MutationRequest request, java.util.Map<String, String> changes) {
        Objects.requireNonNull(request, "request");
        return mutateRuntimeTunables(request, changes, AuthorizationPolicy.evaluate(request));
    }

    /** Remote API writes fail closed until server-owned capability sessions are integrated. */
    public CanonicalMutationResult mutateRuntimeTunables(
            MutationRequest request, java.util.Map<String, String> changes,
            com.storynpcs.admin.RemoteAccessProof proof, long nowTick) {
        Objects.requireNonNull(request, "request");
        return mutateRuntimeTunables(request, changes,
                AuthorizationPolicy.evaluateRemote(request, proof, nowTick));
    }

    private CanonicalMutationResult mutateRuntimeTunables(
            MutationRequest request, java.util.Map<String, String> changes,
            AuthorizationDecision authorization) {
        synchronized (canonicalMutationLock) {
            if (!authorization.allowed()) {
                ValidationResult denied = ValidationResult.valid();
                denied.addError(authorization.code(), authorization.message());
                var result = new CanonicalMutationResult(false, false, runtimeTunables.revision(), denied,
                        List.of(request.operation() + ":authorization-denied"), "REJECTED_AUTHORIZATION");
                publishCanonicalEvent(request, result);
                return result;
            }
            var outcome = runtimeTunables.applyChanges(changes, request.expectedRevision());
            if (!outcome.committed()) {
                boolean stale = outcome.detail() != null && outcome.detail().startsWith("stale revision");
                String code = stale ? "STALE_REVISION" : "VALIDATION_FAILED";
                ValidationResult failure = ValidationResult.valid();
                failure.addError(code, outcome.detail());
                var result = new CanonicalMutationResult(false, false, runtimeTunables.revision(), failure,
                        List.of(request.operation() + ":rejected"), code);
                publishCanonicalEvent(request, result);
                return result;
            }
            var result = new CanonicalMutationResult(true, false, outcome.newRevision(),
                    ValidationResult.valid(), List.of("RuntimeConfigChangeEvent"), "COMMITTED");
            publishCanonicalEvent(request, result);
            return result;
        }
    }

    private volatile com.storynpcs.domain.quest.QuestMailStore questMailStore;

    public void setQuestMailStore(com.storynpcs.domain.quest.QuestMailStore questMailStore) {
        this.questMailStore = questMailStore;
    }

    /** Shared-party progression store (P5-5); null degrades team ops to a typed denial. */
    private volatile com.storynpcs.domain.quest.TeamProgressionStore teamProgressionStore;

    public void setTeamProgressionStore(com.storynpcs.domain.quest.TeamProgressionStore store) {
        this.teamProgressionStore = store;
    }

    public com.storynpcs.domain.quest.TeamProgressionStore getTeamProgressionStore() {
        return teamProgressionStore;
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
        this.transportOperationJournal = new com.storynpcs.persistence.DurableOperationJournal(
                progressionRepository.storageDirectory().resolve("transport-operations"));
    }

    // ==========================================
    // 1. NPC Lifecycle Operations
    // ==========================================

    public void createNpc(NpcDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (definition.getId() == null) {
            throw new IllegalArgumentException("NPC definition must have an ID");
        }
        var abilities = definition.getAbilities();
        for (int i = 0; i < abilities.size(); i++) {
            try {
                var ability = abilities.get(i);
                if (ability == null) {
                    throw new IllegalStateException("null ability");
                }
                ability.validate();
            } catch (RuntimeException abilityFailure) {
                throw new IllegalArgumentException(
                        "Invalid ability #" + i + ": " + abilityFailure.getMessage());
            }
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

        // Load-path parity: the loader rejects these shapes, so the canonical
        // write path must too — otherwise a malformed definition would persist
        // and "work" live, then fail on the next reload and silently remove
        // the NPC from the registry.
        if (definition.getJob() != null) {
            try {
                definition.getJob().validate();
            } catch (RuntimeException jobFailure) {
                result.addError("JOB_CONFIG_INVALID",
                        "Invalid job configuration: " + jobFailure.getMessage());
                return result;
            }
        }
        if (definition.getTrader() != null) {
            var listings = definition.getTrader().getListings();
            for (int i = 0; i < listings.size(); i++) {
                var listing = listings.get(i);
                try {
                    if (listing == null) {
                        throw new IllegalStateException("null listing");
                    }
                    listing.validate();
                } catch (RuntimeException listingFailure) {
                    String label = listing != null && !listing.getListingId().isBlank()
                            ? listing.getListingId() : "?";
                    result.addError("TRADE_LISTING_INVALID",
                            "Invalid trade listing #" + i + " (" + label
                                    + "): " + listingFailure.getMessage());
                }
            }
            if (result.hasErrors()) {
                return result;
            }
        }
        {
            // #147: same bounded-ability contract as the load path — a
            // malformed ability must never persist through canonical writes.
            var abilities = definition.getAbilities();
            for (int i = 0; i < abilities.size(); i++) {
                var ability = abilities.get(i);
                try {
                    if (ability == null) {
                        throw new IllegalStateException("null ability");
                    }
                    ability.validate();
                } catch (RuntimeException abilityFailure) {
                    result.addError("ABILITY_INVALID",
                            "Invalid ability #" + i + ": " + abilityFailure.getMessage());
                }
            }
            if (result.hasErrors()) {
                return result;
            }
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
            boolean rollbackIncomplete = diagnostics.getErrors().stream()
                    .anyMatch(error -> "ROLLBACK_INCOMPLETE".equals(error.code()));
            CanonicalMutationResult result = new CanonicalMutationResult(
                    !diagnostics.hasErrors(), false, revision, diagnostics,
                    List.of(request.operation() + (diagnostics.hasErrors() ? ":rejected" : ":applied")),
                    rollbackIncomplete ? "ROLLBACK_INCOMPLETE"
                            : diagnostics.hasErrors() ? "REJECTED_NO_SIDE_EFFECTS" : "COMMITTED");
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

    private record CompletedQuestCompletion(
            String payloadFingerprint, QuestCompletionResult result) {}

    private record CompletedProgressionAction(String fingerprint, AuthorizedActionResult result) {}



    private record CompletedFollowerMutation(
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

    private static Object[] createMutationLocks() {
        Object[] locks = new Object[MUTATION_LOCK_STRIPES];
        Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    private void publishCanonicalEvent(MutationRequest request, CanonicalMutationResult result) {
        eventPublisher.publish(new CanonicalMutationEvent(
                request.operation(), request.actorType(), request.targetId(), request.requestId(),
                result.applied(), result.revision(), result.recoveryOutcome()));
    }

    /**
     * Shared pipeline for {@link PlayerProgressionActionRequest} operations
     * (issue #54): request-id replay dedup, authorization (capability, actor
     * rules, and operation binding to the invoked method), then the mutation.
     * Every attempt — allowed, denied, or replayed — publishes a
     * {@link CanonicalMutationEvent} so denials stay observable. Denied
     * requests are never journaled, so privilege changes apply on retry.
     */
    private AuthorizedActionResult runProgressionAction(
            PlayerProgressionActionRequest request, String expectedOperation,
            String fingerprintKey, NamespacedId eventTarget,
            java.util.function.Supplier<AuthorizedActionResult> action) {
        String fingerprint = request.playerUuid() + "|" + expectedOperation + "|" + fingerprintKey;
        Object requestLock = progressionActionLocks[request.requestId().hashCode()
                & (progressionActionLocks.length - 1)];
        AuthorizedActionResult result;
        synchronized (requestLock) {
            CompletedProgressionAction prior = completedProgressionActions.get(request.requestId());
            if (prior != null) {
                if (!expectedOperation.equals(request.operation())) {
                    result = AuthorizedActionResult.denied(AuthorizationDecision.deny(
                            "OPERATION_MISMATCH",
                            "Request operation '" + request.operation()
                                    + "' does not match " + expectedOperation));
                } else {
                    result = prior.fingerprint().equals(fingerprint)
                            ? AuthorizedActionResult.replayOf(prior.result())
                            : AuthorizedActionResult.denied(AuthorizationDecision.deny(
                                    "REQUEST_PAYLOAD_MISMATCH",
                                    "Request ID is already bound to a different progression action"));
                }
            } else {
                AuthorizationDecision decision = AuthorizationPolicy.evaluate(request);
                if (decision.allowed() && !expectedOperation.equals(request.operation())) {
                    decision = AuthorizationDecision.deny("OPERATION_MISMATCH",
                            "Request operation '" + request.operation()
                                    + "' does not match " + expectedOperation);
                }
                if (decision.allowed()) {
                    try {
                        result = action.get();
                    } catch (RuntimeException unavailable) {
                        // A quarantined/unreadable progression record throws out
                        // of the store — convert it to a typed denial so the
                        // boundary still returns a result and emits its audit
                        // event instead of escaping the adapter.
                        result = AuthorizedActionResult.denied(AuthorizationDecision.deny(
                                "PROGRESSION_UNAVAILABLE",
                                "Player progression data is unavailable: " + unavailable.getMessage()));
                    }
                } else {
                    result = AuthorizedActionResult.denied(decision);
                }
                // Infrastructure failures are not terminal outcomes — memoizing
                // them would replay the phantom failure to in-process retries.
                // The durable marker rolled back, so a retry must re-execute.
                if (decision.allowed() && !isNonTerminalOutcome(result)) {
                    completedProgressionActions.put(request.requestId(),
                            new CompletedProgressionAction(fingerprint, result));
                }
            }
        }
        publishProgressionActionEvent(request, eventTarget, result.applied(), result);
        return result;
    }

    /** Infrastructure failures (commit write, unreadable record) must not be memoized — they are not business outcomes and a retry must re-evaluate. */
    private static boolean isNonTerminalOutcome(AuthorizedActionResult result) {
        return !result.applied() && result.decision() != null && !result.decision().allowed()
                && ("PROGRESSION_COMMIT_FAILED".equals(result.decision().code())
                        || "PROGRESSION_UNAVAILABLE".equals(result.decision().code()));
    }

    private void publishProgressionActionEvent(PlayerProgressionActionRequest request,
                                               NamespacedId targetId, boolean applied,
                                               AuthorizedActionResult result) {
        String outcome = !result.decision().allowed() ? result.decision().code()
                : result.duplicate() ? "REPLAYED"
                : applied ? "COMMITTED" : "REJECTED_NO_SIDE_EFFECTS";
        dispatchQuestEvents(request.playerUuid(), List.of(new CanonicalMutationEvent(
                request.operation(), request.actorType(), targetId, request.requestId(),
                applied, 0L, outcome, request.actorId(), request.playerUuid())));
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

    public void updateNpcInventory(NamespacedId id, com.storynpcs.domain.npc.NpcInventory inventory) {
        requireSuccessfulMutation(mutateNpc(id, npc -> npc.setInventory(
                Objects.requireNonNull(inventory, "inventory"))));
    }

    /** Legacy adapter: flat item ids map onto the visible drop slots at 100% chance. */
    public void updateNpcInventory(NamespacedId id, List<String> itemIds) {
        com.storynpcs.domain.npc.NpcInventory inventory = new com.storynpcs.domain.npc.NpcInventory();
        int slot = 0;
        for (String itemId : itemIds) {
            if (slot >= com.storynpcs.domain.npc.NpcInventory.VISIBLE_DROP_SLOTS) break;
            inventory.setDrop(slot++, com.storynpcs.domain.npc.NpcItemStack.single(
                    NamespacedId.of(itemId)), 100);
        }
        updateNpcInventory(id, inventory);
    }

    public void setNpcMarks(NamespacedId id, List<NpcMark> marks) {
        requireSuccessfulMutation(mutateNpc(id, npc -> npc.setMarks(marks)));
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

        // Progression record is materialized before the availability gate —
        // denied opens still cache it (it is needed to evaluate FACTION_* /
        // QUEST_* conditions either way, and persists at the next saveAll).
        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        // Graph-level availability (the target's Dialog.availability) gates the
        // open itself — fail closed, deny is observable, no session is created.
        if (!evalConditions(graph.getAvailability(), progression, null)) {
            eventPublisher.publish(new DialogueOpenDeniedEvent(playerUuid, dialogueId, "AVAILABILITY"));
            return DialogueView.closed(dialogueId);
        }

        // A new open supersedes any prior session: tear it down first so its
        // tokens are revoked and a DialogueClosedEvent fires (P3 — previously
        // the old session was overwritten silently, orphaning its tokens).
        DialogueSession existing = activeSessions.get(playerUuid);
        if (existing != null) {
            endSession(playerUuid, existing, DialogueClosedEvent.Reason.SERVER_CLOSE);
        }

        DialogueSession session = new DialogueSession(playerUuid, graph, npcEntityUuid, dimensionId, originX, originY, originZ);
        session.setNpcDisplayName(resolveNpcDisplayName(npcEntityUuid));
        activeSessions.put(playerUuid, session);

        // Self-scoped "dialogue" request is always allowed; the visit record is
        // session bookkeeping and its result is intentionally not consulted.
        recordDialogueVisitChecked(new PlayerProgressionActionRequest(
                "dialogue.visit.record", "dialogue", playerUuid, playerUuid,
                UUID.randomUUID(), -1), dialogueId, session.getCurrentNodeId(), progression);

        eventPublisher.publish(new DialogueOpenEvent(playerUuid, dialogueId, session.getCurrentNodeId()));
        return buildDialogueView(session, progression);
    }

    public Optional<DialogueSession> getActiveSession(UUID playerUuid) {
        return Optional.ofNullable(activeSessions.get(playerUuid));
    }

    /**
     * Token-canonical choice entry point (P5-2). The client echoes an opaque
     * token issued with the rendered view; accepting it is a single-use atomic
     * consume bound to session/player/dialogue/node/choice/revision/expiry.
     * Malformed, consumed, expired, foreign, or stale tokens are rejected and
     * the session is closed — there is no accept-by-index path at this boundary.
     */
    public DialogueView chooseDialogueOption(UUID playerUuid, String choiceTokenText) {
        DialogueSession session = activeSessions.get(playerUuid);
        if (session == null || !session.isActive()) {
            return DialogueView.closed(session != null ? session.getDialogueId() : null);
        }
        DialogueNode currentNode = session.getCurrentNode();
        if (currentNode == null) {
            endSession(playerUuid, session, DialogueClosedEvent.Reason.SERVER_CLOSE);
            return DialogueView.closed(session.getDialogueId());
        }

        DialogueChoiceProtocol.ChoiceToken token = parseChoiceToken(choiceTokenText);
        long revision = currentRevision("dialogue", session.getDialogueId());
        DialogueChoiceProtocol.AcceptOutcome outcome = choiceProtocol.accept(
                token, session.getSessionId(), playerUuid, revision,
                session.isActive(), choiceTickSource.getAsLong());
        if (!(outcome instanceof DialogueChoiceProtocol.AcceptOutcome.Accepted accepted)) {
            rejectChoice(playerUuid, session, currentNode.getId(),
                    ((DialogueChoiceProtocol.AcceptOutcome.Rejected) outcome).reason().name());
            return DialogueView.closed(session.getDialogueId());
        }
        // The token binds the node it was issued from; a moved-on session can
        // never legitimately replay it.
        if (!accepted.nodeId().equals(currentNode.getId())) {
            rejectChoice(playerUuid, session, currentNode.getId(),
                    DialogueChoiceProtocol.RejectReason.STALE_REVISION.name());
            return DialogueView.closed(session.getDialogueId());
        }

        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        List<DialogueEdge> availableEdges = getAvailableEdges(currentNode, progression, session);
        List<String> keys = edgeChoiceKeys(currentNode, availableEdges);
        int index = keys.indexOf(accepted.choiceKey());
        if (index < 0) {
            // The choice the token authorized no longer exists (progression or
            // onceOnly filtering moved on) — fail closed rather than guess.
            rejectChoice(playerUuid, session, currentNode.getId(), "CHOICE_UNAVAILABLE");
            return DialogueView.closed(session.getDialogueId());
        }
        return advanceAlongEdge(session, currentNode, availableEdges.get(index), progression, index);
    }

    /**
     * Index-addressed choice for server-side callers (commands, tests). The
     * index only selects among the tokens the last rendered view issued —
     * authorization still goes through the token protocol, so a stale index or
     * a second submission of the same choice is rejected exactly like the
     * network path.
     */
    public DialogueView chooseDialogueOption(UUID playerUuid, int optionIndex) {
        DialogueSession session = activeSessions.get(playerUuid);
        if (session == null || !session.isActive()) {
            return DialogueView.closed(session != null ? session.getDialogueId() : null);
        }
        List<DialogueChoiceProtocol.ChoiceToken> issued = session.getIssuedTokens();
        if (optionIndex < 0 || optionIndex >= issued.size()) {
            rejectChoice(playerUuid, session,
                    session.getCurrentNode() != null ? session.getCurrentNode().getId() : "",
                    DialogueChoiceProtocol.RejectReason.UNKNOWN_TOKEN.name());
            return DialogueView.closed(session.getDialogueId());
        }
        return chooseDialogueOption(playerUuid, issued.get(optionIndex).value().toString());
    }

    /** Shared edge-effect path for every accepted choice. */
    private DialogueView advanceAlongEdge(DialogueSession session, DialogueNode currentNode,
                                          DialogueEdge chosen, PlayerProgression progression,
                                          int optionIndex) {
        UUID playerUuid = session.getPlayerUuid();
        String fromNodeId = currentNode.getId();
        String toNodeId = chosen.getTargetNodeId();

        if (chosen.isOnceOnly()) {
            session.recordOptionSelection(fromNodeId + "->" + toNodeId);
        }
        if (chosen.getActions() != null) {
            for (DialogueAction action : chosen.getActions()) {
                executeAction(playerUuid, action);
            }
        }
        if (!session.isActive()) {
            return DialogueView.closed(session.getDialogueId());
        }
        eventPublisher.publish(new DialogueOptionSelectEvent(playerUuid, session.getDialogueId(),
                fromNodeId, toNodeId, optionIndex));
        session.advanceTo(toNodeId);
        // Self-scoped "dialogue" request is always allowed; result intentionally unused.
        recordDialogueVisitChecked(new PlayerProgressionActionRequest(
                "dialogue.visit.record", "dialogue", playerUuid, playerUuid,
                UUID.randomUUID(), -1), session.getDialogueId(), toNodeId, progression);

        // buildDialogueView tears down terminal sessions itself (GRAPH_END).
        return buildDialogueView(session, progression);
    }

    private static DialogueChoiceProtocol.ChoiceToken parseChoiceToken(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return new DialogueChoiceProtocol.ChoiceToken(UUID.fromString(text.trim()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Records a player's dialogue-node visit into durable progression on the
     * already-held {@link PlayerProgression}. Writes through only when the
     * visit is new, so repeat navigation does not rewrite the store. Visits are
     * keyed per dialogue ({@code dialogueId#nodeId}) so node ids shared across
     * dialogues do not collide. Returns whether the node was newly recorded.
     */
    private boolean recordDialogueNodeVisitInternal(
            UUID playerUuid, PlayerProgression progression, NamespacedId dialogueId, String nodeId) {
        if (!playerUuid.equals(progression.getPlayerUuid())) {
            throw new IllegalArgumentException(
                    "progression instance does not belong to the request subject");
        }
        synchronized (progression) {
            if (progression.hasVisitedDialogueNode(dialogueId, nodeId)) {
                return false;
            }
            progression.recordDialogueNodeVisit(dialogueId, nodeId);
            saveProgression(playerUuid, progression);
            return true;
        }
    }

    /**
     * Evaluates authorization then applies the visit to the supplied progression
     * instance, so session paths reuse the object already fetched for the view.
     */
    private AuthorizedActionResult recordDialogueVisitChecked(
            PlayerProgressionActionRequest request, NamespacedId dialogueId, String nodeId,
            PlayerProgression progression) {
        return runProgressionAction(request, "dialogue.visit.record",
                dialogueId + "#" + nodeId, dialogueId,
                () -> AuthorizedActionResult.of(
                        recordDialogueNodeVisitInternal(request.playerUuid(), progression, dialogueId, nodeId)));
    }

    /**
     * Authorization-checked dialogue node-visit recording (issue #51 — the last
     * session-driven progression write that bypassed the typed boundary).
     * Dialogue navigation submits a {@link PlayerProgressionActionRequest} with
     * actor {@code "dialogue"} bound to the visiting player, so visit recording
     * honors the same actor/subject policy as every other player-scoped
     * mutation; denials carry machine-readable codes and are side-effect-free.
     */
    public AuthorizedActionResult recordDialogueNodeVisit(
            PlayerProgressionActionRequest request, NamespacedId dialogueId, String nodeId) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(dialogueId, "dialogueId");
        Objects.requireNonNull(nodeId, "nodeId");
        return runProgressionAction(request, "dialogue.visit.record",
                dialogueId + "#" + nodeId, dialogueId, () -> {
            // Boundary hygiene: only nodes that exist in the named dialogue may
            // be recorded — a privileged caller cannot persist arbitrary keys.
            boolean nodeExists = registry.getDialogue(dialogueId)
                    .flatMap(graph -> graph.getNode(nodeId))
                    .isPresent();
            if (!nodeExists) {
                return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                        "DIALOGUE_NODE_NOT_FOUND",
                        "Dialogue " + dialogueId + " has no node '" + nodeId + "'."));
            }
            PlayerProgression progression = progressionRepository.getOrCreate(request.playerUuid());
            return AuthorizedActionResult.of(
                    recordDialogueNodeVisitInternal(request.playerUuid(), progression, dialogueId, nodeId));
        });
    }

    private void rejectChoice(UUID playerUuid, DialogueSession session, String nodeId, String reason) {
        eventPublisher.publish(new DialogueChoiceRejectedEvent(
                playerUuid, session.getDialogueId(), nodeId, reason));
        endSession(playerUuid, session, DialogueClosedEvent.Reason.SERVER_CLOSE);
    }

    /** Session teardown: close, remove, revoke outstanding choice tokens, notify. */
    private void endSession(UUID playerUuid, DialogueSession session, DialogueClosedEvent.Reason reason) {
        session.close();
        // Identity remove: a stale handle must not evict a newer session that
        // replaced this one in the map.
        activeSessions.remove(playerUuid, session);
        choiceProtocol.revokeSession(session.getSessionId());
        eventPublisher.publish(new DialogueClosedEvent(
                playerUuid, session.getDialogueId(), session.getCurrentNodeId(), reason));
    }

    /**
     * Re-evaluates every live session pinned to a dialogue whose definition
     * just changed (canonical mutate/replace/delete) or was reloaded
     * (definitions reload). Sessions whose dialogue vanished, lost their
     * current node, or now fail graph-level availability are closed with
     * {@code SERVER_CLOSE}; surviving sessions rebind to the new graph
     * instance so they never walk a superseded definition. Emits
     * {@link DialogueReloadedEvent} with the affected/closed counts.
     */
    private void notifyDialogueReloaded(NamespacedId dialogueId) {
        List<DialogueSession> sessions = activeSessions.values().stream()
                .filter(s -> s.isActive() && dialogueId.equals(s.getDialogueId()))
                .toList();
        DialogueGraph fresh = registry.getDialogue(dialogueId).orElse(null);
        int closed = 0;
        for (DialogueSession session : sessions) {
            // The snapshot was taken before any ClosedEvent listeners ran — a
            // synchronous listener may already have ended this session.
            if (!session.isActive()) {
                continue;
            }
            try {
                PlayerProgression progression = progressionRepository.getOrCreate(session.getPlayerUuid());
                boolean keep = fresh != null
                        && fresh.getNodes().containsKey(session.getCurrentNodeId())
                        && evalConditions(fresh.getAvailability(), progression, session);
                if (keep) {
                    session.rebindGraph(fresh);
                } else {
                    endSession(session.getPlayerUuid(), session, DialogueClosedEvent.Reason.SERVER_CLOSE);
                    closed++;
                }
            } catch (RuntimeException e) {
                // Fail closed per session: a corrupt progression record or a
                // rebind fault must not abort re-evaluation of the remaining
                // sessions or suppress the reload event — the mutation already
                // committed, so half-processed is the worst honest state.
                if (session.isActive()) {
                    endSession(session.getPlayerUuid(), session, DialogueClosedEvent.Reason.SERVER_CLOSE);
                }
                closed++;
            }
        }
        eventPublisher.publish(new DialogueReloadedEvent(dialogueId, sessions.size(), closed));
    }

    /**
     * Definitions-reload hook (e.g. {@code /storynpcs reload}): the staging
     * registry was swapped wholesale, so every dialogue with a live session is
     * re-evaluated — the swap carries no per-definition diff. The revision of
     * each touched dialogue is bumped first so choice tokens issued against a
     * pre-swap definition become STALE_REVISION and fail closed instead of
     * re-resolving against the new graph's edge list.
     */
    public void notifyDialogueDefinitionsReloaded() {
        Set<NamespacedId> ids = activeSessions.values().stream()
                .map(DialogueSession::getDialogueId)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        for (NamespacedId id : ids) {
            definitionRevisions.merge(revisionKey("dialogue", id), 1L, Long::sum);
            notifyDialogueReloaded(id);
        }
    }

    /** Test seam: bind the choice-token clock (e.g., a controlled tick counter). */
    public void setChoiceTickSource(java.util.function.LongSupplier source) {
        choiceTickSource = Objects.requireNonNull(source, "source");
    }

    /**
     * Stable per-edge choice keys aligned to {@code edges}. Two edges may share
     * a target node, so the key carries an ordinal among same-target edges —
     * the issued token can always be re-resolved to exactly one edge while the
     * edge list is unchanged.
     */
    private static List<String> edgeChoiceKeys(DialogueNode node, List<DialogueEdge> edges) {
        Map<String, Integer> ordinals = new HashMap<>();
        List<String> keys = new ArrayList<>(edges.size());
        for (DialogueEdge edge : edges) {
            String pair = edge.getTargetNodeId();
            int ordinal = ordinals.merge(pair, 1, Integer::sum) - 1;
            keys.add(node.getId() + "->" + pair + "#" + ordinal);
        }
        return keys;
    }

    public void closeDialogue(UUID playerUuid) {
        DialogueSession session = activeSessions.get(playerUuid);
        if (session != null) {
            endSession(playerUuid, session, DialogueClosedEvent.Reason.PLAYER_EXIT);
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
    public ValidationResult saveDialogue(NamespacedId expectedId, DialogueGraph graph) {
        synchronized (canonicalMutationLock) {
            return saveDialogueUnderCanonicalLock(expectedId, graph);
        }
    }

    private synchronized ValidationResult saveDialogueUnderCanonicalLock(
            NamespacedId expectedId, DialogueGraph graph) {
        Objects.requireNonNull(expectedId, "expectedId");
        Objects.requireNonNull(graph, "graph");
        ValidationResult result = ValidationResult.valid();

        if (graph.getId() == null || !graph.getId().equals(expectedId)) {
            result.addError("GRAPH_ID_MISMATCH",
                    "Graph id '" + graph.getId() + "' does not match requested dialogue '" + expectedId + "'");
            return result;
        }

        // Structural graph validation: cycles, unreachable nodes, dangling edges
        // must fail closed before cross-reference or persistence work runs.
        result.merge(new com.storynpcs.domain.dialogue.DialogueGraphValidator().validate(graph));
        if (result.hasErrors()) {
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
        CanonicalMutationResult result = executeCanonicalMutation(request, "dialogue", "mutate", () -> {
            DialogueGraph current = registry.getDialogue(request.targetId())
                    .orElseThrow(() -> new NoSuchElementException("Dialogue not found: " + request.targetId()));
            DialogueGraph working = DialogueGraphSerde.fromJson(DialogueGraphSerde.toJson(current))
                    .orElseThrow(() -> new IllegalStateException("Unable to copy dialogue for mutation: " + request.targetId()));
            mutation.accept(working);
            if (!request.targetId().equals(working.getId())) {
                ValidationResult validation = ValidationResult.valid();
                validation.addError("TARGET_ID_MISMATCH", "Dialogue mutation cannot change the request target ID");
                return validation;
            }
            DialogueGraph committed = DialogueGraphSerde.fromJson(DialogueGraphSerde.toJson(working))
                    .orElseThrow(() -> new IllegalStateException("Unable to detach dialogue mutation result: " + request.targetId()));
            return saveDialogue(request.targetId(), committed);
        });
        if (result.newlyApplied()) {
            notifyDialogueReloaded(request.targetId());
        }
        return result;
    }

    /** Canonical full-graph replacement used by packet adapters after decoding detached input. */
    public CanonicalMutationResult replaceDialogue(MutationRequest request, DialogueGraph replacement) {
        Objects.requireNonNull(replacement, "replacement");
        String payloadJson = DialogueGraphSerde.toJson(replacement);
        DialogueGraph payload = DialogueGraphSerde.fromJson(payloadJson)
                .orElseThrow(() -> new IllegalArgumentException("Unable to snapshot dialogue replacement payload"));
        CanonicalMutationResult result = executeCanonicalMutation(request, "dialogue", "replace",
                MutationPayloadFingerprint.ofJson("dialogue.replace", payloadJson), () -> {
                    if (!request.targetId().equals(payload.getId())) {
                        ValidationResult validation = ValidationResult.valid();
                        validation.addError("TARGET_ID_MISMATCH", "Replacement ID does not match request target");
                        return validation;
                    }
                    return saveDialogue(request.targetId(), payload);
                });
        if (result.newlyApplied()) {
            notifyDialogueReloaded(request.targetId());
        }
        return result;
    }

    /**
     * Persists a quest definition: validates it against a snapshot of the live registry
     * (so prerequisite/faction references must resolve), writes YAML atomically, then
     * registers it live. Canonical mutation path for `/storynpcs quest create/set/
     * objective/reward` — mirrors {@link #saveNpc}.
     *
     * @return validation result; on errors nothing is written or registered.
     */
    public ValidationResult saveQuest(Quest quest) {
        synchronized (canonicalMutationLock) {
            return saveQuestUnderCanonicalLock(quest);
        }
    }

    private synchronized ValidationResult saveQuestUnderCanonicalLock(Quest quest) {
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
        // Load-path parity: the loader fails quest-graph dependency cycles /
        // dangling prerequisites — the write path must not admit them either.
        result.merge(new com.storynpcs.domain.quest.QuestDependencyValidator()
                .validate(new java.util.ArrayList<>(snapshot.getAllQuests())));
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
    public ValidationResult saveFaction(Faction faction) {
        synchronized (canonicalMutationLock) {
            return saveFactionUnderCanonicalLock(faction);
        }
    }

    private synchronized ValidationResult saveFactionUnderCanonicalLock(Faction faction) {
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

    /**
     * Canonical template save — mirrors {@link #saveFaction}: id + schema
     * invariants checked (load-path parity: the loader rejects missing ids,
     * unsupported schemaVersions, and templates without an embedded NPC
     * definition), atomically persisted, then registered with a revision bump.
     *
     * @return validation result; on errors nothing is written or registered.
     */
    public ValidationResult saveTemplate(com.storynpcs.creator.template.NpcTemplate template) {
        Objects.requireNonNull(template, "template");
        if (template.getId() == null) {
            ValidationResult result = ValidationResult.valid();
            result.addError("TEMPLATE_ID_MISSING", "Template must have an ID");
            return result;
        }
        return saveTemplate(new MutationRequest(
                "template.replace", "adapter", "template.mutate", template.getId(),
                definitionRevisions.getOrDefault(revisionKey("template", template.getId()), 0L),
                UUID.randomUUID()), template).diagnostics();
    }

    /**
     * Typed, replay-safe template save (upsert) — mirrors {@link #replaceNpc}.
     * The request binds actor, capability, target id, and expected revision; the
     * payload fingerprint covers the full template (id, schema, revision,
     * description, tags, embedded definition) so an identical retry replays the
     * recorded result instead of re-writing YAML and re-registering.
     */
    public CanonicalMutationResult saveTemplate(MutationRequest request,
            com.storynpcs.creator.template.NpcTemplate template) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(template, "template");
        var payload = detachedTemplateCopy(template);
        return executeCanonicalMutation(request, "template", "replace",
                MutationPayloadFingerprint.ofFields("template.replace", canonicalTemplateFields(payload)),
                () -> {
                    if (payload.getId() == null) {
                        ValidationResult result = ValidationResult.valid();
                        result.addError("TEMPLATE_ID_MISSING", "Template must have an ID");
                        return result;
                    }
                    if (!request.targetId().equals(payload.getId())) {
                        ValidationResult result = ValidationResult.valid();
                        result.addError("TARGET_ID_MISMATCH", "Template ID does not match request target");
                        return result;
                    }
                    return saveTemplateUnderCanonicalLock(payload);
                });
    }

    /**
     * Replay-safe, revision-checked template deletion — mirrors
     * {@link #deleteQuest(MutationRequest)}. Orphaned dependent spawners are
     * surfaced as a diagnostic warning; callers that need the full
     * {@code DeleteOutcome} use {@link #deleteTemplate(NamespacedId)}.
     */
    public CanonicalMutationResult deleteTemplate(MutationRequest request) {
        Objects.requireNonNull(request, "request");
        return executeCanonicalMutation(request, "template", "delete",
                MutationPayloadFingerprint.of("template.delete", request.targetId().toString()), () -> {
                    if (registry.getTemplate(request.targetId()).isEmpty()) {
                        ValidationResult result = ValidationResult.valid();
                        result.addError("TEMPLATE_NOT_FOUND", "Template not found: " + request.targetId());
                        return result;
                    }
                    var outcome = deleteTemplate(request.targetId());
                    if (!outcome.removed()) {
                        ValidationResult failure = ValidationResult.valid();
                        failure.addError(registry.getTemplate(request.targetId()).isPresent()
                                        ? "DEFINITION_DELETE_FAILED" : "TEMPLATE_NOT_FOUND",
                                "Template '" + request.targetId() + "' could not be deleted");
                        return failure;
                    }
                    ValidationResult result = ValidationResult.valid();
                    if (!outcome.dependentSpawners().isEmpty()) {
                        result.addWarning("TEMPLATE_SPAWNERS_ORPHANED",
                                "Deleted template '" + request.targetId()
                                        + "' leaves dependent spawners: " + outcome.dependentSpawners());
                    }
                    return result;
                });
    }

    private static com.storynpcs.creator.template.NpcTemplate detachedTemplateCopy(
            com.storynpcs.creator.template.NpcTemplate source) {
        var copy = new com.storynpcs.creator.template.NpcTemplate();
        copy.setId(source.getId());
        copy.setSchemaVersion(source.getSchemaVersion());
        copy.setRevision(source.getRevision());
        copy.setDescription(source.getDescription());
        copy.setTags(source.getTags());
        if (source.getDefinition() != null) {
            copy.setDefinition(NpcDefinitionSerde.fromJson(NpcDefinitionSerde.toJson(source.getDefinition()))
                    .orElseThrow(() -> new IllegalStateException(
                            "Unable to detach template payload: " + source.getId())));
        }
        return copy;
    }

    /**
     * Labeled, self-describing field sequence bound to a template save request.
     * Labels and length-prefixed digesting make field boundaries unforgeable:
     * sequence equality is exactly semantic payload equality.
     */
    private static java.util.List<String> canonicalTemplateFields(
            com.storynpcs.creator.template.NpcTemplate template) {
        var fields = new java.util.ArrayList<String>();
        fields.add("id");
        fields.add(template.getId() == null ? null : template.getId().toString());
        fields.add("schemaVersion");
        fields.add(Integer.toString(template.getSchemaVersion()));
        fields.add("revision");
        fields.add(Long.toString(template.getRevision()));
        fields.add("description");
        fields.add(template.getDescription());
        fields.add("tags");
        fields.addAll(template.getTags());
        fields.add("definition");
        fields.add(template.getDefinition() == null ? null
                : NpcDefinitionSerde.toJson(template.getDefinition()));
        return fields;
    }

    private synchronized ValidationResult saveTemplateUnderCanonicalLock(
            com.storynpcs.creator.template.NpcTemplate template) {
        Objects.requireNonNull(template, "template");
        ValidationResult result = ValidationResult.valid();

        if (template.getId() == null) {
            result.addError("TEMPLATE_ID_MISSING", "Template must have an ID");
            return result;
        }
        if (template.getSchemaVersion() != com.storynpcs.creator.template.NpcTemplate.SCHEMA_VERSION) {
            result.addError("SCHEMA_VERSION_UNSUPPORTED",
                    "Template schemaVersion " + template.getSchemaVersion()
                            + " is not supported (expected "
                            + com.storynpcs.creator.template.NpcTemplate.SCHEMA_VERSION + ")");
            return result;
        }
        if (template.getDefinition() == null) {
            result.addError("TEMPLATE_MISSING_DEFINITION",
                    "Template must embed an NPC 'definition'");
            return result;
        }

        if (loader != null && loader.getLastLoadedRootPath() != null) {
            try {
                var savedFile = new YamlDefinitionWriter(loader.getDefinitionWriteCoordinator()).writeDefinition(
                        loader.getLastLoadedRootPath(), "templates",
                        YamlDefinitionWriter.fileNameFor(template.getId()), template,
                        loader.getDefinitionFiles("templates", template.getId()));
                loader.recordDefinitionFile("templates", template.getId(), savedFile);
            } catch (IOException e) {
                result.addError("PERSIST_WRITE_FAILED", "Failed to write template file: " + e.getMessage());
                return result;
            }
        }

        registry.registerTemplate(template);
        definitionRevisions.merge(revisionKey("template", template.getId()), 1L, Long::sum);
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
     * Deletes a faction and repairs primary NPC and relationship-matrix references
     * within one canonical operation. Deletion is rejected while other definition
     * references still require manual repair. If a write or delete fails, previously
     * committed definitions are restored before the operation is rejected.
     */
    public CanonicalMutationResult deleteFactionWithRepairs(
            MutationRequest request, NamespacedId fallbackFactionId) {
        Objects.requireNonNull(request, "request");
        String fingerprint = MutationPayloadFingerprint.of("faction.delete.withReferences",
                request.targetId() + "\u0000" + String.valueOf(fallbackFactionId));
        return executeCanonicalMutation(request, "faction", "delete", fingerprint,
                () -> deleteFactionUnderCanonicalLock(request.targetId(), fallbackFactionId));
    }

    private ValidationResult deleteFactionUnderCanonicalLock(
            NamespacedId factionId, NamespacedId fallbackFactionId) {
        if (registry.getFaction(factionId).isEmpty()) {
            ValidationResult missing = ValidationResult.valid();
            missing.addError("FACTION_NOT_FOUND", "Faction not found: " + factionId);
            return missing;
        }
        com.storynpcs.domain.faction.FactionDeletionPlanner.DeletionPlan plan;
        FactionReferenceSnapshots snapshots;
        try {
            plan = planFactionDeletion(factionId, fallbackFactionId);
            snapshots = snapshotFactionReferences(factionId, plan.references());
        } catch (IOException | RuntimeException invalidPlan) {
            ValidationResult failure = ValidationResult.valid();
            failure.addError("FACTION_DELETE_BLOCKED", invalidPlan.getMessage());
            return failure;
        }
        Set<UUID> changedPlayerProgressions = new LinkedHashSet<>();
        try {
            applyFactionReferenceRepairs(factionId, plan.fallbackFactionId(), plan.references());
            applyFactionProgressionRepairs(
                    factionId, snapshots.playerProgressions(), changedPlayerProgressions);
            if (!deleteFactionSource(factionId)) {
                throw new IllegalStateException("Faction YAML source could not be deleted");
            }
            return ValidationResult.valid();
        } catch (IOException | RuntimeException failure) {
            boolean restored = restoreFactionReferenceSnapshots(
                    factionId, snapshots, changedPlayerProgressions);
            ValidationResult rejected = ValidationResult.valid();
            rejected.addError(restored ? "FACTION_DELETE_ROLLED_BACK" : "ROLLBACK_INCOMPLETE",
                    "Faction deletion failed: " + failure.getMessage()
                            + (restored ? "; reference repairs were restored"
                                    : "; some reference repairs could not be restored"));
            return rejected;
        }
    }

    private com.storynpcs.domain.faction.FactionDeletionPlanner.DeletionPlan planFactionDeletion(
            NamespacedId factionId, NamespacedId fallbackFactionId) {
        var planner = new com.storynpcs.domain.faction.FactionDeletionPlanner();
        var plan = planner.plan(factionId, List.copyOf(registry.getAllNpcs()),
                List.copyOf(registry.getAllTemplates()), List.copyOf(registry.getAllFactions()),
                List.copyOf(registry.getAllDialogues()),
                List.copyOf(registry.getAllQuests()), List.copyOf(registry.getAllTransportLocations()),
                fallbackFactionId);
        if (!plan.viable()) {
            throw new IllegalStateException(String.join("\n", plan.diagnostics()));
        }
        return plan;
    }

    private record FactionReferenceSnapshots(
            Map<NamespacedId, NpcDefinition> npcs,
            Map<NamespacedId, Faction> factions,
            Map<UUID, ProgressionRepository.FactionProgressionSnapshot> playerProgressions) {}

    private FactionReferenceSnapshots snapshotFactionReferences(NamespacedId factionId,
            List<com.storynpcs.domain.faction.FactionDeletionPlanner.Reference> references) throws IOException {
        Map<NamespacedId, NpcDefinition> npcSnapshots = new LinkedHashMap<>();
        Map<NamespacedId, Faction> factionSnapshots = new LinkedHashMap<>();
        for (var reference : references) {
            NamespacedId holderId = NamespacedId.of(reference.holderId());
            if (reference.kind().equals("npc-faction")) {
                registry.getNpc(holderId).ifPresent(npc -> npcSnapshots.put(holderId,
                        NpcDefinitionSerde.fromJson(NpcDefinitionSerde.toJson(npc)).orElseThrow()));
            } else if (reference.kind().equals("matrix-entry")) {
                registry.getFaction(holderId).ifPresent(faction -> factionSnapshots.put(holderId,
                        com.storynpcs.domain.faction.FactionSerde.fromJson(
                                com.storynpcs.domain.faction.FactionSerde.toJson(faction)).orElseThrow()));
            }
        }
        return new FactionReferenceSnapshots(npcSnapshots, factionSnapshots,
                progressionRepository.factionProgressionSnapshots(factionId));
    }

    private void applyFactionReferenceRepairs(
            NamespacedId factionId, NamespacedId fallbackFactionId,
            List<com.storynpcs.domain.faction.FactionDeletionPlanner.Reference> references) {
        for (var reference : references) {
            NamespacedId holderId = NamespacedId.of(reference.holderId());
            ValidationResult result;
            if (reference.kind().equals("npc-faction")) {
                NpcDefinition npc = registry.getNpc(holderId)
                        .map(current -> NpcDefinitionSerde.fromJson(NpcDefinitionSerde.toJson(current)).orElseThrow())
                        .orElseThrow(() -> new IllegalStateException("NPC disappeared during faction deletion: " + holderId));
                npc.setFactionId(fallbackFactionId);
                result = saveNpc(npc);
            } else if (reference.kind().equals("matrix-entry")) {
                Faction faction = registry.getFaction(holderId)
                        .map(current -> com.storynpcs.domain.faction.FactionSerde
                                .fromJson(com.storynpcs.domain.faction.FactionSerde.toJson(current)).orElseThrow())
                        .orElseThrow(() -> new IllegalStateException("Faction disappeared during reference repair: " + holderId));
                faction.removeRelationshipTo(factionId);
                result = saveFaction(faction);
            } else {
                continue;
            }
            if (result.hasErrors()) {
                throw new IllegalStateException("reference repair failed for " + holderId + ":\n"
                        + result.formatReport(3));
            }
        }
    }

    private void applyFactionProgressionRepairs(NamespacedId factionId,
            Map<UUID, ProgressionRepository.FactionProgressionSnapshot> playerProgressions,
            Set<UUID> changedPlayerProgressions) throws IOException {
        for (var entry : playerProgressions.entrySet()) {
            UUID playerUuid = entry.getKey();
            ProgressionRepository.FactionProgressionSnapshot snapshot = entry.getValue();
            progressionRepository.withProgression(playerUuid, progression -> {
                Map<NamespacedId, Integer> factionPoints = progression.getFactionPoints();
                if (factionPoints == null || progression.getFactionRevision() != snapshot.revision()
                        || factionPoints.containsKey(factionId) != snapshot.hasFactionPoints()
                        || (snapshot.hasFactionPoints()
                                && !Objects.equals(factionPoints.get(factionId), snapshot.factionPoints()))) {
                    throw new IllegalStateException(
                            "faction reputation changed while deleting " + factionId + " for player " + playerUuid);
                }
                long nextRevision = Math.addExact(snapshot.revision(), 1L);
                factionPoints.remove(factionId);
                progression.setFactionRevision(nextRevision);
                changedPlayerProgressions.add(playerUuid);
                progressionRepository.save(playerUuid, progression);
            });
        }
    }

    private boolean restoreFactionReferenceSnapshots(NamespacedId factionId,
            FactionReferenceSnapshots snapshots, Set<UUID> changedPlayerProgressions) {
        boolean restored = true;
        for (NpcDefinition npc : snapshots.npcs().values()) {
            try {
                if (saveNpc(npc).hasErrors()) restored = false;
            } catch (RuntimeException failure) {
                restored = false;
            }
        }
        for (Faction faction : snapshots.factions().values()) {
            try {
                if (saveFaction(faction).hasErrors()) restored = false;
            } catch (RuntimeException failure) {
                restored = false;
            }
        }
        for (UUID playerUuid : changedPlayerProgressions) {
            var snapshot = snapshots.playerProgressions().get(playerUuid);
            if (snapshot == null) {
                restored = false;
                continue;
            }
            try {
                progressionRepository.withProgression(playerUuid, progression -> {
                    Map<NamespacedId, Integer> factionPoints = progression.getFactionPoints();
                    if (factionPoints == null) {
                        throw new IllegalStateException(
                                "faction reputation map is unavailable for player " + playerUuid);
                    }
                    long currentRevision = progression.getFactionRevision();
                    if (currentRevision < Long.MAX_VALUE) {
                        progression.setFactionRevision(currentRevision + 1L);
                    }
                    if (snapshot.hasFactionPoints()) {
                        factionPoints.put(factionId, snapshot.factionPoints());
                    } else {
                        factionPoints.remove(factionId);
                    }
                    progressionRepository.save(playerUuid, progression);
                });
            } catch (IOException | RuntimeException failure) {
                restored = false;
            }
        }
        return restored;
    }

    /**
     * Canonical template deletion: removes the durable YAML source first
     * (fail-closed — a file that cannot be deleted keeps its registry binding),
     * then the registry binding under the canonical lock. Dependent spawners are
     * surfaced to the caller via the returned outcome rather than silently orphaned.
     */
    public com.storynpcs.creator.template.TemplateLibrary.DeleteOutcome deleteTemplate(NamespacedId id) {
        Objects.requireNonNull(id, "id");
        synchronized (canonicalMutationLock) {
            synchronized (this) {
                if (registry.getTemplate(id).isEmpty()) {
                    return new com.storynpcs.creator.template.TemplateLibrary.DeleteOutcome(false, List.of());
                }
                if (!deleteDefinitionFileBeforeRegistryMutation("templates", id)) {
                    return new com.storynpcs.creator.template.TemplateLibrary.DeleteOutcome(false, List.of());
                }
                var outcome = registry.removeTemplate(id);
                if (outcome.removed()) {
                    definitionRevisions.merge(revisionKey("template", id), 1L, Long::sum);
                }
                return outcome;
            }
        }
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
     * Compatibility delegate for the canonical, reference-aware faction deletion operation.
     */
    public boolean deleteFaction(NamespacedId id) {
        Objects.requireNonNull(id, "id");
        MutationRequest request = new MutationRequest("faction.delete", "system", "faction.delete",
                id, currentRevision("faction", id), UUID.randomUUID(), -1);
        return deleteFaction(request).applied();
    }

    private synchronized boolean deleteFactionSource(NamespacedId id) {
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
        return deleteFactionWithRepairs(request, null);
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
        CanonicalMutationResult result = executeCanonicalMutation(request, "dialogue", "delete",
                MutationPayloadFingerprint.of("dialogue.delete", request.targetId().toString()), () -> {
            if (registry.getDialogue(request.targetId()).isEmpty()) {
                ValidationResult missing = ValidationResult.valid();
                missing.addError("DIALOGUE_NOT_FOUND", "Dialogue not found: " + request.targetId());
                return missing;
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
        if (result.newlyApplied()) {
            notifyDialogueReloaded(request.targetId());
        }
        return result;
    }

    /**
     * Compensates an unreferenced dialogue creation through the canonical delete boundary.
     * The reference check and delete share the canonical mutation lock so another typed
     * definition mutation cannot attach the graph between those two steps.
     */
    public CanonicalMutationResult deleteUnreferencedDialogue(MutationRequest request) {
        Objects.requireNonNull(request, "request");
        CanonicalMutationResult result = executeCanonicalMutation(request, "dialogue", "delete",
                MutationPayloadFingerprint.of("dialogue.delete.unreferenced", request.targetId().toString()), () -> {
            synchronized (this) {
                if (registry.getDialogue(request.targetId()).isEmpty()) {
                    ValidationResult missing = ValidationResult.valid();
                    missing.addError("DIALOGUE_NOT_FOUND", "Dialogue not found: " + request.targetId());
                    return missing;
                }
                List<NamespacedId> references = findNpcsReferencingDialogue(request.targetId());
                if (!references.isEmpty()) {
                    ValidationResult referenced = ValidationResult.valid();
                    referenced.addError("DIALOGUE_REFERENCED",
                            "Dialogue '" + request.targetId() + "' is still referenced by NPCs: " + references);
                    return referenced;
                }
                if (!deleteDialogue(request.targetId())) {
                    ValidationResult failure = ValidationResult.valid();
                    failure.addError("DEFINITION_DELETE_FAILED",
                            "Unreferenced dialogue '" + request.targetId() + "' could not be removed");
                    return failure;
                }
            }
            return ValidationResult.valid();
        });
        if (result.newlyApplied()) {
            notifyDialogueReloaded(request.targetId());
        }
        return result;
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
            // Centralized teardown: every terminal close fires DialogueClosedEvent
            // (GRAPH_END) — including a terminal entry node on open — and the
            // identity-remove cannot evict a different session for this player.
            endSession(session.getPlayerUuid(), session, DialogueClosedEvent.Reason.GRAPH_END);
            return new DialogueView(session.getDialogueId(), node != null ? node.getId() : "",
                    node != null ? node.getText() : "", node != null ? node.getSound() : "", List.of(), true,
                    speaker, List.of(), List.of());
        }

        // VULN-46: pass session so onceOnly options are filtered after first selection
        List<DialogueEdge> availableEdges = getAvailableEdges(node, progression, session);
        List<String> optionTexts = availableEdges.stream().map(DialogueEdge::getText).toList();
        List<String> optionHints = availableEdges.stream().map(this::optionHint).toList();

        // Issue one single-use opaque token per rendered option (P5-2): the
        // client echoes a token, never an index, and the first accept wins.
        List<String> edgeKeys = edgeChoiceKeys(node, availableEdges);
        long nowTick = choiceTickSource.getAsLong();
        long revision = currentRevision("dialogue", session.getDialogueId());
        List<DialogueChoiceProtocol.ChoiceToken> tokens = new ArrayList<>(availableEdges.size());
        List<String> tokenTexts = new ArrayList<>(availableEdges.size());
        for (int i = 0; i < availableEdges.size(); i++) {
            var token = choiceProtocol.issue(session.getSessionId(), session.getPlayerUuid(),
                    session.getDialogueId(), node.getId(), edgeKeys.get(i), revision, nowTick);
            tokens.add(token);
            tokenTexts.add(token.value().toString());
        }
        session.setIssuedTokens(tokens);

        return new DialogueView(session.getDialogueId(), node.getId(), node.getText(), node.getSound(),
                optionTexts, false, speaker, optionHints, tokenTexts);
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
                    QuestProgressState state = progression.peekQuestState(qid);
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
                        CanonicalMutationResult started = mutateQuestProgression(QuestProgressionMutationRequest.start(
                                "dialogue", playerUuid, playerUuid, qid,
                                currentQuestProgressionRevision(playerUuid), UUID.randomUUID()));
                        if (started.hasErrors()) {
                            System.err.println("Warning: Dialogue action START_QUEST failed for " + qid
                                    + ": " + started.formatReport());
                        }
                    } else {
                        System.err.println("Warning: Dialogue action START_QUEST references unknown quest: " + action.getTarget());
                    }
                }
                case ADVANCE_QUEST -> {
                    NamespacedId qid = NamespacedId.of(action.getTarget());
                    if (registry.getQuest(qid).isPresent()) {
                        String obj = (action.getValue() != null && !action.getValue().isBlank()) ? action.getValue().trim() : "obj";
                        CanonicalMutationResult progressed = mutateQuestProgression(QuestProgressionMutationRequest.progress(
                                "dialogue", playerUuid, playerUuid, qid, obj, 1,
                                currentQuestProgressionRevision(playerUuid), UUID.randomUUID()));
                        if (progressed.hasErrors()) {
                            System.err.println("Warning: Dialogue action ADVANCE_QUEST failed for " + qid
                                    + ": " + progressed.formatReport());
                        }
                    }
                }
                case COMPLETE_QUEST -> {
                    NamespacedId qid = NamespacedId.of(action.getTarget());
                    if (registry.getQuest(qid).isPresent()) {
                        completeQuest(new QuestCompletionMutationRequest(
                                "dialogue", playerUuid, playerUuid, qid,
                                currentQuestProgressionRevision(playerUuid), UUID.randomUUID(), -1));
                    } else {
                        System.err.println("Warning: Dialogue action COMPLETE_QUEST references unknown quest: " + action.getTarget());
                    }
                }
                case ADJUST_FACTION -> {
                    NamespacedId fid = NamespacedId.of(action.getTarget());
                    if (registry.getFaction(fid).isPresent()) {
                        int delta = Integer.parseInt(action.getValue() != null ? action.getValue().trim() : "0");
                        CanonicalMutationResult adjusted = mutateFactionProgression(new FactionProgressionMutationRequest(
                                "dialogue", playerUuid, playerUuid, fid,
                                FactionProgressionMutationRequest.Action.ADJUST, delta,
                                currentFactionProgressionRevision(playerUuid), UUID.randomUUID(), -1));
                        if (adjusted.hasErrors()) {
                            System.err.println("Warning: Dialogue action ADJUST_FACTION failed for " + fid
                                    + ": " + adjusted.formatReport());
                        }
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

    /** Applies one typed, player-scoped faction-standing mutation through the canonical boundary. */
    public CanonicalMutationResult mutateFactionProgression(FactionProgressionMutationRequest request) {
        Objects.requireNonNull(request, "request");
        AuthorizationDecision authorization = AuthorizationPolicy.evaluate(request);
        String fingerprint = factionMutationFingerprint(request);
        Object playerLock = progressionMutationLocks[request.playerUuid().hashCode()
                & (progressionMutationLocks.length - 1)];
        if (!authorization.allowed()) {
            CanonicalMutationResult denied = factionMutationFailure(0, authorization.code(), authorization.message());
            QuestEventQueue dispatchQueue;
            synchronized (playerLock) {
                dispatchQueue = enqueueQuestEvents(request.playerUuid(), List.of(factionMutationEvent(request, denied)));
            }
            if (dispatchQueue != null) drainQuestEvents(dispatchQueue);
            return denied;
        }

        Object requestLock = questMutationLocks[request.requestId().hashCode() & (questMutationLocks.length - 1)];
        CanonicalMutationResult result;
        List<StoryNpcsEvent> notifications = new ArrayList<>();
        QuestEventQueue dispatchQueue;
        synchronized (canonicalMutationLock) {
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
                        result = factionMutationFailure(0, "PROGRESSION_UNAVAILABLE",
                                "Player progression is blocked pending durable-state recovery");
                    } else {
                        synchronized (progression) {
                            result = applyFactionProgressionMutation(request, fingerprint, progression, notifications);
                        }
                        // P6-1 team sharing: an applied ADJUST propagates its
                        // post-clamp delta to teammates when the team opted in.
                        // The delta rides the emitted event so clamped values
                        // are what teammates receive, not the requested amount.
                        if (result.applied() && !result.duplicate()
                                && request.action() == FactionProgressionMutationRequest.Action.ADJUST) {
                            // Snapshot: propagation appends member events to
                            // the same list — iterating a live copy is required.
                            for (var event : new ArrayList<>(notifications)) {
                                if (event instanceof FactionReputationChangeEvent fce
                                        && fce.newPoints() != fce.oldPoints()) {
                                    registry.getFaction(request.factionId()).ifPresent(faction ->
                                            propagateTeamFactionDelta(request.playerUuid(),
                                                    request.factionId(), fce.newPoints() - fce.oldPoints(),
                                                    faction, notifications));
                                }
                            }
                        }
                    }
                    if (!result.duplicate()) notifications.add(0, factionMutationEvent(request, result));
                    dispatchQueue = enqueueQuestEvents(request.playerUuid(), notifications);
                }
            }
        }

        if (dispatchQueue != null) drainQuestEvents(dispatchQueue);
        return result;
    }

    private CanonicalMutationResult applyFactionProgressionMutation(
            FactionProgressionMutationRequest request, String fingerprint,
            PlayerProgression progression, List<StoryNpcsEvent> notifications) {
        CompletedFactionMutation completed = completedFactionMutations.get(request.requestId());
        if (completed != null) {
            if (!completed.payloadFingerprint().equals(fingerprint)) {
                return factionMutationFailure(progression.getFactionRevision(),
                        "REQUEST_PAYLOAD_MISMATCH", "Request ID is already bound to a different faction mutation payload");
            }
            CanonicalMutationResult prior = completed.result();
            return new CanonicalMutationResult(prior.applied(), true, prior.revision(),
                    prior.diagnostics(), prior.events(), prior.recoveryOutcome());
        }

        // Durable replay check (issue #57 — P2-3): the in-memory cache dies
        // with the process; the progression ledger survives restart. A recorded
        // marker means the mutation already committed in the same save, so a
        // replayed request id must return that outcome, never re-apply a
        // non-idempotent ADJUST.
        String durableOutcome = progression.appliedActionOutcome(request.requestId());
        if (durableOutcome != null) {
            String[] parts = durableOutcome.split("\n", -1);
            if (!parts[0].equals(fingerprint)) {
                return factionMutationFailure(progression.getFactionRevision(),
                        "REQUEST_PAYLOAD_MISMATCH", "Request ID is already bound to a different faction mutation payload");
            }
            long recordedRevision;
            try {
                recordedRevision = parts.length > 1
                        ? Long.parseLong(parts[1]) : progression.getFactionRevision();
            } catch (NumberFormatException corruptMarker) {
                // Fingerprint matched but the recorded revision is mangled —
                // the ledger entry cannot be trusted, so fail closed rather
                // than re-apply or report a fabricated revision.
                return factionMutationFailure(progression.getFactionRevision(),
                        "LEDGER_CORRUPT", "Recorded outcome for this request ID is malformed");
            }
            String recoveryOutcome = parts.length > 2 ? parts[2] : "COMMITTED";
            List<String> events = parts.length > 3 && !parts[3].isEmpty()
                    ? List.of(parts[3].split(";")) : List.of();
            return new CanonicalMutationResult(true, true, recordedRevision,
                    ValidationResult.valid(), events, recoveryOutcome);
        }

        long currentRevision = progression.getFactionRevision();
        if (currentRevision != request.expectedRevision()) {
            CanonicalMutationResult stale = factionMutationFailure(currentRevision, "STALE_REVISION",
                    "Expected player faction revision " + request.expectedRevision()
                            + " but current revision is " + currentRevision);
            rememberFactionMutation(request, fingerprint, stale);
            return stale;
        }

        Faction faction = registry.getFaction(request.factionId()).orElse(null);
        if (faction == null) {
            CanonicalMutationResult missing = factionMutationFailure(currentRevision, "FACTION_NOT_FOUND",
                    "Faction not found: " + request.factionId());
            rememberFactionMutation(request, fingerprint, missing);
            return missing;
        }

        PlayerProgression snapshot = progression.copy();
        final int oldPoints = progression.getFactionScore(request.factionId(), faction.getDefaultPoints());
        final int newPoints;
        try {
            if (request.action() == FactionProgressionMutationRequest.Action.SET) {
                progression.setFactionScore(request.factionId(), request.amount());
            } else {
                progression.adjustFactionScore(request.factionId(), request.amount(), faction.getDefaultPoints());
            }
            newPoints = progression.getFactionScore(request.factionId(), faction.getDefaultPoints());
            progression.setFactionRevision(Math.addExact(currentRevision, 1L));
            // The replay marker commits inside the same save as the mutation —
            // a failed save rolls the marker back with it via restoreFrom.
            progression.recordAppliedActionRequest(request.requestId(), fingerprint,
                    progression.getFactionRevision() + "\nCOMMITTED\nFactionReputationChangeEvent");
            progressionRepository.save(request.playerUuid(), progression);
        } catch (Exception failure) {
            progression.restoreFrom(snapshot);
            return factionMutationFailure(currentRevision, "PROGRESSION_COMMIT_FAILED",
                    "Could not durably save faction progression: " + failure.getMessage());
        }

        notifications.add(new FactionReputationChangeEvent(request.playerUuid(), request.factionId(), oldPoints, newPoints, request.actorType()));
        CanonicalMutationResult applied = new CanonicalMutationResult(true, false, progression.getFactionRevision(),
                ValidationResult.valid(), List.of("FactionReputationChangeEvent"), "COMMITTED");
        rememberFactionMutation(request, fingerprint, applied);
        return applied;
    }

    private void rememberFactionMutation(FactionProgressionMutationRequest request, String fingerprint,
                                         CanonicalMutationResult result) {
        completedFactionMutations.put(request.requestId(),
                new CompletedFactionMutation(fingerprint, result.snapshot()));
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
                Integer.toString(request.amount()), Long.toString(request.expectedRevision()));
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
        Object playerLock = progressionMutationLocks[subject.hashCode() & (progressionMutationLocks.length - 1)];
        synchronized (canonicalMutationLock) {
            synchronized (playerLock) {
                PlayerProgression progression = progressionRepository.getOrCreate(subject);
                synchronized (progression) {
                    return progression.getFactionRevision();
                }
            }
        }
    }

    public void setFactionPoints(UUID playerUuid, NamespacedId factionId, int points) {
        CanonicalMutationResult result = mutateFactionProgression(FactionProgressionMutationRequest.set(
                "system", null, playerUuid, factionId, points,
                currentFactionProgressionRevision(playerUuid), UUID.randomUUID()));
        if (result.hasErrors()) throw progressionMutationException(result, "Faction not found: " + factionId);
    }

    public void adjustFactionPoints(UUID playerUuid, NamespacedId factionId, int delta) {
        CanonicalMutationResult result = mutateFactionProgression(FactionProgressionMutationRequest.adjust(
                "system", null, playerUuid, factionId, delta,
                currentFactionProgressionRevision(playerUuid), UUID.randomUUID()));
        if (result.hasErrors()) throw progressionMutationException(result, "Faction not found: " + factionId);
    }

    private static RuntimeException progressionMutationException(CanonicalMutationResult result, String notFoundMessage) {
        boolean notFound = result.diagnostics().getErrors().stream()
                .anyMatch(error -> "FACTION_NOT_FOUND".equals(error.code())
                        || "QUEST_NOT_FOUND".equals(error.code()));
        return notFound
                ? new NoSuchElementException(notFoundMessage)
                : new IllegalStateException(result.formatReport());
    }


    // ==========================================
    // Transport Location Operations (issue #72 — transport locations foundation)
    // ==========================================
    //
    // Scope boundary: this defines and validates the destination CONTRACT and
    // per-player UNLOCK STATE only. Executing a live, safe teleport (loaded-chunk
    // check, non-obstructed landing, cross-dimension timeout/recovery) requires a
    // real ServerLevel and is intentionally NOT implemented here.

    /** Creates a transport location definition after validating its destination contract. Internal-only (issue #54): adapters must use the typed {@code MutationRequest} overload. */
    ValidationResult createTransportLocation(
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
        definitionRevisions.merge(revisionKey("transport", location.getId()), 1L, Long::sum);
        return result;
    }

    /**
     * Creates a transport location through the revisioned, authorization-checked
     * canonical request boundary (issue #54 — P1-4 authorization policy coverage).
     * The package-private {@link #createTransportLocation(com.storynpcs.domain.transport.TransportLocation)}
     * overload remains for trusted internal/bootstrap callers; adapters that accept
     * untrusted actor input (commands, packets, scripts) must route through this
     * overload instead so definition mutation authorization is enforced uniformly,
     * matching {@link #createQuest(MutationRequest, String)} and
     * {@link #createFaction(MutationRequest, String)}.
     */
    public CanonicalMutationResult createTransportLocation(
            MutationRequest request, com.storynpcs.domain.transport.TransportLocation location) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(location, "location");
        return executeCanonicalMutation(request, "transport", "create",
                MutationPayloadFingerprint.of("transport.create.id", String.valueOf(location.getId())), () -> {
            if (!request.targetId().equals(location.getId())) {
                ValidationResult result = ValidationResult.valid();
                result.addError("TARGET_ID_MISMATCH", "Transport location mutation cannot change the request target ID");
                return result;
            }
            return createTransportLocation(location);
        });
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
        java.util.Set<NamespacedId> unlocked = unlockedTransportLocations(progression);
        java.util.List<com.storynpcs.domain.transport.TransportLocation> available = new ArrayList<>();
        for (var location : registry.getAllTransportLocations()) {
            if (location.getUnlockConditions().isEmpty() || unlocked.contains(location.getId())) {
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
        if (location.getUnlockConditions().isEmpty()) return true;
        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        return unlockedTransportLocations(progression).contains(locationId);
    }

    /** Unlocks a transport location for a player. Idempotent — unlocking twice is a no-op. Fails if the location doesn't exist. Internal-only (issue #54): adapters must use the typed request overload. */
    boolean unlockTransportLocation(UUID playerUuid, NamespacedId locationId) {
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

    /**
     * Authorization-checked unlock (issue #54 — P1-4 coverage). Adapters that accept
     * untrusted actor input should route through this overload instead of the
     * package-private {@link #unlockTransportLocation(UUID, NamespacedId)}, which
     * remains for trusted internal callers.
     */
    public AuthorizedActionResult unlockTransportLocation(PlayerProgressionActionRequest request, NamespacedId locationId) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(locationId, "locationId");
        return runProgressionAction(request, "transport.unlock", locationId.toString(), locationId,
                () -> applyTransportUnlock(request, locationId));
    }

    /**
     * Transport unlock with durable replay classification (issue #57 — P2-3):
     * the request id and its outcome are recorded in the same progression save
     * that grants the unlock, so a replay after restart returns the recorded
     * outcome instead of re-executing and re-auditing the mutation.
     */
    private AuthorizedActionResult applyTransportUnlock(
            PlayerProgressionActionRequest request, NamespacedId locationId) {
        String fingerprint = request.playerUuid() + "|transport.unlock|" + locationId;
        PlayerProgression progression = progressionRepository.getOrCreate(request.playerUuid());
        synchronized (progression) {
            String recorded = progression.appliedActionOutcome(request.requestId());
            if (recorded != null) {
                String[] parts = recorded.split("\n", 2);
                if (!parts[0].equals(fingerprint)) {
                    return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                            "REQUEST_PAYLOAD_MISMATCH",
                            "Request ID is already bound to a different progression action"));
                }
                boolean applied = parts.length > 1 && Boolean.parseBoolean(parts[1]);
                return AuthorizedActionResult.replayOf(AuthorizedActionResult.of(applied));
            }
            if (registry.getTransportLocation(locationId).isEmpty()) {
                return AuthorizedActionResult.of(false);
            }
            PlayerProgression snapshot = progression.copy();
            try {
                progression.getUnlockedTransportLocations().add(locationId);
                // The replay marker commits inside the same save as the grant —
                // a failed save rolls the marker back with it via restoreFrom so
                // a retry re-executes instead of replaying a phantom success.
                progression.recordAppliedActionRequest(request.requestId(), fingerprint, "true");
                progressionRepository.save(request.playerUuid(), progression);
            } catch (Exception failure) {
                progression.restoreFrom(snapshot);
                return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                        "PROGRESSION_COMMIT_FAILED",
                        "Could not durably save transport unlock: " + failure.getMessage()));
            }
            return AuthorizedActionResult.of(true);
        }
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
    // Issue #54: internal-only primitive — adapters must not call this; tests in
    // this package seed mailbox fixtures with it. Player-facing paths use the
    // typed overloads or deliverQuestMail (self-bound to the interactor).
    com.storynpcs.domain.progression.MailMessage deliverMail(
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
    boolean markMailRead(UUID playerUuid, UUID mailId) {
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

    /**
     * Authorization-checked mail-read (issue #54 — P1-4 coverage). Adapters that
     * accept untrusted actor input should route through this overload instead of
     * the package-private {@link #markMailRead(UUID, UUID)}, which remains for
     * trusted internal callers.
     */
    public AuthorizedActionResult markMailRead(PlayerProgressionActionRequest request, UUID mailId) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(mailId, "mailId");
        return runProgressionAction(request, "mail.read", mailId.toString(), null,
                () -> AuthorizedActionResult.of(markMailRead(request.playerUuid(), mailId)));
    }

    /** Deletes a mail message. Returns false if no message with that ID exists. */
    boolean deleteMail(UUID playerUuid, UUID mailId) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(mailId, "mailId");
        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        synchronized (progression) {
            boolean removed = progression.getMailbox().removeIf(m -> m.getId().equals(mailId));
            if (removed) saveProgression(playerUuid, progression);
            return removed;
        }
    }

    /**
     * Player-facing mail send (issue #150): delivers {@code subject}/{@code body}
     * to {@code recipientUuid}'s mailbox on behalf of the acting player. The
     * recipient is resolved by the adapter (name → known player UUID); this
     * method validates content bounds, applies a per-sender quota against the
     * recipient's mailbox, and appends through the durable mailbox path.
     * The request id is recorded in the sender's durable action ledger, so a
     * replayed request survives restarts instead of double-delivering.
     */
    public AuthorizedActionResult sendMail(PlayerProgressionActionRequest request,
                                           UUID recipientUuid, String senderLabel,
                                           String subject, String body) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(recipientUuid, "recipientUuid");
        if ((senderLabel != null && senderLabel.length() > MAIL_SENDER_MAX_LENGTH)
                || (subject != null && subject.length() > MAIL_SUBJECT_MAX_LENGTH)
                || (body != null && body.length() > MAIL_BODY_MAX_LENGTH)) {
            return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                    "MAIL_CONTENT_TOO_LONG",
                    "Mail sender, subject, or body exceeds the allowed length."));
        }
        String fingerprint = recipientUuid + "|"
                + (subject == null ? -1 : subject.length()) + ":" + subject + "|"
                + (body == null ? -1 : body.length()) + ":" + body;
        return runProgressionAction(request, "mail.send", fingerprint, null,
                () -> applySendMail(request, recipientUuid, senderLabel,
                        subject, body, fingerprint));
    }

    /**
     * Bounds one player's mail footprint in another player's mailbox — without
     * it, the self-scoped {@code mail.send} authorization would let a sender
     * flush a victim's durable mailbox by forcing oldest-first evictions.
     */
    private static final int MAIL_SENDER_QUOTA_PER_RECIPIENT = 8;

    private AuthorizedActionResult applySendMail(
            PlayerProgressionActionRequest request, UUID recipientUuid,
            String senderLabel, String subject, String body, String fingerprint) {
        PlayerProgression senderProgression =
                progressionRepository.getOrCreate(request.playerUuid());
        synchronized (senderProgression) {
            String recorded = senderProgression.appliedActionOutcome(request.requestId());
            if (recorded != null) {
                String[] parts = recorded.split("\n", 2);
                if (!parts[0].equals(fingerprint)) {
                    return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                            "REQUEST_PAYLOAD_MISMATCH",
                            "Request ID is already bound to a different progression action"));
                }
                boolean applied = parts.length > 1 && Boolean.parseBoolean(parts[1]);
                return AuthorizedActionResult.replayOf(AuthorizedActionResult.of(applied));
            }
        }
        PlayerProgression recipient = progressionRepository.getOrCreate(recipientUuid);
        synchronized (recipient) {
            long fromSender = recipient.getMailbox().stream()
                    .filter(m -> Objects.equals(senderLabel, m.getSender()))
                    .count();
            if (fromSender >= MAIL_SENDER_QUOTA_PER_RECIPIENT) {
                return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                        "MAIL_SENDER_QUOTA_EXCEEDED",
                        "Recipient mailbox already holds the maximum messages from this sender."));
            }
        }
        deliverMail(recipientUuid, senderLabel, subject, body);
        // The replay marker lands in the sender's durable ledger — best-effort
        // after the delivery commit; the in-process replay cache already covers
        // the same-request path, this survives restart/eviction.
        synchronized (senderProgression) {
            try {
                senderProgression.recordAppliedActionRequest(
                        request.requestId(), fingerprint, "true");
                progressionRepository.save(request.playerUuid(), senderProgression);
            } catch (Exception markerFailure) {
                // Delivery already committed — report applied rather than lying
                // about the recipient-visible outcome.
            }
        }
        return AuthorizedActionResult.of(true);
    }

    /**
     * Authorization-checked mail deletion (issue #54 — P1-4 coverage). Adapters that
     * accept untrusted actor input should route through this overload instead of
     * the package-private {@link #deleteMail(UUID, UUID)}, which remains for
     * trusted internal callers.
     */
    public AuthorizedActionResult deleteMail(PlayerProgressionActionRequest request, UUID mailId) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(mailId, "mailId");
        return runProgressionAction(request, "mail.delete", mailId.toString(), null,
                () -> AuthorizedActionResult.of(deleteMail(request.playerUuid(), mailId)));
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
    // 3b. Shared-party team operations (P5-5)
    // ==========================================

    /**
     * Deterministic team id derived from the create request id — a replayed
     * create resolves to the same team instead of forking a second record.
     */
    public static UUID teamIdForRequest(UUID requestId) {
        return UUID.nameUUIDFromBytes(("storynpcs:team/" + requestId).getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Create a team with the subject as owner; no-op when already teamed. */
    public AuthorizedActionResult teamCreate(PlayerProgressionActionRequest request) {
        Objects.requireNonNull(request, "request");
        return runProgressionAction(request, "team.create", "create", null,
                () -> applyTeamCreate(request));
    }

    private AuthorizedActionResult applyTeamCreate(PlayerProgressionActionRequest request) {
        var store = teamProgressionStore;
        if (store == null) {
            return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                    "TEAM_STORE_UNAVAILABLE", "Team progression store is not open"));
        }
        PlayerProgression progression = progressionRepository.getOrCreate(request.playerUuid());
        synchronized (progression) {
            UUID existing = progression.getTeamId();
            if (existing != null) {
                // Heal a dangling ref (a team write that failed mid-op leaves
                // the player's ref pointing at a record that lacks them).
                try {
                    var existingTeam = store.get(existing);
                    if (existingTeam.isPresent() && existingTeam.get().isMember(request.playerUuid())) {
                        return AuthorizedActionResult.of(false); // really already teamed
                    }
                } catch (Exception e) {
                    return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                            "TEAM_STORE_UNAVAILABLE", "Team record could not be read"));
                }
                progression.setTeamId(null);
            }
            UUID teamId = teamIdForRequest(request.requestId());
            try {
                if (store.get(teamId).isPresent()) {
                    return AuthorizedActionResult.of(false); // deterministic id collides
                }
                com.storynpcs.domain.quest.TeamProgression team =
                        new com.storynpcs.domain.quest.TeamProgression(teamId, request.playerUuid());
                // Player ref first: a failure after this leaves a dangling
                // ref that the next op self-heals, never a ghost team.
                progression.setTeamId(teamId);
                saveProgression(request.playerUuid(), progression);
                store.save(team);
                return AuthorizedActionResult.of(true);
            } catch (Exception e) {
                progression.setTeamId(null);
                return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                        "TEAM_STORE_UNAVAILABLE", "Team record could not be persisted"));
            }
        }
    }

    /**
     * Invite a player to the subject's team. The subject must be the team
     * owner (self-service) — invitations are the explicit join gate so no
     * player can party-join uninvited.
     */
    public AuthorizedActionResult teamInvite(PlayerProgressionActionRequest request, UUID inviteeUuid) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(inviteeUuid, "inviteeUuid");
        return runProgressionAction(request, "team.invite", inviteeUuid.toString(), null,
                () -> applyTeamInvite(request, inviteeUuid));
    }

    private AuthorizedActionResult applyTeamInvite(PlayerProgressionActionRequest request, UUID inviteeUuid) {
        var store = teamProgressionStore;
        if (store == null) {
            return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                    "TEAM_STORE_UNAVAILABLE", "Team progression store is not open"));
        }
        PlayerProgression progression = progressionRepository.getOrCreate(request.playerUuid());
        synchronized (progression) {
            UUID teamId = progression.getTeamId();
            if (teamId == null) {
                return AuthorizedActionResult.of(false); // not in a team
            }
            try {
                var teamOpt = store.get(teamId);
                if (teamOpt.isEmpty()) {
                    return AuthorizedActionResult.of(false);
                }
                var team = teamOpt.get();
                if (!request.playerUuid().equals(team.getOwnerUuid())) {
                    return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                            "NOT_OWNER", "Only the team owner can invite members"));
                }
                if (team.isMember(inviteeUuid)) {
                    return AuthorizedActionResult.of(false); // already a member
                }
                if (!team.invite(inviteeUuid)) {
                    return AuthorizedActionResult.of(false); // already invited
                }
                store.save(team);
                return AuthorizedActionResult.of(true);
            } catch (Exception e) {
                return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                        "TEAM_STORE_UNAVAILABLE", "Team record could not be persisted"));
            }
        }
    }

    /**
     * Join the team owned by {@code ownerUuid}. The joiner must hold an
     * invitation (consumed on success); an admin-scope command actor bypasses
     * the invite gate as the documented operator override.
     */
    public AuthorizedActionResult teamJoin(PlayerProgressionActionRequest request, UUID ownerUuid) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        return runProgressionAction(request, "team.join", ownerUuid.toString(), null,
                () -> applyTeamJoin(request, ownerUuid));
    }

    private AuthorizedActionResult applyTeamJoin(PlayerProgressionActionRequest request, UUID ownerUuid) {
        var store = teamProgressionStore;
        if (store == null) {
            return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                    "TEAM_STORE_UNAVAILABLE", "Team progression store is not open"));
        }
        // Read the owner's ref WITHOUT holding the subject lock — nesting
        // player locks in inconsistent order could deadlock two players
        // joining each other's teams concurrently.
        PlayerProgression ownerProgression = progressionRepository.getOrCreate(ownerUuid);
        UUID teamId;
        synchronized (ownerProgression) {
            teamId = ownerProgression.getTeamId();
        }
        if (teamId == null) {
            return AuthorizedActionResult.of(false); // owner has no team
        }
        PlayerProgression progression = progressionRepository.getOrCreate(request.playerUuid());
        synchronized (progression) {
            UUID existing = progression.getTeamId();
            if (existing != null) {
                try {
                    var existingTeam = store.get(existing);
                    if (existingTeam.isPresent() && existingTeam.get().isMember(request.playerUuid())) {
                        return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                                "ALREADY_IN_TEAM", "Leave the current team before joining another"));
                    }
                } catch (Exception e) {
                    return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                            "TEAM_STORE_UNAVAILABLE", "Team record could not be read"));
                }
                progression.setTeamId(null); // dangling ref — self-heal and continue
            }
            try {
                var teamOpt = store.get(teamId);
                if (teamOpt.isEmpty()) {
                    return AuthorizedActionResult.of(false);
                }
                var team = teamOpt.get();
                boolean adminOverride = "command".equals(request.actorType())
                        && request.permissionLevel() >= 2;
                if (!adminOverride && !team.isInvited(request.playerUuid())) {
                    return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                            "NOT_INVITED", "Joining a team requires an invitation from its owner"));
                }
                // Player ref first: a failure after this leaves a dangling
                // ref that the next op self-heals — never a ghost member.
                progression.setTeamId(teamId);
                saveProgression(request.playerUuid(), progression);
                team.addMember(request.playerUuid());
                team.consumeInvite(request.playerUuid());
                store.save(team);
                return AuthorizedActionResult.of(true);
            } catch (Exception e) {
                progression.setTeamId(null);
                return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                        "TEAM_STORE_UNAVAILABLE", "Team record could not be persisted"));
            }
        }
    }

    /**
     * Leave the subject's team. The last member disbands the record; an owner
     * leaving with survivors transfers ownership to a deterministic successor
     * (lowest member UUID — stable across durable rewrites).
     */
    public AuthorizedActionResult teamLeave(PlayerProgressionActionRequest request) {
        Objects.requireNonNull(request, "request");
        return runProgressionAction(request, "team.leave", "leave", null,
                () -> applyTeamLeave(request));
    }

    private AuthorizedActionResult applyTeamLeave(PlayerProgressionActionRequest request) {
        var store = teamProgressionStore;
        if (store == null) {
            return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                    "TEAM_STORE_UNAVAILABLE", "Team progression store is not open"));
        }
        PlayerProgression progression = progressionRepository.getOrCreate(request.playerUuid());
        synchronized (progression) {
            UUID teamId = progression.getTeamId();
            if (teamId == null) {
                return AuthorizedActionResult.of(false); // not in a team
            }
            try {
                // Team record first: a failure after it leaves a dangling
                // player ref that a retried leave self-heals — never a ghost
                // member who can never be removed from the roster.
                var teamOpt = store.get(teamId);
                if (teamOpt.isPresent()) {
                    var team = teamOpt.get();
                    team.removeMember(request.playerUuid());
                    team.revokeInvite(request.playerUuid());
                    if (team.getMemberUuids().isEmpty()) {
                        store.delete(teamId);
                    } else {
                        if (request.playerUuid().equals(team.getOwnerUuid())) {
                            UUID successor = team.getMemberUuids().stream()
                                    .sorted().findFirst().orElse(null);
                            team.setOwnerUuid(successor);
                        }
                        store.save(team);
                    }
                }
                progression.setTeamId(null);
                saveProgression(request.playerUuid(), progression);
                return AuthorizedActionResult.of(true);
            } catch (Exception e) {
                return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                        "TEAM_STORE_UNAVAILABLE", "Team record could not be persisted"));
            }
        }
    }

    /**
     * Transfer ownership to another member. The subject is the CURRENT owner
     * (self-service transfer) or any member under an admin-scope command;
     * {@code newOwnerUuid} must already be a team member — ownership never
     * leaves the membership set.
     */
    public AuthorizedActionResult teamTransferOwner(PlayerProgressionActionRequest request,
                                                    UUID newOwnerUuid) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(newOwnerUuid, "newOwnerUuid");
        return runProgressionAction(request, "team.owner", newOwnerUuid.toString(), null,
                () -> applyTeamTransferOwner(request, newOwnerUuid));
    }

    private AuthorizedActionResult applyTeamTransferOwner(PlayerProgressionActionRequest request,
                                                          UUID newOwnerUuid) {
        var store = teamProgressionStore;
        if (store == null) {
            return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                    "TEAM_STORE_UNAVAILABLE", "Team progression store is not open"));
        }
        PlayerProgression progression = progressionRepository.getOrCreate(request.playerUuid());
        synchronized (progression) {
            UUID teamId = progression.getTeamId();
            if (teamId == null) {
                return AuthorizedActionResult.of(false);
            }
            try {
                var teamOpt = store.get(teamId);
                if (teamOpt.isEmpty()) {
                    return AuthorizedActionResult.of(false);
                }
                var team = teamOpt.get();
                if (!team.isMember(newOwnerUuid)) {
                    return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                            "NOT_A_MEMBER", "Ownership can only transfer to a team member"));
                }
                boolean selfService = request.playerUuid().equals(request.actorId());
                if (selfService && !request.playerUuid().equals(team.getOwnerUuid())) {
                    return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                            "NOT_OWNER", "Only the team owner can transfer ownership"));
                }
                if (newOwnerUuid.equals(team.getOwnerUuid())) {
                    return AuthorizedActionResult.of(false);
                }
                team.setOwnerUuid(newOwnerUuid);
                store.save(team);
                return AuthorizedActionResult.of(true);
            } catch (Exception e) {
                return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                        "TEAM_STORE_UNAVAILABLE", "Team record could not be persisted"));
            }
        }
    }

    /**
     * Toggle optional faction sharing (P6-1): the subject must own the team.
     * When enabled, an ADJUST faction change on any member propagates the
     * applied delta to teammates — explicit opt-in, never implicit.
     */
    public AuthorizedActionResult teamSetSharing(PlayerProgressionActionRequest request,
                                                 boolean shareFactionPoints) {
        Objects.requireNonNull(request, "request");
        return runProgressionAction(request, "team.share", "share=" + shareFactionPoints, null,
                () -> applyTeamSetSharing(request, shareFactionPoints));
    }

    private AuthorizedActionResult applyTeamSetSharing(PlayerProgressionActionRequest request,
                                                       boolean shareFactionPoints) {
        var store = teamProgressionStore;
        if (store == null) {
            return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                    "TEAM_STORE_UNAVAILABLE", "Team progression store is not open"));
        }
        PlayerProgression progression = progressionRepository.getOrCreate(request.playerUuid());
        synchronized (progression) {
            UUID teamId = progression.getTeamId();
            if (teamId == null) {
                return AuthorizedActionResult.of(false);
            }
            try {
                var teamOpt = store.get(teamId);
                if (teamOpt.isEmpty() || !teamOpt.get().isMember(request.playerUuid())) {
                    return AuthorizedActionResult.of(false);
                }
                var team = teamOpt.get();
                if (!request.playerUuid().equals(team.getOwnerUuid())) {
                    return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                            "NOT_OWNER", "Only the team owner can change sharing"));
                }
                team.setShareFactionPoints(shareFactionPoints);
                store.save(team);
                return AuthorizedActionResult.of(true);
            } catch (Exception e) {
                return AuthorizedActionResult.denied(AuthorizationDecision.deny(
                        "TEAM_STORE_UNAVAILABLE", "Team record could not be persisted"));
            }
        }
    }

    /** Bound on teammates receiving a shared faction delta — teams stay small; the cap is defensive. */
    private static final int MAX_TEAM_FACTION_SHARE = 64;

    /**
     * Faction sharing (P6-1): when the subject's team opted in, the *applied*
     * delta (post-clamp) is applied to each teammate's own progression — own
     * revision bump, own save, own {@code FactionReputationChangeEvent} with
     * source {@code "team"}. Runs after the subject commit; per-member write
     * failures are logged, never rolled back (same best-effort policy as the
     * quest mirror). Only ADJUST deltas propagate — an absolute SET is an
     * administrative action with no shareable relative meaning.
     */
    private void propagateTeamFactionDelta(UUID playerUuid, NamespacedId factionId, int delta,
                                           Faction faction, List<StoryNpcsEvent> eventsOut) {
        var store = teamProgressionStore;
        if (store == null || delta == 0) {
            return;
        }
        try {
            PlayerProgression subject = progressionRepository.getOrCreate(playerUuid);
            UUID teamId;
            synchronized (subject) {
                teamId = subject.getTeamId();
            }
            if (teamId == null) {
                return;
            }
            var teamOpt = store.get(teamId);
            if (teamOpt.isEmpty() || !teamOpt.get().isShareFactionPoints()
                    || !teamOpt.get().isMember(playerUuid)) {
                return;
            }
            int applied = 0;
            for (UUID memberUuid : teamOpt.get().getMemberUuids()) {
                if (memberUuid.equals(playerUuid)) {
                    continue;
                }
                if (++applied > MAX_TEAM_FACTION_SHARE) {
                    break;
                }
                try {
                    PlayerProgression member = progressionRepository.getOrCreate(memberUuid);
                    synchronized (member) {
                        int oldP = member.getFactionScore(factionId, faction.getDefaultPoints());
                        member.adjustFactionScore(factionId, delta, faction.getDefaultPoints());
                        int newP = member.getFactionScore(factionId, faction.getDefaultPoints());
                        member.setFactionRevision(Math.addExact(member.getFactionRevision(), 1L));
                        progressionRepository.save(memberUuid, member);
                        if (eventsOut != null && oldP != newP) {
                            eventsOut.add(new FactionReputationChangeEvent(
                                    memberUuid, factionId, oldP, newP, "team"));
                        }
                    }
                } catch (Exception memberFailure) {
                    System.err.println("[StoryNPCs] team faction share to " + memberUuid
                            + " failed: " + memberFailure.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[StoryNPCs] team faction share for " + playerUuid
                    + " failed: " + e.getMessage());
        }
    }

    /**
     * Post-commit mirror (P5-5): when a team member's quest state changes,
     * the shared team record picks it up so "team progress and ownership
     * survive reconnect". Completion also records the explicit claim — the
     * first committing member owns the shared slot, durably. Best-effort:
     * the player-scoped commit already landed, so a store failure is logged
     * rather than rolling a completed quest back.
     */
    private void propagateTeamQuestState(UUID playerUuid, NamespacedId questId,
                                         PlayerProgression progression) {
        var store = teamProgressionStore;
        UUID teamId = progression.getTeamId();
        if (store == null || teamId == null) {
            return;
        }
        try {
            var teamOpt = store.get(teamId);
            if (teamOpt.isEmpty()) {
                return;
            }
            var team = teamOpt.get();
            if (!team.isMember(playerUuid)) {
                return; // dangling ref — never write into a record lacking the member
            }
            var state = progression.peekQuestState(questId);
            if (state == null) {
                return;
            }
            team.putQuestState(questId, state.copy());
            if (state.getStatus() == com.storynpcs.domain.progression.QuestProgressState.Status.COMPLETED) {
                team.claim(questId, playerUuid);
            }
            store.save(team);
        } catch (Exception e) {
            System.err.println("[StoryNPCs] team progression mirror failed for " + questId
                    + " (player " + playerUuid + "): " + e.getMessage());
        }
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
                        } else if (request.action() == QuestProgressionMutationRequest.Action.RESET) {
                            notifications.add(new com.storynpcs.api.event.QuestResetEvent(
                                    request.playerUuid(), request.questId()));
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
                                    request.playerUuid(), quest, progression, factionEvents,
                                    request.requestId(), fingerprint);
                            if (completion.outcome() == QuestCompletionResult.Outcome.COMPLETED) {
                                notifications.addAll(factionEvents);
                                // P6-1: share the just-applied faction reward
                                // deltas with an opted-in team.
                                for (var fe : factionEvents) {
                                    registry.getFaction(fe.factionId()).ifPresent(faction ->
                                            propagateTeamFactionDelta(request.playerUuid(),
                                                    fe.factionId(), fe.newPoints() - fe.oldPoints(),
                                                    faction, notifications));
                                }
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
                    if (result.newlyApplied()) {
                        // P5-5: mirror the member's committed state into the
                        // shared team record (progress + completion claim).
                        propagateTeamQuestState(request.playerUuid(), request.questId(), progression);
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
        if (request.action() == QuestProgressionMutationRequest.Action.RESET) {
            // Explicit reset is reachable only for RESET-type quests with a
            // completed progression state — clears state so the quest reopens.
            if (quest.getRepeatType() != Quest.RepeatType.RESET) {
                ValidationResult diagnostics = ValidationResult.valid();
                diagnostics.addWarning("QUEST_NOT_RESETTABLE",
                        "Quest " + request.questId() + " does not support explicit resets");
                CanonicalMutationResult rejected = new CanonicalMutationResult(false, false, currentRevision,
                        diagnostics, List.of(), "NO_CHANGE");
                rememberQuestMutation(request, fingerprint, rejected);
                return QuestMutationExecution.resultOnly(rejected);
            }
            if (current == null || current.getStatus() != QuestProgressState.Status.COMPLETED) {
                ValidationResult diagnostics = ValidationResult.valid();
                diagnostics.addWarning("QUEST_NOT_COMPLETED",
                        "Quest " + request.questId() + " has no completed progression to reset");
                CanonicalMutationResult unchanged = new CanonicalMutationResult(false, false, currentRevision,
                        diagnostics, List.of(), "NO_CHANGE");
                rememberQuestMutation(request, fingerprint, unchanged);
                return QuestMutationExecution.resultOnly(unchanged);
            }
        } else if (request.action() == QuestProgressionMutationRequest.Action.START) {
            if (current != null && current.getStatus() == QuestProgressState.Status.COMPLETED) {
                // Repeat-boundary enforcement: NORMAL/RESET stay closed until an
                // explicit reset clears the state, DAILY/WEEKLY reopen on the
                // authored boundary, REPEATABLE/INSTANT reopen freely.
                Instant lastCompleted = Instant.ofEpochMilli(current.getLastCompletedAtEpochMillis());
                if (!QUEST_REPEAT_SCHEDULE.canRepeat(quest.getRepeatType(), lastCompleted, Instant.now())) {
                    ValidationResult diagnostics = ValidationResult.valid();
                    diagnostics.addWarning("QUEST_ALREADY_COMPLETED", switch (quest.getRepeatType()) {
                        case DAILY -> "This daily quest is already complete — it reopens at the next day boundary";
                        case WEEKLY -> "This weekly quest is already complete — it reopens at the next week boundary";
                        case RESET -> "This quest is already complete — it requires an explicit reset to reopen";
                        default -> "This quest is non-repeatable and is already complete";
                    });
                    CanonicalMutationResult unchanged = new CanonicalMutationResult(false, false, currentRevision,
                            diagnostics, List.of(), "NO_CHANGE");
                    rememberQuestMutation(request, fingerprint, unchanged);
                    return QuestMutationExecution.resultOnly(unchanged);
                }
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
        long completionStateRevision = 0L;
        boolean shouldComplete = false;
        try {
            if (request.action() == QuestProgressionMutationRequest.Action.RESET) {
                // Clear the completed progression state entirely — the quest
                // reverts to unstarted and can be taken again.
                progression.getQuests().remove(request.questId());
            } else {
                QuestProgressState state = progression.getQuestState(request.questId());
                if (request.action() == QuestProgressionMutationRequest.Action.START) {
                    if (state.getStatus() == QuestProgressState.Status.COMPLETED) {
                        // A re-run starts clean: stale objective counts would
                        // otherwise satisfy every objective on the first progress tick.
                        state.getObjectiveCounts().clear();
                    }
                    state.setStatus(QuestProgressState.Status.IN_PROGRESS);
                    // INSTANT quests complete without a turn-in: satisfaction is
                    // evaluated immediately — trivially true for objective-free quests.
                    shouldComplete = quest.getRepeatType().autoCompletes()
                            && quest.getObjectives() != null
                            && quest.getObjectives().stream().allMatch(objective -> objective != null
                                    && state.getCount(objective.getId()) >= objective.getRequiredCount());
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
                completionStateRevision = state.getStateRevision();
            }
            progression.setQuestRevision(Math.addExact(currentRevision, 1L));
            progression.getPendingQuestCompletions().remove(request.questId());
            if (shouldComplete) {
                progression.getPendingQuestCompletions().put(request.questId(), new PendingQuestCompletion(
                        request.requestId(), request.questId(), fingerprint, completionStateRevision));
            }
            progressionRepository.save(request.playerUuid(), progression);
        } catch (Exception failure) {
            progression.restoreFrom(snapshot);
            CanonicalMutationResult rejected = questMutationFailure(currentRevision, "PROGRESSION_COMMIT_FAILED",
                    "Could not durably save quest progression: " + failure.getMessage());
            return QuestMutationExecution.resultOnly(rejected);
        }

        String event = switch (request.action()) {
            case START -> "QuestStartEvent";
            case RESET -> "QuestResetEvent";
            case PROGRESS -> "QuestObjectiveProgressEvent";
        };
        CanonicalMutationResult applied = new CanonicalMutationResult(true, false, progression.getQuestRevision(),
                ValidationResult.valid(), List.of(event), "COMMITTED");
        rememberQuestMutation(request, fingerprint, applied, shouldComplete);
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
                || state.getStateRevision() != pending.questStateRevision()) {
            return false;
        }
        if (quest.getObjectives() == null || quest.getObjectives().isEmpty()) {
            // Objective-free INSTANT quests are satisfied vacuously — their
            // pending completion (recorded at START) must remain recoverable.
            return quest.getRepeatType() != null && quest.getRepeatType().autoCompletes();
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
        long expectedRevision;
        try {
            expectedRevision = currentQuestProgressionRevision(playerUuid);
        } catch (RuntimeException unavailable) {
            return QuestCompletionResult.failed("PROGRESSION_UNAVAILABLE", 0);
        }
        QuestCompletionResult result = completeQuest(new QuestCompletionMutationRequest(
                "system", null, playerUuid, questId,
                expectedRevision, UUID.randomUUID(), -1));
        if (result.outcome() == QuestCompletionResult.Outcome.REJECTED
                && "QUEST_NOT_FOUND".equals(result.code())) {
            throw new NoSuchElementException("Quest not found: " + questId);
        }
        return result;
    }

    /** Applies one typed, player-scoped quest completion through the canonical boundary. */
    public QuestCompletionResult completeQuest(QuestCompletionMutationRequest request) {
        Objects.requireNonNull(request, "request");
        AuthorizationDecision authorization = AuthorizationPolicy.evaluate(request);
        Object playerLock = progressionMutationLocks[request.playerUuid().hashCode()
                & (progressionMutationLocks.length - 1)];
        if (!authorization.allowed()) {
            QuestCompletionResult denied = QuestCompletionResult.rejected(authorization.code());
            QuestEventQueue dispatchQueue;
            synchronized (playerLock) {
                dispatchQueue = enqueueQuestEvents(request.playerUuid(),
                        List.of(questCompletionEvent(request, denied, 0L)));
            }
            if (dispatchQueue != null) drainQuestEvents(dispatchQueue);
            return denied;
        }

        String fingerprint = questCompletionFingerprint(request);
        Object requestLock = questMutationLocks[request.requestId().hashCode() & (questMutationLocks.length - 1)];
        List<FactionReputationChangeEvent> factionEvents = new ArrayList<>();
        List<StoryNpcsEvent> notifications = new ArrayList<>();
        QuestCompletionResult result;
        boolean replayed = false;
        long[] committedRevision = {0L};
        QuestEventQueue dispatchQueue;
        synchronized (requestLock) {
            synchronized (playerLock) {
                PlayerProgression progression;
                try {
                    progression = progressionRepository.getOrCreate(request.playerUuid());
                } catch (RuntimeException unavailable) {
                    System.err.println("[StoryNPCs] quest completion blocked for " + request.playerUuid()
                            + ": " + unavailable.getMessage());
                    progression = null;
                }
                if (progression == null) {
                    result = QuestCompletionResult.failed("PROGRESSION_UNAVAILABLE", 0);
                } else {
                    synchronized (progression) {
                        CompletedQuestCompletion completed = completedQuestCompletions.get(request.requestId());
                        if (completed != null) {
                            if (!completed.payloadFingerprint().equals(fingerprint)) {
                                result = QuestCompletionResult.rejected("REQUEST_PAYLOAD_MISMATCH");
                            } else {
                                result = completed.result();
                                replayed = true;
                            }
                        } else {
                            QuestProgressState existing = progression.getQuests().get(request.questId());
                            if (existing != null
                                    && existing.getStatus() == QuestProgressState.Status.COMPLETED) {
                                // Completion is idempotent: a terminal state is a no-op
                                // regardless of how stale the caller's expected revision is.
                                result = QuestCompletionResult.alreadyCompleted();
                            } else {
                                long currentRevision = progression.getQuestRevision();
                                if (currentRevision != request.expectedRevision()) {
                                    result = QuestCompletionResult.rejected("STALE_REVISION");
                                } else {
                                    Quest quest = registry.getQuest(request.questId()).orElse(null);
                                    if (quest == null) {
                                        result = QuestCompletionResult.rejected("QUEST_NOT_FOUND");
                                    } else {
                                        result = completeQuestUnderLock(request.playerUuid(), quest, progression,
                                                factionEvents, request.requestId(), fingerprint);
                                    }
                                }
                            }
                            // FAILED outcomes are transient: a same-request retry must
                            // re-attempt, recovering through the durable pending record.
                            if (result.outcome() != QuestCompletionResult.Outcome.FAILED) {
                                rememberQuestCompletion(request, fingerprint, result);
                            }
                        }
                        committedRevision[0] = progression.getQuestRevision();
                    }
                    if (!replayed && result.outcome() == QuestCompletionResult.Outcome.COMPLETED) {
                        // P5-5: mirror the completion into the shared team
                        // record and record the explicit claim.
                        propagateTeamQuestState(request.playerUuid(), request.questId(), progression);
                        notifications.addAll(factionEvents);
                        // P6-1: share applied faction reward deltas with an
                        // opted-in team (teammates get the delta, each under
                        // their own revision and durable save).
                        for (var fe : factionEvents) {
                            registry.getFaction(fe.factionId()).ifPresent(faction ->
                                    propagateTeamFactionDelta(request.playerUuid(),
                                            fe.factionId(), fe.newPoints() - fe.oldPoints(),
                                            faction, notifications));
                        }
                        notifications.add(new QuestCompleteEvent(request.playerUuid(), request.questId()));
                    }
                }
                if (!replayed) notifications.add(0, questCompletionEvent(request, result, committedRevision[0]));
                // Enqueue under the player lock; listeners are dispatched after releasing it.
                dispatchQueue = enqueueQuestEvents(request.playerUuid(), notifications);
            }
        }
        if (dispatchQueue != null) drainQuestEvents(dispatchQueue);
        return result;
    }

    private void rememberQuestCompletion(QuestCompletionMutationRequest request, String fingerprint,
                                         QuestCompletionResult result) {
        completedQuestCompletions.put(request.requestId(), new CompletedQuestCompletion(fingerprint, result));
    }

    private static String questCompletionFingerprint(QuestCompletionMutationRequest request) {
        String payload = String.join("\u0000", request.actorType(),
                request.actorId() == null ? "" : request.actorId().toString(),
                request.playerUuid().toString(), request.questId().toString(),
                Long.toString(request.expectedRevision()));
        return MutationPayloadFingerprint.of(request.operation(), payload);
    }

    private CanonicalMutationEvent questCompletionEvent(
            QuestCompletionMutationRequest request, QuestCompletionResult result, long revision) {
        String outcome = switch (result.outcome()) {
            case COMPLETED -> "COMMITTED";
            case ALREADY_COMPLETED -> "COMPLETION_ALREADY_COMMITTED";
            case REJECTED -> "REJECTED_NO_SIDE_EFFECTS";
            case FAILED -> "REWARD_EXECUTION_FAILED";
        };
        return new CanonicalMutationEvent(request.operation(), request.actorType(), request.questId(),
                request.requestId(), result.outcome() == QuestCompletionResult.Outcome.COMPLETED, revision, outcome,
                request.actorId(), request.playerUuid());
    }

    private QuestCompletionResult completeQuestUnderLock(
            UUID playerUuid, Quest quest, PlayerProgression progression,
            List<FactionReputationChangeEvent> factionEvents,
            UUID requestId, String requestFingerprint) {
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
                    requestId, questId, requestFingerprint,
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
                        case EXPERIENCE, ITEM, COMMAND -> {
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
                            // lose this reward but can never grant it twice. For COMMAND
                            // rewards the mark additionally means "attempted" — a failed
                            // command is reported and never re-executed (non-atomic policy).
                            deliverQuestReward(playerUuid, questId, reward);
                            rewardsApplied++;
                        }
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
            boolean factionStandingChanged = false;
            if (quest.getRewards() != null) {
                for (QuestReward reward : quest.getRewards()) {
                    if (reward.getType() != QuestReward.Type.FACTION_POINTS) continue;
                    NamespacedId factionId = NamespacedId.of(reward.getTarget());
                    Faction faction = registry.getFaction(factionId).orElseThrow();
                    int oldPoints = progression.getFactionScore(factionId, faction.getDefaultPoints());
                    progression.adjustFactionScore(factionId, reward.getAmount(), faction.getDefaultPoints());
                    int newPoints = progression.getFactionScore(factionId, faction.getDefaultPoints());
                    factionEvents.add(new FactionReputationChangeEvent(
                            playerUuid, factionId, oldPoints, newPoints, "quest"));
                    rewardsApplied++;
                    factionStandingChanged = true;
                }
            }
            // Keep the typed faction-mutation revision consistent with this internal,
            // already-canonical (locked, revisioned quest-completion) reward commit so a
            // client's cached faction revision never silently goes stale after rewards land.
            if (factionStandingChanged) {
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
    private void deliverQuestReward(UUID playerUuid, NamespacedId questId, QuestReward reward) {
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
            case COMMAND -> {
                // Non-atomic reward: runs once under the durable delivered
                // mark; %player% is sanitized exactly like the dialogue
                // EXECUTE_COMMAND path so a crafted player name cannot inject
                // extra command syntax.
                String cmd = reward.getTarget().trim();
                String safeName = player.getScoreboardName().replaceAll("[^a-zA-Z0-9_]", "");
                if (safeName.length() < 2 || safeName.length() > 16) safeName = "unknown";
                cmd = cmd.replace("%player%", safeName);
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                try {
                    minecraftServer.getCommands().performPrefixedCommand(
                            minecraftServer.createCommandSourceStack()
                                    .withSuppressedOutput().withMaximumPermission(4), cmd);
                } catch (Exception commandFailure) {
                    System.err.println("[StoryNPCs] non-atomic quest command reward failed for "
                            + questId + " (policy: marked attempted, not retried): "
                            + commandFailure.getMessage());
                    throw commandFailure;
                }
            }
            case ITEM -> {
                var itemRl = net.minecraft.resources.ResourceLocation.tryParse(reward.getTarget().trim());
                var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(itemRl).orElseThrow();
                net.minecraft.world.item.ItemStack stack =
                        new net.minecraft.world.item.ItemStack(item, Math.max(1, reward.getAmount()));
                if (player.getInventory().add(stack)) {
                    return;
                }
                // Inventory.add leaves the uninserted remainder in `stack` when it
                // returns false — only that remainder may be mailed. Mailing the
                // authored amount would re-grant whatever was already placed.
                int remainder = stack.getCount();
                if (remainder <= 0) {
                    return;
                }
                // RewardOverflowPolicy.MAIL is the default: a full inventory must
                // never silently drop a reward. The mail is persisted before we
                // return so the durable reward mark + mail are both recoverable.
                if (questMailStore == null) {
                    // FAIL policy fallback — the completion leg stays recoverable
                    // via the durable delivered-reward mark rather than dropping.
                    throw new IllegalStateException(
                            "quest reward overflow and no mail store is available");
                }
                try {
                    enqueueItemRemainderMail(playerUuid, questId,
                            remainderMailItems(NamespacedId.of(itemRl.toString()), remainder, ""),
                            minecraftServer.overworld().getGameTime());
                } catch (IOException mailFailure) {
                    throw new IllegalStateException(
                            "quest reward overflow mail could not be persisted", mailFailure);
                }
            }
            default -> throw new IllegalStateException("unsupported reward side effect: " + reward.getType());
        }
    }

    /**
     * Postman delivery: claims up to {@code maxDeliveries} pending quest mails
     * for the player and grants their item/experience payloads exactly once.
     * Each mail is durably claimed BEFORE its payload is granted so a payload
     * can never be granted twice concurrently, then durably marked delivered
     * AFTER the grant so a crash inside the window leaves a claimed-but-
     * undelivered record that login recovery replays — at-least-once, never
     * silently lost. Any portion the inventory refuses is re-queued as a new
     * mail (falling back to a world drop if the mail store fails), so a full
     * inventory cannot silently destroy it either.
     *
     * @return a summary for the interacting player; empty when nothing pending.
     */
    public java.util.Optional<MailDeliverySummary> deliverQuestMail(UUID playerUuid, int maxDeliveries) {
        var mailStore = questMailStore;
        if (mailStore == null || minecraftServer == null) return java.util.Optional.empty();
        var player = minecraftServer.getPlayerList().getPlayer(playerUuid);
        if (player == null) return java.util.Optional.empty();
        int bound = Math.max(1, Math.min(maxDeliveries,
                com.storynpcs.domain.role.social.PostmanRole.MAX_MAILBOX_CAPACITY));
        try {
            var pending = mailStore.pendingFor(playerUuid);
            if (pending.isEmpty()) return java.util.Optional.empty();
            long tick = minecraftServer.overworld().getGameTime();
            int delivered = 0;
            int experience = 0;
            var itemSummary = new java.util.ArrayList<String>();
            for (var mail : pending) {
                if (delivered >= bound) break;
                var claimed = mailStore.claim(mail.mailId(), tick).orElse(null);
                if (claimed == null) continue; // raced by another delivery — first wins
                delivered++;
                deliverMailPayload(player, claimed, tick, itemSummary);
                experience += claimed.experience();
                try {
                    mailStore.confirmDelivery(claimed.mailId(), tick);
                } catch (IOException confirmFailure) {
                    // The record stays claimed-but-undelivered — login recovery
                    // replays it, degrading to at-least-once instead of loss.
                    com.storynpcs.StoryNpcs.LOGGER.warn(
                            "Quest mail {} delivery confirmation failed; login recovery will replay it",
                            claimed.mailId(), confirmFailure);
                }
            }
            if (delivered == 0) return java.util.Optional.empty();
            return java.util.Optional.of(new MailDeliverySummary(delivered, experience, List.copyOf(itemSummary)));
        } catch (IOException e) {
            throw new IllegalStateException("quest mail delivery failed", e);
        }
    }

    /**
     * Grants one claimed mail's payload: items into the inventory (any refused,
     * unresolvable, or ungranted remainder is re-queued as a fresh mail, or
     * dropped at the player's feet if the store itself fails), then the mail's
     * experience leg.
     *
     * <p>An item grant that throws mid-loop must not abort this mail — the
     * caller would leave it claimed-undelivered and login recovery would replay
     * the WHOLE payload, re-granting items that already landed. Instead the
     * defensible remainder plus every untouched item is returned to durable
     * mail, the XP leg still runs, and the source mail confirms normally:
     * at-least-once is preserved while whole-mail replay duplication is
     * narrowed to a genuine crash window.
     */
    private void deliverMailPayload(net.minecraft.server.level.ServerPlayer player,
                                    com.storynpcs.domain.quest.QuestMail mail,
                                    long tick, java.util.List<String> itemSummary) {
        var remainderItems = new ArrayList<com.storynpcs.domain.npc.NpcItemStack>();
        var items = mail.items();
        for (int i = 0; i < items.size(); i++) {
            var mailItem = items.get(i);
            var rl = net.minecraft.resources.ResourceLocation.tryParse(mailItem.itemId().toString());
            var item = rl != null
                    ? net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(rl).orElse(null)
                    : null;
            if (item == null) {
                // An unresolvable item id (e.g. a removed content pack) must not
                // be silently voided — retain it in durable mail so it can be
                // resolved again later rather than confirmed-and-lost.
                remainderItems.addAll(remainderMailItems(
                        mailItem.itemId(), mailItem.count(), mailItem.components()));
                itemSummary.add(mailItem.itemId() + " x" + mailItem.count()
                        + " (unknown item — returned to mail)");
                continue;
            }
            var stack = new net.minecraft.world.item.ItemStack(item, mailItem.count());
            boolean placed;
            try {
                placed = player.getInventory().add(stack);
            } catch (RuntimeException grantFailure) {
                // The inventory may have partially absorbed `stack` before
                // throwing; its post-mutation count is the defensible
                // un-granted remainder. Re-queue it plus every untouched item
                // instead of aborting — aborting would leave the source mail
                // claimed-undelivered and recovery would re-grant the whole
                // payload including items already delivered.
                remainderItems.addAll(remainderMailItems(
                        mailItem.itemId(), Math.max(0, stack.getCount()), mailItem.components()));
                for (int j = i + 1; j < items.size(); j++) {
                    var pendingItem = items.get(j);
                    remainderItems.addAll(remainderMailItems(
                            pendingItem.itemId(), pendingItem.count(), pendingItem.components()));
                }
                itemSummary.add("(item grant interrupted — ungranted items returned to mail)");
                com.storynpcs.StoryNpcs.LOGGER.warn(
                        "Quest mail {} item grant failed mid-delivery; ungranted items were "
                                + "re-queued instead of replaying the whole mail",
                        mail.mailId(), grantFailure);
                break;
            }
            if (placed) {
                itemSummary.add(mailItem.itemId() + " x" + mailItem.count());
                continue;
            }
            // Inventory.add leaves the uninserted remainder in `stack`. The
            // mail is already durably claimed, so only the remainder is
            // re-queued as new mail — never re-send the placed portion,
            // never silently destroy it.
            int remainder = stack.getCount();
            remainderItems.addAll(
                    remainderMailItems(mailItem.itemId(), remainder, mailItem.components()));
            itemSummary.add(mailItem.itemId() + " x" + (mailItem.count() - remainder)
                    + " (remainder " + remainder + " returned to mail)");
        }
        if (!remainderItems.isEmpty()) {
            try {
                enqueueItemRemainderMail(player.getUUID(), mail.questId(), remainderItems, tick);
            } catch (IOException requeueFailure) {
                // The source mail is already claimed — if the remainder
                // cannot be re-queued durably, drop it at the player's
                // feet so nothing is destroyed silently.
                for (var remainderItem : remainderItems) {
                    var remainderRl = net.minecraft.resources.ResourceLocation
                            .tryParse(remainderItem.itemId().toString());
                    var remainderMcItem = remainderRl != null
                            ? net.minecraft.core.registries.BuiltInRegistries.ITEM
                                    .getOptional(remainderRl).orElse(null)
                            : null;
                    if (remainderMcItem != null) {
                        player.drop(new net.minecraft.world.item.ItemStack(
                                remainderMcItem, remainderItem.count()), false);
                    }
                }
                itemSummary.add("(mail overflow could not be persisted — remainder dropped at your feet)");
            }
        }
        if (mail.experience() > 0) {
            player.giveExperiencePoints(mail.experience());
        }
    }

    /**
     * Login recovery for the claim→deliver crash window (P2-2/P5-5): a mail
     * durably claimed but never confirmed delivered lost its payload leg to a
     * crash. Replays the payload for that player's stranded mail and confirms
     * delivery — matching the bank/trade journal-replay contract.
     *
     * @return number of stranded mails recovered (failures stay undelivered
     *         and are retried on the next login).
     */
    public int recoverQuestMailDeliveries(UUID playerUuid) {
        var mailStore = questMailStore;
        if (mailStore == null || minecraftServer == null || playerUuid == null) {
            return 0;
        }
        var player = minecraftServer.getPlayerList().getPlayer(playerUuid);
        if (player == null) {
            return 0;
        }
        final java.util.List<com.storynpcs.domain.quest.QuestMail> stranded;
        try {
            stranded = mailStore.claimedUndeliveredFor(playerUuid);
        } catch (IOException readFailure) {
            com.storynpcs.StoryNpcs.LOGGER.error(
                    "Quest mail recovery scan failed for {}", playerUuid, readFailure);
            return 0;
        }
        if (stranded.isEmpty()) {
            return 0;
        }
        long tick = minecraftServer.overworld().getGameTime();
        int recovered = 0;
        for (var mail : stranded) {
            try {
                deliverMailPayload(player, mail, tick, new ArrayList<>());
                mailStore.confirmDelivery(mail.mailId(), tick);
                recovered++;
            } catch (Exception failure) {
                com.storynpcs.StoryNpcs.LOGGER.warn(
                        "Quest mail {} recovery failed; will retry on next login",
                        mail.mailId(), failure);
            }
        }
        return recovered;
    }

    /**
     * Builds the mail payload for an undelivered item remainder, chunked at
     * {@link com.storynpcs.domain.npc.NpcItemStack#MAX_COUNT} so an arbitrarily
     * large remainder is preserved rather than silently truncated.
     */
    static List<com.storynpcs.domain.npc.NpcItemStack> remainderMailItems(
            NamespacedId itemId, int remainderCount, String components) {
        if (remainderCount <= 0) {
            return List.of();
        }
        var items = new ArrayList<com.storynpcs.domain.npc.NpcItemStack>();
        int remaining = remainderCount;
        while (remaining > 0) {
            int chunk = Math.min(com.storynpcs.domain.npc.NpcItemStack.MAX_COUNT, remaining);
            items.add(com.storynpcs.domain.npc.NpcItemStack.of(itemId, chunk, components));
            remaining -= chunk;
        }
        return List.copyOf(items);
    }

    /**
     * Persists an undelivered item remainder as a new overflow mail. The mail
     * carries no experience — the source mail's XP leg is granted through the
     * delivery path itself — so re-queueing can never double-grant XP.
     */
    void enqueueItemRemainderMail(UUID playerUuid, NamespacedId questId,
            List<com.storynpcs.domain.npc.NpcItemStack> remainderItems, long gameTime) throws IOException {
        var mailStore = questMailStore;
        if (mailStore == null || remainderItems == null || remainderItems.isEmpty()) {
            return;
        }
        mailStore.enqueue(new com.storynpcs.domain.quest.QuestMail(
                UUID.randomUUID(), playerUuid, questId, remainderItems,
                0, "INVENTORY_FULL", gameTime, 0, 0));
    }

    /** Result of a postman mail delivery for messaging the interacting player. */
    public record MailDeliverySummary(int mailsClaimed, int experienceGranted,
                                      List<String> itemLines) {}

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
                        // Non-atomic by policy: validated up-front, executed
                        // once under a durable delivered mark, and a failure is
                        // reported rather than retried (never silently replayed).
                        if (reward.getTarget() == null || reward.getTarget().isBlank()) {
                            return QuestCompletionResult.rejected("COMMAND_REWARD_TARGET_MISSING");
                        }
                        if (minecraftServer != null && minecraftServer.getPlayerList().getPlayer(playerUuid) == null) {
                            return QuestCompletionResult.rejected("PLAYER_OFFLINE");
                        }
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

    /** Replay-aware trade compatibility delegate used by callers carrying a request ID. */
    public boolean executeTrade(UUID playerUuid, NamespacedId npcId, int listingIndex,
                                com.storynpcs.domain.role.trader.TradeListing trade,
                                UUID requestId) {
        if (playerUuid == null || npcId == null || trade == null || requestId == null) return false;
        return executeTrade(new TradeExecutionRequest(
                "player", playerUuid, playerUuid, npcId, listingIndex, requestId, -1), trade).applied();
    }

    /**
     * Typed canonical trade execution. The actor/subject/capability envelope is
     * authorized before the journaled mutation runs, and every attempt — denied
     * or applied — publishes a canonical mutation event for the trader target.
     */
    public CanonicalMutationResult executeTrade(TradeExecutionRequest request,
            com.storynpcs.domain.role.trader.TradeListing trade) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(trade, "trade");
        AuthorizationDecision authorization = AuthorizationPolicy.evaluate(request);
        if (!authorization.allowed()) {
            ValidationResult denied = ValidationResult.valid();
            denied.addError(authorization.code(), authorization.message());
            var rejected = new CanonicalMutationResult(false, false, 0L, denied,
                    List.of(request.operation() + ":authorization-denied"), "REJECTED_AUTHORIZATION");
            publishTradeEvent(request, rejected);
            return rejected;
        }
        boolean applied = executeTradeInternal(request.playerUuid(), request.npcId(),
                request.listingIndex(), trade, request.requestId());
        CanonicalMutationResult result;
        if (applied) {
            result = new CanonicalMutationResult(true, false, 0L, ValidationResult.valid(),
                    List.of("TradeExecutedEvent"), "COMMITTED");
        } else {
            ValidationResult failure = ValidationResult.valid();
            failure.addError("TRADE_NOT_APPLIED",
                    "Trade validation failed, sold out, or left pending for recovery");
            result = new CanonicalMutationResult(false, false, 0L, failure,
                    List.of(request.operation() + ":rejected"), "REJECTED_NO_SIDE_EFFECTS");
        }
        publishTradeEvent(request, result);
        return result;
    }

    private void publishTradeEvent(TradeExecutionRequest request, CanonicalMutationResult result) {
        eventPublisher.publish(new CanonicalMutationEvent(request.operation(), request.actorType(),
                request.npcId(), request.requestId(), result.applied(), result.revision(),
                result.recoveryOutcome(), request.actorId(), request.playerUuid()));
    }

    private boolean executeTradeInternal(UUID playerUuid, NamespacedId npcId, int listingIndex,
                                com.storynpcs.domain.role.trader.TradeListing trade,
                                UUID requestId) {
        if (playerUuid == null || npcId == null || trade == null || requestId == null) return false;
        // Serialize the prepare/commit/recover decision per request id — recovery
        // must not abort a prepared record between its durable intent and the
        // listing-use commit decision.
        var outcome = tradeOperationJournal.withOperationLock(requestId, () -> {
            if (tradeStateRepository == null || listingIndex < 0) {
                return executeTradeJournaled(playerUuid, npcId, listingIndex, trade, requestId);
            }
            String listingId = trade.ensureStableId();
            int lockIndex = Math.floorMod(Objects.hash(npcId, listingId), tradeMutationLocks.length);
            synchronized (tradeMutationLocks[lockIndex]) {
                return executeTradeJournaled(playerUuid, npcId, listingIndex, trade, requestId);
            }
        });
        return outcome != null && outcome;
    }

    private boolean executeTradeJournaled(UUID playerUuid, NamespacedId npcId, int listingIndex,
                                          com.storynpcs.domain.role.trader.TradeListing trade,
                                          UUID requestId) {
        String listingId = trade.ensureStableId();
        String legacyListingId = trade.legacyListingIdForMigration().orElse(null);
        String requiredFactionId = trade.getRequiredFaction() == null
                ? "" : trade.getRequiredFaction().toString();
        String secondaryPriceItemId = trade.hasTwoInputs()
                ? trade.getSecondaryPriceItemId().trim() : "";
        int secondaryPriceCount = trade.hasTwoInputs() ? trade.getSecondaryPriceCount() : 0;
        String subject = tradeOperationSubject(playerUuid, npcId, listingIndex, listingId,
                trade, requiredFactionId, secondaryPriceItemId, secondaryPriceCount);
        String legacySubject = trade.hasTwoInputs()
                ? tradeOperationSubject(playerUuid, npcId, listingIndex,
                        legacyListingId == null ? listingId : legacyListingId,
                        trade, requiredFactionId, "", 0)
                : null;
        try {
            var existing = tradeOperationJournal.read(requestId);
            if (existing != null) {
                boolean legacyReplay = legacySubject != null
                        && "trade.execute".equals(existing.operationType())
                        && legacySubject.equals(existing.subject());
                String replaySubject = legacyReplay ? legacySubject : subject;
                var replay = tradeOperationJournal.begin(requestId, "trade.execute", replaySubject);
                if (replay.status() == com.storynpcs.persistence.DurableOperationJournal.BeginStatus.PENDING
                        && listingIndex >= 0) {
                    // A still-prepared record means the first attempt crashed
                    // between prepare and commit — reconcile it against the
                    // durable listing use count instead of leaving the request
                    // id pending and failing every retry forever.
                    reconcilePendingTrade(playerUuid, requestId);
                    replay = tradeOperationJournal.begin(requestId, "trade.execute", replaySubject);
                }
                return replay.status() == com.storynpcs.persistence.DurableOperationJournal.BeginStatus.COMMITTED;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }

        int usesBefore = Math.max(0, trade.getUses());
        if (tradeStateRepository != null && listingIndex >= 0) {
            try {
                // P7-1: lazy restock — the durable boundary check runs inside
                // the same state record as uses, so a due restock resets uses
                // before the reservation reads them. The repo write is atomic:
                // a crash cannot split "reset" from "boundary advanced".
                // The listing's own interval wins; the role-wide interval is
                // the fallback (target daily-reset default).
                if (minecraftServer != null) {
                    long restockInterval = registry.getNpc(npcId)
                            .map(com.storynpcs.domain.npc.NpcDefinition::getTrader)
                            .map(trader -> trader.effectiveRestockInterval(trade))
                            .orElse(trade.getRestockIntervalTicks());
                    tradeStateRepository.restockIfDue(npcId.toString(), listingId,
                            restockInterval, minecraftServer.overworld().getGameTime());
                }
                usesBefore = tradeStateRepository.getUsesOrMigrateLegacy(
                        npcId.toString(), listingId, legacyListingId);
            } catch (IOException | RuntimeException unavailable) {
                return false;
            }
        }

        com.storynpcs.persistence.TradeOperationIntent intent;
        try {
            intent = new com.storynpcs.persistence.TradeOperationIntent(
                    playerUuid, npcId.toString(), listingIndex, listingId,
                    trade.getOfferItemId(), Math.max(1, trade.getOfferCount()),
                    trade.getPriceItemId(), Math.max(1, trade.getPriceCount()),
                    secondaryPriceItemId, secondaryPriceCount,
                    Math.max(0, trade.getMaxUses()), usesBefore,
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

        var mutationOutcome = executeTradeMutation(playerUuid, npcId, listingIndex, trade, usesBefore);
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

    private static String tradeOperationSubject(UUID playerUuid, NamespacedId npcId, int listingIndex,
            String listingId, com.storynpcs.domain.role.trader.TradeListing trade,
            String requiredFactionId, String secondaryPriceItemId, int secondaryPriceCount) {
        String subject = playerUuid + "|" + npcId + "|" + listingIndex
                + "|" + listingId + "|" + trade.getOfferItemId() + "|" + Math.max(1, trade.getOfferCount())
                + "|" + trade.getPriceItemId() + "|" + Math.max(1, trade.getPriceCount())
                + "|" + Math.max(0, trade.getMaxUses()) + "|" + requiredFactionId
                + "|" + Math.max(0, trade.getRequiredFactionPoints());
        if (!secondaryPriceItemId.isEmpty()) {
            subject += "|secondary|" + secondaryPriceItemId + "|" + secondaryPriceCount;
        }
        return subject;
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
                                         com.storynpcs.domain.role.trader.TradeListing trade, int usesBefore) {
        if (tradeStateRepository != null && listingIndex >= 0) {
            String listingId = trade.ensureStableId();
            synchronized (trade) {
                int expectedUses = Math.max(0, usesBefore);
                boolean reserved = false;
                try {
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

        // A malformed listing (e.g. a secondary count with no item id) must
        // never reach the exchange — validate throws and the caller's
        // RuntimeException boundary classifies it as REJECTED.
        trade.validate();

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

            // Second input slot: same contract — resolved item + held amount
            // verified before anything mutates, deducted with the primary
            // input, and compensated identically on delivery failure.
            net.minecraft.world.item.Item secondaryItem = null;
            int secondaryRequired = 0;
            if (trade.hasTwoInputs()) {
                var secondaryRl = net.minecraft.resources.ResourceLocation.tryParse(
                        trade.getSecondaryPriceItemId().trim());
                if (secondaryRl == null) return false;
                var secondaryOpt = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(secondaryRl);
                if (secondaryOpt.isEmpty()) return false;
                final var resolvedSecondary = secondaryOpt.get();
                secondaryItem = resolvedSecondary;
                secondaryRequired = trade.getSecondaryPriceCount();
                int heldSecondary = player.getInventory().items.stream()
                        .filter(s -> !s.isEmpty() && s.getItem() == resolvedSecondary)
                        .mapToInt(net.minecraft.world.item.ItemStack::getCount)
                        .sum();
                if (heldSecondary < secondaryRequired) {
                    return false; // insufficient second input — abort without recording
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

            var inventory = player.getInventory();
            java.util.List<net.minecraft.world.item.ItemStack> inventorySnapshot = inventory.items.stream()
                    .map(net.minecraft.world.item.ItemStack::copy).toList();
            try {
                if (priceItem != null) {
                    int toRemove = required;
                    for (net.minecraft.world.item.ItemStack slot : player.getInventory().items) {
                        if (!slot.isEmpty() && slot.getItem() == priceItem && toRemove > 0) {
                            int take = Math.min(slot.getCount(), toRemove);
                            slot.shrink(take);
                            toRemove -= take;
                        }
                    }
                    // The held-check passed above, so a leftover means inventory
                    // shrank mid-exchange — fail closed: the catch restores every
                    // stack and rolls the reservation back rather than trade at
                    // a discount.
                    if (toRemove > 0) {
                        throw new IllegalStateException(
                                "primary price under-deducted by " + toRemove);
                    }
                }
                if (secondaryItem != null) {
                    int toRemove = secondaryRequired;
                    for (net.minecraft.world.item.ItemStack slot : player.getInventory().items) {
                        if (!slot.isEmpty() && slot.getItem() == secondaryItem && toRemove > 0) {
                            int take = Math.min(slot.getCount(), toRemove);
                            slot.shrink(take);
                            toRemove -= take;
                        }
                    }
                    if (toRemove > 0) {
                        throw new IllegalStateException(
                                "secondary price under-deducted by " + toRemove);
                    }
                }
                if (offerItem != null) {
                    net.minecraft.world.item.ItemStack offerStack =
                            new net.minecraft.world.item.ItemStack(offerItem, Math.max(1, trade.getOfferCount()));
                    inventory.add(offerStack);
                    if (!offerStack.isEmpty()) {
                        // P7-1 / P5-5 overflow policy: anything the inventory
                        // could not absorb — including a PARTIAL fit, which a
                        // bare !add() check would silently void — is queued as
                        // durable claim-once mail (provenance = the trading
                        // NPC's id). A mail-write failure propagates into the
                        // catch, restoring the snapshot + rolling the use back
                        // so the exchange stays side-effect-free.
                        if (questMailStore == null) {
                            throw new IllegalStateException(
                                    "mail store unavailable — cannot queue trade overflow");
                        }
                        try {
                            enqueueItemRemainderMail(playerUuid, npcId,
                                    remainderMailItems(
                                            NamespacedId.of(trade.getOfferItemId().trim()),
                                            offerStack.getCount(), null),
                                    minecraftServer.overworld().getGameTime());
                        } catch (java.io.IOException mailFailure) {
                            // Checked IO must land in the RuntimeException
                            // boundary so the inventory snapshot restores.
                            throw new IllegalStateException(
                                    "trade overflow mail write failed", mailFailure);
                        }
                    }
                }
                reservation.commit();
            } catch (RuntimeException failure) {
                for (int i = 0; i < inventorySnapshot.size(); i++) {
                    inventory.items.set(i, inventorySnapshot.get(i));
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

    /** Applies one typed, owner-scoped follower mutation through the canonical boundary. */
    public CanonicalMutationResult mutateFollowerState(
            FollowerStateMutationRequest request, com.storynpcs.domain.role.follower.FollowerRole role) {
        Objects.requireNonNull(request, "request");
        AuthorizationDecision authorization = AuthorizationPolicy.evaluate(request);
        String fingerprint = followerMutationFingerprint(request);
        if (!authorization.allowed()) {
            CanonicalMutationResult denied = followerMutationFailure(authorization.code(), authorization.message());
            dispatchQuestEvents(request.playerUuid(), List.of(followerMutationEvent(request, denied)));
            return denied;
        }

        Object requestLock = questMutationLocks[request.requestId().hashCode() & (questMutationLocks.length - 1)];
        CanonicalMutationResult result;
        List<StoryNpcsEvent> notifications = new ArrayList<>();
        synchronized (requestLock) {
            result = applyFollowerMutation(request, fingerprint, role, notifications);
            if (!result.duplicate()) notifications.add(0, followerMutationEvent(request, result));
        }
        dispatchQuestEvents(request.playerUuid(), notifications);
        return result;
    }

    private CanonicalMutationResult applyFollowerMutation(
            FollowerStateMutationRequest request, String fingerprint,
            com.storynpcs.domain.role.follower.FollowerRole role, List<StoryNpcsEvent> notifications) {
        CompletedFollowerMutation completed = completedFollowerMutations.get(request.requestId());
        if (completed != null) {
            if (!completed.payloadFingerprint().equals(fingerprint)) {
                return followerMutationFailure("REQUEST_PAYLOAD_MISMATCH",
                        "Request ID is already bound to a different follower mutation payload");
            }
            CanonicalMutationResult prior = completed.result();
            return new CanonicalMutationResult(prior.applied(), true, prior.revision(),
                    prior.diagnostics(), prior.events(), prior.recoveryOutcome());
        }

        if (role == null) {
            CanonicalMutationResult unavailable = followerMutationFailure("FOLLOWER_ROLE_UNAVAILABLE",
                    "No follower role is attached to NPC: " + request.npcId());
            rememberFollowerMutation(request, fingerprint, unavailable);
            return unavailable;
        }
        synchronized (role) {
            if (!role.isOwnedBy(request.playerUuid())) {
                CanonicalMutationResult notOwned = followerMutationFailure("FOLLOWER_NOT_OWNED",
                        "Player " + request.playerUuid() + " does not own follower " + request.npcId());
                rememberFollowerMutation(request, fingerprint, notOwned);
                return notOwned;
            }
            if (request.action() == FollowerStateMutationRequest.Action.SET_STATE) {
                var oldState = role.getState();
                role.setState(request.state());
                notifications.add(new com.storynpcs.api.event.FollowerStateChangeEvent(
                        request.playerUuid(), request.npcId(), oldState, request.state()));
            } else {
                var oldFormation = role.getFormation();
                role.setFormation(request.formation());
                role.setFormationSlot(request.slotIndex());
                role.setFormationSpacing(request.spacing());
                notifications.add(new com.storynpcs.api.event.FollowerFormationChangeEvent(
                        request.playerUuid(), request.npcId(), oldFormation, request.formation(),
                        request.slotIndex(), role.getFormationSpacing()));
            }
        }

        String eventName = request.action() == FollowerStateMutationRequest.Action.SET_STATE
                ? "FollowerStateChangeEvent" : "FollowerFormationChangeEvent";
        CanonicalMutationResult applied = new CanonicalMutationResult(true, false, 0L,
                ValidationResult.valid(), List.of(eventName), "COMMITTED");
        rememberFollowerMutation(request, fingerprint, applied);
        return applied;
    }

    private void rememberFollowerMutation(FollowerStateMutationRequest request, String fingerprint,
                                          CanonicalMutationResult result) {
        completedFollowerMutations.put(request.requestId(),
                new CompletedFollowerMutation(fingerprint, result.snapshot()));
    }

    private static CanonicalMutationResult followerMutationFailure(String code, String message) {
        ValidationResult diagnostics = ValidationResult.valid();
        diagnostics.addError(code, message == null || message.isBlank() ? code : message);
        return new CanonicalMutationResult(false, false, 0L, diagnostics, List.of(),
                Set.of("PERMISSION_DENIED", "PLAYER_SUBJECT_MISMATCH", "SCRIPT_CAPABILITY_REQUIRED").contains(code)
                        ? "REJECTED_AUTHORIZATION" : "REJECTED_NO_SIDE_EFFECTS");
    }

    private static String followerMutationFingerprint(FollowerStateMutationRequest request) {
        String payload = String.join("\u0000", request.actorType(),
                request.actorId() == null ? "" : request.actorId().toString(),
                request.playerUuid().toString(), request.npcId().toString(), request.action().name(),
                request.state() == null ? "" : request.state().name(),
                request.formation() == null ? "" : request.formation().name(),
                Integer.toString(request.slotIndex()), Double.toString(request.spacing()));
        return MutationPayloadFingerprint.of(request.operation(), payload);
    }

    private CanonicalMutationEvent followerMutationEvent(
            FollowerStateMutationRequest request, CanonicalMutationResult result) {
        return new CanonicalMutationEvent(request.operation(), request.actorType(), request.npcId(),
                request.requestId(), result.applied(), result.revision(), result.recoveryOutcome(),
                request.actorId(), request.playerUuid());
    }

    public boolean setFollowerState(UUID playerUuid, NamespacedId npcId, com.storynpcs.domain.role.follower.FollowerRole role, com.storynpcs.domain.role.follower.FollowerRole.State newState) {
        return mutateFollowerState(FollowerStateMutationRequest.setState(
                "system", null, playerUuid, npcId, newState, UUID.randomUUID()), role).applied();
    }

    public boolean setFollowerFormation(
            UUID playerUuid,
            NamespacedId npcId,
            com.storynpcs.domain.role.follower.FollowerRole role,
            com.storynpcs.domain.role.follower.FormationType newFormation,
            int slotIndex,
            double spacing
    ) {
        double effectiveSpacing = spacing > 0.0 ? spacing
                : role != null ? role.getFormationSpacing() : 2.5;
        return mutateFollowerState(FollowerStateMutationRequest.setFormation(
                "system", null, playerUuid, npcId, newFormation, slotIndex,
                effectiveSpacing, UUID.randomUUID()), role).applied();
    }

    public boolean depositToBank(UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo, int tab, int slot, String itemId, int count) {
        // VULN-54: delegate to tagged overload with null tag — callers that have tag data should use the overload below
        return depositToBank(playerUuid, bankRepo, tab, slot, itemId, count, null);
    }

    /**
     * Deposit an item into the player's bank vault preserving its NBT/DataComponent tag.
     * VULN-54: This overload must be used by GUI and command code that has access to the full ItemStack tag.
     * Compatibility delegate: routes through the typed canonical request boundary.
     */
    public boolean depositToBank(UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo, int tab, int slot, String itemId, int count, String tag) {
        if (playerUuid == null || itemId == null) return false;
        try {
            return depositToBank(new BankOperationRequest("player", playerUuid, playerUuid, null,
                    BankOperationRequest.Action.DEPOSIT, tab, slot, itemId, count,
                    UUID.randomUUID(), -1), bankRepo, tag).accepted();
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /**
     * Typed canonical bank deposit: authorizes the actor/subject/capability
     * envelope before any vault mutation, then routes to the durable deposit
     * path matching the requested action. Every attempt on a banker-scoped
     * request publishes a canonical mutation event.
     */
    public BankDepositOperationResult depositToBank(BankOperationRequest request,
            com.storynpcs.persistence.BankRepository bankRepo, String tag) {
        Objects.requireNonNull(request, "request");
        AuthorizationDecision authorization = AuthorizationPolicy.evaluate(request);
        if (!authorization.allowed()) {
            publishBankEvent(request, false, "REJECTED_AUTHORIZATION");
            return BankDepositOperationResult.rejected(authorization.code());
        }
        if (bankRepo == null) {
            publishBankEvent(request, false, "REJECTED_NO_SIDE_EFFECTS");
            return BankDepositOperationResult.rejected("BANK_UNAVAILABLE");
        }
        String accessDeny = bankVaultAccessDenyCode(request, bankRepo);
        if (accessDeny != null) {
            publishBankEvent(request, false, accessDeny);
            return BankDepositOperationResult.rejected(accessDeny);
        }
        BankDepositOperationResult result = switch (request.action()) {
            case DEPOSIT -> depositToBankInternal(request, bankRepo, request.tab(),
                    request.slot(), request.itemId(), request.amount(), tag)
                    ? BankDepositOperationResult.applied(request.slot())
                    : BankDepositOperationResult.rejected("DEPOSIT_NOT_APPLIED");
            case DEPOSIT_AUTO -> {
                int slot = depositToBankAutoInternal(request, bankRepo, request.tab(),
                        request.itemId(), request.amount(), tag);
                yield slot >= 0 ? BankDepositOperationResult.applied(slot)
                        : BankDepositOperationResult.rejected("TAB_LOCKED_OR_FULL");
            }
            case DEPOSIT_HELD -> depositHeldToBankInternal(scopeFor(request),
                    bankRepo, request.tab(), request.requestId());
            default -> BankDepositOperationResult.rejected("ACTION_ROUTE_MISMATCH");
        };
        publishBankEvent(request, result.accepted(), result.accepted() ? "COMMITTED" : result.code());
        return result;
    }

    /**
     * Pre-mutation shared-vault access gate. Self-vault requests pass trivially;
     * cross-vault requests require operator authority or a durable SHARED vault
     * that lists the acting player. Membership is re-verified inside each vault
     * transaction so a revocation between this gate and the commit cannot slip.
     */
    private String bankVaultAccessDenyCode(BankOperationRequest request,
            com.storynpcs.persistence.BankRepository bankRepo) {
        UUID owner = request.resolvedVaultOwner();
        if (owner.equals(request.playerUuid())) return null;
        if ("system".equals(request.actorType())) return null;
        if ("command".equals(request.actorType()) && request.permissionLevel() >= 2) return null;
        com.storynpcs.domain.role.banker.BankVault vault;
        try {
            vault = bankRepo.getOrCreate(owner);
        } catch (RuntimeException unavailable) {
            return "VAULT_UNAVAILABLE";
        }
        return vault.canAccess(request.playerUuid()) ? null : "VAULT_ACCESS_DENIED";
    }

    /** True when this request may operate on any vault regardless of membership. */
    private boolean hasVaultAccessOverride(BankOperationRequest request) {
        return request.resolvedVaultOwner().equals(request.playerUuid())
                || "system".equals(request.actorType())
                || ("command".equals(request.actorType()) && request.permissionLevel() >= 2);
    }

    /** Atomic membership re-check for use inside a vault transaction lambda. */
    private boolean vaultAccessAllowed(BankOperationRequest request,
                                       com.storynpcs.domain.role.banker.BankVault vault) {
        return hasVaultAccessOverride(request) || vault.canAccess(request.playerUuid());
    }

    /**
     * Identity + authorization scope resolved once per canonical bank request.
     * {@code actorUuid} owns the inventory side-effects (held stacks, emerald
     * payments, delivered items); {@code vaultOwnerUuid} keys the durable vault.
     */
    private record BankOperationScope(UUID actorUuid, UUID vaultOwnerUuid, boolean accessOverride) {
        /** Membership check executed inside the vault lock. */
        boolean mayAccess(com.storynpcs.domain.role.banker.BankVault vault) {
            return accessOverride || vault.canAccess(actorUuid);
        }
    }

    private BankOperationScope scopeFor(BankOperationRequest request) {
        return new BankOperationScope(request.playerUuid(), request.resolvedVaultOwner(),
                hasVaultAccessOverride(request));
    }

    private boolean depositToBankInternal(BankOperationRequest request, com.storynpcs.persistence.BankRepository bankRepo, int tab, int slot, String itemId, int count, String tag) {
        if (bankRepo == null) return false;
        UUID vaultOwner = request.resolvedVaultOwner();
        var result = bankRepo.transact(vaultOwner, vault -> {
            // Membership re-check inside the vault lock — atomic against
            // sharing-policy revocations in a competing transaction.
            if (!vaultAccessAllowed(request, vault)) {
                return com.storynpcs.persistence.BankRepository.BankMutation.unchanged(false);
            }
            boolean changed = vault.deposit(tab, slot, itemId, count, tag);
            return changed
                    ? com.storynpcs.persistence.BankRepository.BankMutation.changed(true)
                    : com.storynpcs.persistence.BankRepository.BankMutation.unchanged(false);
        });
        if (result.committed()) {
            eventPublisher.publish(new com.storynpcs.api.event.BankTransactionEvent(
                    vaultOwner, com.storynpcs.api.event.BankTransactionEvent.Type.DEPOSIT, tab, itemId, count));
        }
        return result.committed() && Boolean.TRUE.equals(result.value());
    }

    /**
     * Deposit into the first compatible slot of the given tab (merge or first free slot).
     * Returns the slot index used, or -1 on failure (locked tab, full tab, invalid input).
     * Compatibility delegate: routes through the typed canonical request boundary.
     */
    public int depositToBankAuto(UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo, int tab, String itemId, int count, String tag) {
        if (playerUuid == null || itemId == null) return -1;
        try {
            return depositToBank(new BankOperationRequest("player", playerUuid, playerUuid, null,
                    BankOperationRequest.Action.DEPOSIT_AUTO, tab, -1, itemId, count,
                    UUID.randomUUID(), -1), bankRepo, tag).slot();
        } catch (IllegalArgumentException invalid) {
            return -1;
        }
    }

    private int depositToBankAutoInternal(BankOperationRequest request, com.storynpcs.persistence.BankRepository bankRepo, int tab, String itemId, int count, String tag) {
        if (bankRepo == null) return -1;
        UUID vaultOwner = request.resolvedVaultOwner();
        var result = bankRepo.transact(vaultOwner, vault -> {
            if (!vaultAccessAllowed(request, vault)) {
                return com.storynpcs.persistence.BankRepository.BankMutation.unchanged(-1);
            }
            int slot = vault.depositAuto(tab, itemId, count, tag);
            return slot >= 0
                    ? com.storynpcs.persistence.BankRepository.BankMutation.changed(slot)
                    : com.storynpcs.persistence.BankRepository.BankMutation.unchanged(-1);
        });
        if (result.committed()) {
            eventPublisher.publish(new com.storynpcs.api.event.BankTransactionEvent(
                    vaultOwner, com.storynpcs.api.event.BankTransactionEvent.Type.DEPOSIT, tab, itemId, count));
        }
        return result.committed() ? result.value() : -1;
    }

    /**
     * Journaled server-side deposit of the player's main-hand stack. The bank
     * commit carries a vault-side operation marker before the inventory stack
     * is removed, so a crash can prove which side reached durable storage.
     */
    /**
     * Compatibility delegate: routes through the typed canonical request boundary
     * ({@link #depositToBank(BankOperationRequest, com.storynpcs.persistence.BankRepository, String)}
     * with action {@code DEPOSIT_HELD}).
     */
    public BankDepositOperationResult depositHeldToBank(
            UUID playerUuid,
            com.storynpcs.persistence.BankRepository bankRepo,
            int tab,
            UUID requestId) {
        if (playerUuid == null || requestId == null) {
            return BankDepositOperationResult.rejected("INVALID_REQUEST");
        }
        try {
            return depositToBank(new BankOperationRequest("player", playerUuid, playerUuid, null,
                    BankOperationRequest.Action.DEPOSIT_HELD, tab, -1, null, 0,
                    requestId, -1), bankRepo, null);
        } catch (IllegalArgumentException invalid) {
            return BankDepositOperationResult.rejected("INVALID_REQUEST");
        }
    }

    private BankDepositOperationResult depositHeldToBankInternal(
            BankOperationScope scope,
            com.storynpcs.persistence.BankRepository bankRepo,
            int tab,
            UUID requestId) {
        if (scope == null || bankRepo == null || requestId == null) {
            return BankDepositOperationResult.rejected("INVALID_REQUEST");
        }
        // Serialize the prepare/commit/recover decision per request id — recovery
        // must not abort a request between its durable prepare and vault commit.
        var result = bankRepo.operationJournal().withOperationLock(requestId,
                () -> depositHeldToBankJournaled(scope, bankRepo, tab, requestId));
        return result != null ? result : BankDepositOperationResult.recoveryRequired("JOURNAL_UNAVAILABLE");
    }

    private BankDepositOperationResult depositHeldToBankJournaled(
            BankOperationScope scope,
            com.storynpcs.persistence.BankRepository bankRepo,
            int tab,
            UUID requestId) {
        final UUID playerUuid = scope.actorUuid();
        final UUID vaultOwnerUuid = scope.vaultOwnerUuid();
        final String operationType = "bank.deposit_held";
        // Subject binds actor and vault — a request id cannot be replayed
        // across a different vault or on behalf of a different member.
        final String subject = bankOperationSubject(scope);
        var journal = bankRepo.operationJournal();

        try {
            var existing = journal.read(requestId);
            if (existing != null) {
                var classification = journal.begin(requestId, operationType, subject);
                return classifyHeldDepositReplay(scope, bankRepo, requestId, classification);
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
            vault = bankRepo.getOrCreate(vaultOwnerUuid);
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
                return classifyHeldDepositReplay(scope, bankRepo, requestId, started);
            }
        } catch (IOException | RuntimeException e) {
            return BankDepositOperationResult.recoveryRequired("JOURNAL_START_FAILED");
        }

        var result = bankRepo.transact(vaultOwnerUuid, candidateVault -> {
            if (!scope.mayAccess(candidateVault)) {
                return com.storynpcs.persistence.BankRepository.BankMutation.unchanged(false);
            }
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
                    vaultOwnerUuid, com.storynpcs.api.event.BankTransactionEvent.Type.DEPOSIT,
                    tab, itemId, count));
            return finalizeCommittedHeldDeposit(scope, bankRepo, requestId, committed, false);
        } catch (IOException | RuntimeException e) {
            return BankDepositOperationResult.recoveryRequired("BANK_COMMIT_REQUIRES_RECOVERY");
        }
    }

    private BankDepositOperationResult classifyHeldDepositReplay(
            BankOperationScope scope,
            com.storynpcs.persistence.BankRepository bankRepo,
            UUID requestId,
            com.storynpcs.persistence.DurableOperationJournal.BeginResult result) {
        return switch (result.status()) {
            case COMMITTED -> finalizeCommittedHeldDeposit(scope, bankRepo, requestId,
                    result.record(), true);
            case ABORTED -> BankDepositOperationResult.rejected(
                    result.record().outcomeCode() == null ? "ABORTED" : result.record().outcomeCode());
            case PENDING, STARTED -> reconcilePendingHeldDeposit(
                    scope, bankRepo, requestId, result.record());
        };
    }

    private BankDepositOperationResult reconcilePendingHeldDeposit(
            BankOperationScope scope,
            com.storynpcs.persistence.BankRepository bankRepo,
            UUID requestId,
            com.storynpcs.persistence.DurableOperationJournal.OperationRecord record) {
        try {
            var marker = bankRepo.getOrCreate(scope.vaultOwnerUuid()).getOperationMarker(requestId);
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
            return finalizeCommittedHeldDeposit(scope, bankRepo, requestId, committed, true);
        } catch (IOException | RuntimeException e) {
            return BankDepositOperationResult.recoveryRequired("PENDING_RECOVERY_FAILED");
        }
    }

    private BankDepositOperationResult finalizeCommittedHeldDeposit(
            BankOperationScope scope,
            com.storynpcs.persistence.BankRepository bankRepo,
            UUID requestId,
            com.storynpcs.persistence.DurableOperationJournal.OperationRecord record,
            boolean replay) {
        try {
            int slot = Integer.parseInt(record.detail());
            final UUID vaultOwnerUuid = scope.vaultOwnerUuid();
            var marker = bankRepo.getOrCreate(vaultOwnerUuid).getOperationMarker(requestId);
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
                boolean stackPresent = bankRepo.getOrCreate(vaultOwnerUuid).getTabItems(operation.tab())
                        .stream().anyMatch(operation::matches);
                return stackPresent
                        ? BankDepositOperationResult.replayed(slot)
                        : BankDepositOperationResult.recoveryRequired("BANK_MARKER_MISSING");
            }
            if (marker.isInventoryApplied()) {
                var cleanupApplied = bankRepo.transact(vaultOwnerUuid, vault ->
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
            var player = minecraftServer.getPlayerList().getPlayer(scope.actorUuid());
            if (player == null) {
                return BankDepositOperationResult.recoveryRequired("PLAYER_OFFLINE");
            }
            var held = player.getMainHandItem();
            if (!heldStackMatchesIntent(player, held, intent)) {
                return BankDepositOperationResult.recoveryRequired("INVENTORY_RECONCILIATION_REQUIRED");
            }
            held.shrink(intent.count());
            var markedApplied = bankRepo.transact(vaultOwnerUuid, vault ->
                    vault.markOperationInventoryApplied(requestId)
                            ? com.storynpcs.persistence.BankRepository.BankMutation.changed(true)
                            : com.storynpcs.persistence.BankRepository.BankMutation.unchanged(false));
            if (!markedApplied.committed()) {
                return BankDepositOperationResult.recoveryRequired("INVENTORY_MARKER_WRITE_FAILED");
            }
            var cleanup = bankRepo.transact(vaultOwnerUuid, vault ->
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
            UUID actorUuid,
            UUID vaultOwnerUuid,
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
        if (!actorUuid.equals(withdrawal.playerUuid())
                || !"withdraw".equals(withdrawal.action())
                || withdrawal.count() != withdrawal.beforeCount()
                || withdrawal.count() != withdrawal.expectedCount()
                || withdrawal.vaultRevision() == null) {
            return false;
        }

        boolean exactOriginalStackStillPresent;
        try {
            var vault = bankRepo.getOrCreate(vaultOwnerUuid).copy();
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
        try {
            // Retention sweep — login recovery is the journal's regular scan
            // point, so terminal record files cannot accumulate unbounded.
            bankRepo.operationJournal().pruneTerminalRecords();
        } catch (IOException | RuntimeException ignored) {
            // Housekeeping must never block recovery.
        }
        java.util.List<com.storynpcs.persistence.DurableOperationJournal.OperationRecord> pendingRecords;
        java.util.Map<UUID, UUID> depositScopes = new java.util.LinkedHashMap<>();
        java.util.Map<UUID, UUID> unlockScopes = new java.util.LinkedHashMap<>();
        try {
            String playerSubject = playerUuid.toString();
            String compoundSubjectPrefix = playerSubject + "|";
            var journalScan = bankRepo.operationJournal().pending();
            for (String diagnostic : journalScan.diagnostics()) {
                System.err.println("[StoryNPCs] bank operation journal recovery for "
                        + playerUuid + ": " + diagnostic);
            }
            pendingRecords = journalScan.records().stream()
                    .filter(record -> playerSubject.equals(record.subject())
                            || record.subject().startsWith(compoundSubjectPrefix))
                    .toList();
            for (var record : pendingRecords) {
                // Compound subjects are "actor|vaultOwner|..." for shared-vault
                // operations — recover against the durable vault identity, not
                // the scanning player's own vault.
                UUID vaultOwner = vaultOwnerFromSubject(record.subject(), playerUuid);
                if ("bank.deposit_held".equals(record.operationType())) {
                    depositScopes.put(record.operationId(), vaultOwner);
                }
                if ("bank.unlock_tab".equals(record.operationType())) {
                    unlockScopes.put(record.operationId(), vaultOwner);
                }
            }
            bankRepo.getOrCreate(playerUuid).getOperationMarkers().values().stream()
                    .filter(marker -> "bank.deposit_held".equals(marker.getOperationType()))
                    .map(com.storynpcs.domain.role.banker.BankVault.OperationMarker::getOperationId)
                    .forEach(id -> depositScopes.putIfAbsent(id, playerUuid));
            bankRepo.getOrCreate(playerUuid).getOperationMarkers().values().stream()
                    .filter(marker -> "bank.unlock_tab".equals(marker.getOperationType()))
                    .map(com.storynpcs.domain.role.banker.BankVault.OperationMarker::getOperationId)
                    .forEach(id -> unlockScopes.putIfAbsent(id, playerUuid));
        } catch (IOException | RuntimeException e) {
            return 1;
        }
        int unresolved = 0;
        for (var record : pendingRecords) {
            if ("bank.withdraw".equals(record.operationType())) {
                UUID vaultOwner = vaultOwnerFromSubject(record.subject(), playerUuid);
                boolean resolved;
                try {
                    resolved = bankRepo.operationJournal().withOperationLock(record.operationId(),
                            () -> reconcilePendingBankWithdrawal(
                                    playerUuid, vaultOwner, bankRepo, record.operationId()));
                } catch (RuntimeException failure) {
                    resolved = false;
                }
                if (!resolved) unresolved++;
            }
        }
        for (var entry : depositScopes.entrySet()) {
            BankDepositOperationResult result = depositToBank(new BankOperationRequest(
                    "system", null, playerUuid, null,
                    BankOperationRequest.Action.DEPOSIT_HELD, 0, -1, null, 0,
                    entry.getKey(), -1, entry.getValue()), bankRepo, null);
            if (result.outcome() == BankDepositOperationResult.Outcome.RECOVERY_REQUIRED) {
                unresolved++;
            }
        }
        for (var entry : unlockScopes.entrySet()) {
            UUID vaultOwner = entry.getValue();
            boolean resolved;
            try {
                resolved = Boolean.TRUE.equals(bankRepo.operationJournal().withOperationLock(entry.getKey(),
                        () -> reconcileBankUnlockRequest(
                                new BankOperationScope(playerUuid, vaultOwner, true), bankRepo, entry.getKey())));
            } catch (RuntimeException failure) {
                resolved = false;
            }
            if (!resolved) unresolved++;
        }
        return unresolved;
    }

    /**
     * Journal subject binding a bank request to actor and vault. Self-vault
     * operations keep the historical plain {@code playerUuid} format so legacy
     * journal records remain replayable; only member operations on a shared
     * vault carry the compound {@code actor|vaultOwner} binding.
     */
    private static String bankOperationSubject(BankOperationScope scope) {
        return scope.vaultOwnerUuid().equals(scope.actorUuid())
                ? scope.actorUuid().toString()
                : scope.actorUuid() + "|" + scope.vaultOwnerUuid();
    }

    /**
     * Extracts the durable vault owner from a compound journal subject
     * ({@code actor|vaultOwner|...}). Legacy subjects — plain
     * {@code playerUuid} or {@code playerUuid|tab|slot|...} — resolve to the
     * scanning player, matching the historical self-vault semantics.
     */
    private static UUID vaultOwnerFromSubject(String subject, UUID fallback) {
        if (subject == null) return fallback;
        int first = subject.indexOf('|');
        if (first < 0) return fallback;
        int second = subject.indexOf('|', first + 1);
        String ownerPart = second < 0 ? subject.substring(first + 1) : subject.substring(first + 1, second);
        try {
            return UUID.fromString(ownerPart);
        } catch (IllegalArgumentException notUuid) {
            return fallback; // legacy tab/slot segment — owner is the actor
        }
    }

    /**
     * Recovery entry for one tab-unlock request: a still-prepared record is
     * reconciled against the vault marker; anything else (including a committed
     * record whose marker cleanup was interrupted) runs the payment finalize.
     */
    private boolean reconcileBankUnlockRequest(
            BankOperationScope scope,
            com.storynpcs.persistence.BankRepository bankRepo,
            UUID requestId) {
        try {
            var record = bankRepo.operationJournal().read(requestId);
            if (record != null
                    && record.state() == com.storynpcs.persistence.DurableOperationJournal.State.PREPARED
                    && "bank.unlock_tab".equals(record.operationType())) {
                return reconcilePendingBankUnlock(scope, bankRepo, requestId);
            }
            return finalizeCommittedBankUnlock(scope, bankRepo, requestId, true);
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
        try {
            tradeOperationJournal.pruneTerminalRecords();
        } catch (IOException | RuntimeException ignored) {
            // Housekeeping must never block recovery.
        }
        java.util.List<com.storynpcs.persistence.DurableOperationJournal.OperationRecord> pending;
        try {
            String subjectPrefix = playerUuid + "|";
            var journalScan = tradeOperationJournal.pending();
            for (String diagnostic : journalScan.diagnostics()) {
                System.err.println("[StoryNPCs] trade operation journal recovery for "
                        + playerUuid + ": " + diagnostic);
            }
            pending = journalScan.records().stream()
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
     * Login-time reconciliation of durable quest-completion intents left by a crash
     * or failed write. A pending intent means an auto-completion or explicit
     * completion was in flight when the process stopped. If it is still eligible
     * (same quest state revision, objectives still met), resuming re-runs the
     * mark-before-deliver protocol: every leg marked durably before delivery, so
     * recovery delivers only legs with no mark and commits the terminal state once.
     * An intent that is no longer eligible (quest removed, state moved on, player
     * withdrew progress) stays pending and is reported for manual recovery rather
     * than guessed. An intent on a quest that is already COMPLETED is dead weight
     * and is dropped durably.
     *
     * @return the number of intents still requiring manual recovery or re-turn-in
     */
    public int recoverQuestCompletions(UUID playerUuid) {
        if (playerUuid == null) return 0;
        PlayerProgression progression;
        try {
            progression = progressionRepository.getOrCreate(playerUuid);
        } catch (RuntimeException unavailable) {
            return 1; // durable state unreadable — cannot prove recovery ran
        }
        int unresolved = 0;
        List<StoryNpcsEvent> notifications = new ArrayList<>();
        Object playerLock = progressionMutationLocks[playerUuid.hashCode()
                & (progressionMutationLocks.length - 1)];
        QuestEventQueue queue;
        synchronized (playerLock) {
            synchronized (progression) {
                for (Map.Entry<NamespacedId, PendingQuestCompletion> entry
                        : List.copyOf(progression.getPendingQuestCompletions().entrySet())) {
                    NamespacedId questId = entry.getKey();
                    PendingQuestCompletion pending = entry.getValue();
                    QuestProgressState state = progression.getQuests().get(questId);
                    if (state != null && state.getStatus() == QuestProgressState.Status.COMPLETED) {
                        progression.getPendingQuestCompletions().remove(questId);
                        try {
                            progressionRepository.save(playerUuid, progression);
                        } catch (Exception e) {
                            unresolved++;
                        }
                        continue;
                    }
                    Quest quest = registry.getQuest(questId).orElse(null);
                    if (!isPendingQuestCompletionEligible(quest, state, pending)) {
                        unresolved++; // in-flight completion can no longer be proven eligible
                        continue;
                    }
                    List<FactionReputationChangeEvent> factionEvents = new ArrayList<>();
                    QuestCompletionResult resumed = completeQuestUnderLock(
                            playerUuid, quest, progression, factionEvents,
                            pending.requestId(), pending.payloadFingerprint());
                    if (resumed.outcome() == QuestCompletionResult.Outcome.COMPLETED) {
                        notifications.addAll(factionEvents);
                        notifications.add(new QuestCompleteEvent(playerUuid, questId));
                    } else if (resumed.outcome() != QuestCompletionResult.Outcome.ALREADY_COMPLETED) {
                        unresolved++;
                    }
                }
            }
            queue = enqueueQuestEvents(playerUuid, notifications);
        }
        if (queue != null) drainQuestEvents(queue);
        return unresolved;
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

    /**
     * Replay-aware compatibility delegate used by request-id-carrying adapters;
     * routes through the typed canonical request boundary.
     */
    public boolean unlockBankTab(UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo,
                                 com.storynpcs.domain.role.banker.BankerRole banker, UUID requestId) {
        if (playerUuid == null || banker == null || requestId == null) return false;
        try {
            return unlockBankTab(new BankOperationRequest("player", playerUuid, playerUuid, null,
                    BankOperationRequest.Action.UNLOCK_TAB, 0, -1, null, 0,
                    requestId, -1), bankRepo, banker).applied();
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /**
     * Typed canonical bank-tab unlock: authorizes the actor/subject/capability
     * envelope before the journaled vault+payment mutation runs. Every attempt
     * on a banker-scoped request publishes a canonical mutation event.
     */
    public CanonicalMutationResult unlockBankTab(BankOperationRequest request,
            com.storynpcs.persistence.BankRepository bankRepo,
            com.storynpcs.domain.role.banker.BankerRole banker) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(banker, "banker");
        AuthorizationDecision authorization = AuthorizationPolicy.evaluate(request);
        if (!authorization.allowed()) {
            publishBankEvent(request, false, "REJECTED_AUTHORIZATION");
            ValidationResult denied = ValidationResult.valid();
            denied.addError(authorization.code(), authorization.message());
            return new CanonicalMutationResult(false, false, 0L, denied,
                    List.of(request.operation() + ":authorization-denied"), "REJECTED_AUTHORIZATION");
        }
        if (request.action() != BankOperationRequest.Action.UNLOCK_TAB) {
            publishBankEvent(request, false, "REJECTED_NO_SIDE_EFFECTS");
            ValidationResult mismatch = ValidationResult.valid();
            mismatch.addError("ACTION_ROUTE_MISMATCH", "Bank action is not an unlock: " + request.action());
            return new CanonicalMutationResult(false, false, 0L, mismatch,
                    List.of(request.operation() + ":route-mismatch"), "REJECTED_NO_SIDE_EFFECTS");
        }
        String accessDeny = bankRepo != null ? bankVaultAccessDenyCode(request, bankRepo) : null;
        boolean applied = accessDeny == null && request.requestId() != null && bankRepo != null
                && unlockBankTabInternal(scopeFor(request), bankRepo, banker, request.requestId());
        CanonicalMutationResult result;
        if (applied) {
            result = new CanonicalMutationResult(true, false, 0L, ValidationResult.valid(),
                    List.of("BankTransactionEvent"), "COMMITTED");
        } else {
            ValidationResult failure = ValidationResult.valid();
            failure.addError(bankRepo == null ? "BANK_UNAVAILABLE" : "UNLOCK_NOT_APPLIED",
                    "Tab unlock rejected, unaffordable, maxed, or left pending for recovery");
            result = new CanonicalMutationResult(false, false, 0L, failure,
                    List.of(request.operation() + ":rejected"), "REJECTED_NO_SIDE_EFFECTS");
        }
        publishBankEvent(request, result.applied(), result.recoveryOutcome());
        return result;
    }

    private boolean unlockBankTabInternal(BankOperationScope scope, com.storynpcs.persistence.BankRepository bankRepo,
                                 com.storynpcs.domain.role.banker.BankerRole banker, UUID requestId) {
        if (scope == null || bankRepo == null || banker == null || requestId == null) return false;
        var journal = bankRepo.operationJournal();
        return Boolean.TRUE.equals(journal.withOperationLock(requestId,
                () -> unlockBankTabJournaled(scope, bankRepo, banker, requestId, journal)));
    }

    /**
     * Canonical vault-sharing mutation: configures a vault's access policy and
     * member list inside one durable transaction guarded by the expected vault
     * revision. This is the only production path that can change
     * {@link com.storynpcs.domain.role.banker.BankVault.AccessPolicy} or the
     * shared member set — GUI and packet adapters never reach it.
     */
    public CanonicalMutationResult configureBankAccess(
            BankAccessMutationRequest request,
            com.storynpcs.persistence.BankRepository bankRepo) {
        Objects.requireNonNull(request, "request");
        AuthorizationDecision authorization = AuthorizationPolicy.evaluate(request);
        if (!authorization.allowed()) {
            ValidationResult denied = ValidationResult.valid();
            denied.addError(authorization.code(), authorization.message());
            eventPublisher.publish(new CanonicalMutationEvent(request.operation(), request.actorType(),
                    null, request.requestId(), false, 0L, "REJECTED_AUTHORIZATION",
                    request.actorId(), request.vaultOwnerUuid()));
            return new CanonicalMutationResult(false, false, 0L, denied,
                    List.of(request.operation() + ":authorization-denied"), "REJECTED_AUTHORIZATION");
        }
        if (bankRepo == null) {
            ValidationResult unavailable = ValidationResult.valid();
            unavailable.addError("BANK_UNAVAILABLE", "Bank repository is unavailable");
            return new CanonicalMutationResult(false, false, 0L, unavailable,
                    List.of(request.operation() + ":rejected"), "REJECTED_NO_SIDE_EFFECTS");
        }
        var result = bankRepo.transact(request.vaultOwnerUuid(), vault -> {
            if (vault.getRevision() != request.expectedVaultRevision()) {
                return com.storynpcs.persistence.BankRepository.BankMutation.unchanged("STALE_VAULT");
            }
            vault.setAccessPolicy(request.accessPolicy());
            vault.setSharedMemberUuids(request.memberUuids());
            return com.storynpcs.persistence.BankRepository.BankMutation.changed("APPLIED");
        });
        if (result.committed()) {
            long revision = bankRepo.getOrCreate(request.vaultOwnerUuid()).getRevision();
            eventPublisher.publish(new CanonicalMutationEvent(request.operation(), request.actorType(),
                    null, request.requestId(), true, revision, "COMMITTED",
                    request.actorId(), request.vaultOwnerUuid()));
            return new CanonicalMutationResult(true, false, revision, ValidationResult.valid(),
                    List.of("BankAccessChangeEvent"), "COMMITTED");
        }
        boolean stale = "STALE_VAULT".equals(result.value());
        ValidationResult failure = ValidationResult.valid();
        failure.addError(stale ? "STALE_VAULT" : "DURABLE_COMMIT_FAILED",
                stale ? "Vault revision moved past the expected revision — re-read and retry"
                        : "Vault sharing update could not be committed: " + result.failureReason());
        eventPublisher.publish(new CanonicalMutationEvent(request.operation(), request.actorType(),
                null, request.requestId(), false, 0L, stale ? "STALE_VAULT" : "DURABLE_COMMIT_FAILED",
                request.actorId(), request.vaultOwnerUuid()));
        return new CanonicalMutationResult(false, false, 0L, failure,
                List.of(request.operation() + ":rejected"), stale ? "STALE_VAULT" : "DURABLE_COMMIT_FAILED");
    }

    /** Canonical audit event for banker-scoped typed requests; compat envelopes carry no banker id. */
    private void publishBankEvent(BankOperationRequest request, boolean applied, String outcome) {
        if (request.bankerId() == null) return;
        eventPublisher.publish(new CanonicalMutationEvent(request.operation(), request.actorType(),
                request.bankerId(), request.requestId(), applied, 0L, outcome,
                request.actorId(), request.playerUuid()));
    }

    private boolean unlockBankTabJournaled(
            BankOperationScope scope,
            com.storynpcs.persistence.BankRepository bankRepo,
            com.storynpcs.domain.role.banker.BankerRole banker,
            UUID requestId,
            com.storynpcs.persistence.DurableOperationJournal journal) {
        final UUID playerUuid = scope.actorUuid();
        final UUID vaultOwnerUuid = scope.vaultOwnerUuid();
        final String operationType = "bank.unlock_tab";
        // Subject binds actor and vault — a request id cannot be replayed
        // across a different vault or on behalf of a different member.
        final String subject = bankOperationSubject(scope);
        try {
            var existing = journal.read(requestId);
            if (existing != null) {
                if (!subject.equals(existing.subject())) {
                    return false; // request id is bound to a different subject — fail closed
                }
                var classification = journal.begin(requestId, operationType, subject);
                return switch (classification.status()) {
                    case COMMITTED -> finalizeCommittedBankUnlock(scope, bankRepo, requestId, true);
                    case ABORTED -> false;
                    case PENDING, STARTED -> reconcilePendingBankUnlock(scope, bankRepo, requestId);
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
            var vault = bankRepo.getOrCreate(vaultOwnerUuid);
            if (!scope.mayAccess(vault)) return false;
            unlocked = vault.getUnlockedTabs();
            vaultRevision = vault.getRevision();
        } catch (RuntimeException unavailable) {
            return false;
        }
        if (unlocked >= Math.max(1, banker.getMaxTabs())) return false;
        cost = Math.max(0, banker.getTabUpgradeCost());
        if (cost > 0) {
            if (minecraftServer == null) return false;
            // The acting member pays for the tab — never the vault owner.
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
                    case COMMITTED -> finalizeCommittedBankUnlock(scope, bankRepo, requestId, true);
                    case ABORTED -> false;
                    default -> reconcilePendingBankUnlock(scope, bankRepo, requestId);
                };
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }

        // Vault leg: unlock + durable operation marker in ONE atomic commit.
        var vaultResult = bankRepo.transact(vaultOwnerUuid, candidate -> {
            if (!scope.mayAccess(candidate)) {
                return com.storynpcs.persistence.BankRepository.BankMutation.unchanged(false);
            }
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
        return finalizeCommittedBankUnlock(scope, bankRepo, requestId, false);
    }

    /**
     * Reconciles a prepared unlock after a crash: no vault marker means the
     * vault commit never landed (abort — nothing was paid); a marker means the
     * vault leg is durable and only the payment leg may still be owed.
     */
    private boolean reconcilePendingBankUnlock(
            BankOperationScope scope,
            com.storynpcs.persistence.BankRepository bankRepo,
            UUID requestId) {
        try {
            var marker = bankRepo.getOrCreate(scope.vaultOwnerUuid()).getOperationMarker(requestId);
            if (marker == null) {
                bankRepo.operationJournal().abort(requestId, "BANK_NOT_COMMITTED",
                        "vault unlock marker was not durable");
                return true;
            }
            bankRepo.operationJournal().commit(requestId, "APPLIED",
                    Integer.toString(bankRepo.getOrCreate(scope.vaultOwnerUuid()).getUnlockedTabs()));
            return finalizeCommittedBankUnlock(scope, bankRepo, requestId, true);
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
            BankOperationScope scope,
            com.storynpcs.persistence.BankRepository bankRepo,
            UUID requestId,
            boolean replay) {
        final UUID playerUuid = scope.actorUuid();
        final UUID vaultOwnerUuid = scope.vaultOwnerUuid();
        try {
            var marker = bankRepo.getOrCreate(vaultOwnerUuid).getOperationMarker(requestId);
            if (marker == null) {
                if (!replay) return false;
                // A backup restore can erase a committed unlock together with its
                // marker. Cross-check the vault against the journal's committed
                // outcome before declaring the request resolved — a rolled-back
                // unlock must stay pending, not silently vanish after payment.
                var record = bankRepo.operationJournal().read(requestId);
                if (record != null && record.detail() != null) {
                    int committedTabs = Integer.parseInt(record.detail().trim());
                    if (bankRepo.getOrCreate(vaultOwnerUuid).getUnlockedTabs() < committedTabs) {
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
                return cleanupBankUnlockMarker(vaultOwnerUuid, bankRepo, requestId, replay);
            }
            if (!operation.inventoryDeferred() || operation.count() <= 0) {
                // Free unlock — nothing owed; mark then clean. The event is
                // published once by the finalizer that observed the unapplied
                // marker; replays that find no marker return early.
                var marked = bankRepo.transact(vaultOwnerUuid, vault ->
                        vault.markOperationInventoryApplied(requestId)
                                ? com.storynpcs.persistence.BankRepository.BankMutation.changed(true)
                                : com.storynpcs.persistence.BankRepository.BankMutation.unchanged(false));
                if (!marked.committed()) return false;
                if (!cleanupBankUnlockMarker(vaultOwnerUuid, bankRepo, requestId, replay)) {
                    return false;
                }
                eventPublisher.publish(new com.storynpcs.api.event.BankTransactionEvent(
                        vaultOwnerUuid, com.storynpcs.api.event.BankTransactionEvent.Type.UNLOCK_TAB,
                        operation.tab() + 1, "minecraft:emerald", operation.count()));
                return true;
            }
            if (minecraftServer == null) return false;
            // The acting member pays for the tab — never the vault owner.
            var player = minecraftServer.getPlayerList().getPlayer(playerUuid);
            if (player == null) return false;
            int held = countHeldEmeralds(player);
            if (held != operation.expectedCount() + operation.count()) {
                return false; // inventory drifted — cannot prove payment was not taken already
            }
            deductEmeralds(player, operation.count());
            var markedApplied = bankRepo.transact(vaultOwnerUuid, vault ->
                    vault.markOperationInventoryApplied(requestId)
                            ? com.storynpcs.persistence.BankRepository.BankMutation.changed(true)
                            : com.storynpcs.persistence.BankRepository.BankMutation.unchanged(false));
            if (!markedApplied.committed()) {
                // Payment was taken but the durable proof failed — refund immediately.
                refundEmeralds(player, operation.count());
                return false;
            }
            if (!cleanupBankUnlockMarker(vaultOwnerUuid, bankRepo, requestId, replay)) {
                return false;
            }
            eventPublisher.publish(new com.storynpcs.api.event.BankTransactionEvent(
                    vaultOwnerUuid, com.storynpcs.api.event.BankTransactionEvent.Type.UNLOCK_TAB,
                    operation.tab() + 1, "minecraft:emerald", operation.count()));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private boolean cleanupBankUnlockMarker(
            UUID vaultOwnerUuid,
            com.storynpcs.persistence.BankRepository bankRepo,
            UUID requestId,
            boolean replay) {
        var cleanup = bankRepo.transact(vaultOwnerUuid, vault ->
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

    /**
     * What the charging actor should do after a companion-wage evaluation.
     * The wage ledger itself is entity-owned durable state; this service is the
     * canonical economy path (emerald payment) it charges through.
     */
    public enum CompanionWageOutcome {
        /** A wage was charged for the current period. */
        CHARGED,
        /** The current period was already charged — idempotent. */
        ALREADY_CHARGED,
        /** No wage configured or interval not yet elapsed. */
        NOT_DUE,
        /** Owner is offline and the profile's unload policy pauses service. */
        OWNER_OFFLINE_PAUSED,
        /** Owner is offline and the profile demands despawn — caller should discard. */
        OWNER_OFFLINE_DESPAWN,
        /** Owner could not pay; PAUSE_SERVICE policy — caller pauses companion behavior. */
        INSUFFICIENT_PAUSED,
        /** Owner could not pay; DISMISS policy — caller releases ownership. */
        INSUFFICIENT_DISMISSED,
        /** Owner could not pay; KEEP_ANYWAY policy — period consumed, service continues. */
        INSUFFICIENT_KEPT,
        /** The owner's durable progression record is unreadable (quarantined/corrupt) — fail closed rather than risk an unverifiable charge; caller pauses companion behavior. */
        PROGRESSION_UNAVAILABLE
    }

    /**
     * Canonical companion-wage charge for one entity tick. Exactly-once per
     * wage period through the entity-owned {@link WageLedger}; emeralds are the
     * wage currency. The entity calls this on its own tick — the check is cheap
     * and side effects only run when a new period is due.
     */
    public CompanionWageOutcome chargeCompanionWage(
            UUID ownerUuid, UUID companionId,
            com.storynpcs.domain.companion.CompanionProfile profile,
            com.storynpcs.domain.companion.WageLedger ledger,
            long hireTick, long nowTick) {
        if (profile == null || ledger == null || ownerUuid == null || companionId == null
                || hireTick < 0) {
            return CompanionWageOutcome.NOT_DUE;
        }
        int interval = Math.max(20, profile.getWageIntervalTicks());
        long period = com.storynpcs.domain.companion.WageLedger.periodFor(hireTick, nowTick, interval);
        if (period <= ledger.getLastChargedPeriod()) {
            return CompanionWageOutcome.ALREADY_CHARGED;
        }
        // Durable backstop (issue #57): the entity ledger only persists on
        // periodic NBT saves, so a crash between a deduction and that save
        // loses the marker. The owner-progression record is force-written on
        // every successful charge — consult it before re-charging the period.
        // isUnavailable short-circuits first: a blocked record must not redo
        // the quarantine scan + diagnostic on every wage tick.
        if (progressionRepository.isUnavailable(ownerUuid)) {
            return CompanionWageOutcome.PROGRESSION_UNAVAILABLE;
        }
        PlayerProgression ownerProgression;
        try {
            ownerProgression = progressionRepository.getOrCreate(ownerUuid);
        } catch (RuntimeException unreadable) {
            // Quarantined/corrupt owner data: the wage period cannot be
            // verified or durably marked, so the charge must not run.
            System.err.println("[StoryNPCs] companion wage for " + companionId
                    + " blocked — owner progression unreadable: " + unreadable.getMessage());
            return CompanionWageOutcome.PROGRESSION_UNAVAILABLE;
        }
        long durablePeriod = ownerProgression.chargedWagePeriod(companionId);
        if (durablePeriod >= period) {
            ledger.setLastChargedPeriod(Math.max(ledger.getLastChargedPeriod(), durablePeriod));
            return CompanionWageOutcome.ALREADY_CHARGED;
        }
        var player = minecraftServer != null ? minecraftServer.getPlayerList().getPlayer(ownerUuid) : null;
        if (player == null) {
            return switch (profile.getUnloadPolicy()) {
                case PAUSE -> CompanionWageOutcome.OWNER_OFFLINE_PAUSED;
                case DESPAWN -> CompanionWageOutcome.OWNER_OFFLINE_DESPAWN;
                case FOLLOW_OWNER_OFFLINE -> CompanionWageOutcome.OWNER_OFFLINE_PAUSED;
            };
        }
        var outcome = ledger.charge(period, profile.getWageAmount(),
                (compId, amount) -> {
                    if (countHeldEmeralds(player) < amount) return false;
                    deductEmeralds(player, amount);
                    return true;
                }, companionId);
        switch (outcome) {
            case CHARGED -> {
                // The deduction already ran inside ledger.charge — persist the
                // consumed period in owner progression immediately so a crash
                // before the next entity NBT save cannot re-charge it. Free
                // periods (wageAmount <= 0) move no value, so they skip the
                // forced save — re-firing one after a restart is harmless.
                if (profile.getWageAmount() > 0) {
                    ownerProgression.recordCompanionWagePeriod(companionId, period);
                    try {
                        progressionRepository.save(ownerUuid, ownerProgression);
                    } catch (Exception saveFailure) {
                        // The entity ledger still carries the in-memory marker;
                        // only a crash before BOTH this save and the next entity
                        // save can double-charge — a documented residual window.
                        System.err.println("[StoryNPCs] companion wage period " + period
                                + " for " + companionId + " could not be durably marked: "
                                + saveFailure.getMessage());
                    }
                }
                return CompanionWageOutcome.CHARGED;
            }
            case ALREADY_CHARGED -> { return CompanionWageOutcome.ALREADY_CHARGED; }
            default -> {
                return switch (profile.getInsufficientFundsPolicy()) {
                    case PAUSE_SERVICE -> CompanionWageOutcome.INSUFFICIENT_PAUSED;
                    case DISMISS -> CompanionWageOutcome.INSUFFICIENT_DISMISSED;
                    case KEEP_ANYWAY -> {
                        // Policy keeps the companion and consumes the period —
                        // it must never be retried into a charge, so the
                        // consumption is durable (entity NBT alone could lose
                        // it across a crash and re-charge the "free" period
                        // once the owner is funded again).
                        ledger.setLastChargedPeriod(period);
                        if (profile.getWageAmount() > 0) {
                            ownerProgression.recordCompanionWagePeriod(companionId, period);
                            try {
                                progressionRepository.save(ownerUuid, ownerProgression);
                            } catch (Exception saveFailure) {
                                System.err.println("[StoryNPCs] companion wage period " + period
                                        + " for " + companionId + " could not be durably marked: "
                                        + saveFailure.getMessage());
                            }
                        }
                        yield CompanionWageOutcome.INSUFFICIENT_KEPT;
                    }
                };
            }
        }
    }

    /** Result of an evaluated transport request for command feedback. */
    public record TransportResult(
            boolean approved,
            String destinationName,
            int feeCharged,
            String detail) {
        static TransportResult rejected(
                com.storynpcs.domain.transport.TransportEvaluator.RejectReason reason, String detail) {
            return new TransportResult(false, null, 0, reason + ": " + detail);
        }
    }

    /**
     * Authorization-checked transport request (issue #54 — adapter coverage):
     * a self-scoped {@code command}/{@code player}/{@code dialogue} actor may
     * transport only themselves; transporting another player requires operator
     * level 2 proof, matching the canonical player-scoped policy.
     */
    public TransportResult requestTransport(
            PlayerProgressionActionRequest request, NamespacedId locationId) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(locationId, "locationId");
        Object requestLock = progressionActionLocks[request.requestId().hashCode()
                & (progressionActionLocks.length - 1)];
        TransportResult result = null;
        String outcome = null;
        synchronized (requestLock) {
            // Durable record first: a replayed request id is classified from the
            // journal without re-evaluating authorization (privilege changes only
            // affect new request ids — denied attempts are never journaled).
            com.storynpcs.persistence.DurableOperationJournal.OperationRecord existing = null;
            boolean journalFailed = false;
            try {
                existing = transportOperationJournal.read(request.requestId());
            } catch (IOException | RuntimeException journalFailure) {
                journalFailed = true;
            }
            if (journalFailed) {
                // A transport request without a readable record cannot be
                // replay-classified — refuse rather than risk a duplicate fee.
                result = new TransportResult(false, null, 0, "JOURNAL_UNAVAILABLE");
                outcome = "JOURNAL_UNAVAILABLE";
            } else if (existing != null) {
                if (!"transport.request".equals(request.operation())) {
                    result = new TransportResult(false, null, 0,
                            "OPERATION_MISMATCH: Request operation '" + request.operation()
                                    + "' does not match transport.request");
                    outcome = "OPERATION_MISMATCH";
                } else if (!"transport.request".equals(existing.operationType())) {
                    result = new TransportResult(false, null, 0,
                            "REQUEST_PAYLOAD_MISMATCH: Request ID is bound to operation '"
                                    + existing.operationType() + "'");
                    outcome = "REQUEST_PAYLOAD_MISMATCH";
                } else if (!isTransportSubject(existing, request.playerUuid(), locationId)) {
                    result = new TransportResult(false, null, 0,
                            "REQUEST_PAYLOAD_MISMATCH: Request ID is already bound to a different transport request");
                    outcome = "REQUEST_PAYLOAD_MISMATCH";
                } else if (existing.state()
                        == com.storynpcs.persistence.DurableOperationJournal.State.PREPARED) {
                    // A durable PREPARED means the fee/teleport leg may or may
                    // not have run before a crash — never re-execute live side
                    // effects on a guess.
                    result = new TransportResult(false, null, 0, "RECOVERY_REQUIRED");
                    outcome = "RECOVERY_REQUIRED";
                } else {
                    try {
                        result = decodeTransportResult(existing);
                        outcome = "REPLAYED";
                    } catch (RuntimeException corruptRecord) {
                        // A readable-but-malformed terminal record is ambiguous —
                        // never re-execute live side effects on a guess.
                        result = new TransportResult(false, null, 0, "RECOVERY_REQUIRED");
                        outcome = "RECOVERY_REQUIRED";
                    }
                }
            } else {
                AuthorizationDecision decision = AuthorizationPolicy.evaluate(request);
                if (decision.allowed() && !"transport.request".equals(request.operation())) {
                    decision = AuthorizationDecision.deny("OPERATION_MISMATCH",
                            "Request operation '" + request.operation()
                                    + "' does not match transport.request");
                }
                if (!decision.allowed()) {
                    result = new TransportResult(false, null, 0,
                            decision.code() + ": " + decision.message());
                    outcome = decision.code();
                } else {
                    try {
                        var began = transportOperationJournal.begin(request.requestId(),
                                "transport.request", request.playerUuid() + "|" + locationId);
                        switch (began.status()) {
                            case STARTED -> {
                                result = requestTransport(request.playerUuid(), locationId);
                                outcome = result.approved() ? "COMMITTED"
                                        : result.detail() == null ? "REJECTED_NO_SIDE_EFFECTS"
                                        : result.detail().split(":", 2)[0];
                                finalizeTransportRequest(request.requestId(), result);
                            }
                            case PENDING -> {
                                result = new TransportResult(false, null, 0, "RECOVERY_REQUIRED");
                                outcome = "RECOVERY_REQUIRED";
                            }
                            default -> {
                                try {
                                    result = decodeTransportResult(began.record());
                                    outcome = "REPLAYED";
                                } catch (RuntimeException corruptRecord) {
                                    result = new TransportResult(false, null, 0, "RECOVERY_REQUIRED");
                                    outcome = "RECOVERY_REQUIRED";
                                }
                            }
                        }
                    } catch (com.storynpcs.persistence.DurableOperationJournal
                                     .OperationIdentityMismatchException mismatch) {
                        result = new TransportResult(false, null, 0,
                                "REQUEST_PAYLOAD_MISMATCH: Request ID is already bound to a different transport request");
                        outcome = "REQUEST_PAYLOAD_MISMATCH";
                    } catch (IOException | RuntimeException journalFailure) {
                        result = new TransportResult(false, null, 0,
                                "JOURNAL_UNAVAILABLE: " + journalFailure.getMessage());
                        outcome = "JOURNAL_UNAVAILABLE";
                    }
                }
            }
        }
        if (result == null || outcome == null) {
            throw new IllegalStateException("transport request produced no outcome");
        }
        dispatchQuestEvents(request.playerUuid(), List.of(new CanonicalMutationEvent(
                request.operation(), request.actorType(), locationId, request.requestId(),
                result.approved(), 0L, outcome, request.actorId(), request.playerUuid())));
        return result;
    }

    /**
     * Terminals the durable transport record: approved requests commit so a
     * post-restart replay returns the stored result; every rejected or
     * compensated outcome aborts the record (no durable effect persists). If
     * the journal write itself fails the record stays PREPARED and a later
     * replay fails closed with {@code RECOVERY_REQUIRED} rather than
     * re-executing live side effects.
     */
    private void finalizeTransportRequest(UUID requestId, TransportResult result) {
        String encoded = encodeTransportResult(result);
        try {
            if (result.approved()) {
                transportOperationJournal.commit(requestId, "APPLIED", encoded);
            } else {
                transportOperationJournal.abort(requestId,
                        result.detail() == null ? "REJECTED" : result.detail().split(":", 2)[0], encoded);
            }
        } catch (IOException | RuntimeException terminalFailure) {
            System.err.println("[StoryNPCs] transport request " + requestId
                    + " could not be terminally journaled; it will recover fail-closed: "
                    + terminalFailure.getMessage());
        }
    }

    private static boolean isTransportSubject(
            com.storynpcs.persistence.DurableOperationJournal.OperationRecord record,
            UUID playerUuid, NamespacedId locationId) {
        return (playerUuid + "|" + locationId).equals(record.subject());
    }

    private static String encodeTransportResult(TransportResult result) {
        // The record detail is a flat 4-field newline-delimited format —
        // user-authored names/details can carry newlines, which would corrupt
        // the field boundaries on replay, so they are flattened here.
        return result.approved() + "\n"
                + flattenJournalField(result.destinationName()) + "\n"
                + result.feeCharged() + "\n"
                + flattenJournalField(result.detail());
    }

    private static String flattenJournalField(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private static TransportResult decodeTransportResult(
            com.storynpcs.persistence.DurableOperationJournal.OperationRecord record) {
        String detail = record.detail();
        if (detail == null) return new TransportResult(false, null, 0, "RECOVERY_REQUIRED");
        String[] parts = detail.split("\n", -1);
        int fee = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        return new TransportResult(
                Boolean.parseBoolean(parts[0]),
                parts.length > 1 && !parts[1].isEmpty() ? parts[1] : null,
                fee,
                parts.length > 3 && !parts[3].isEmpty() ? parts[3] : null);
    }

    /**
     * Login recovery for transport requests (issue #57 — P2-3): reports
     * operations still PREPARED — the fee/teleport leg cannot be proven either
     * way after a crash, so they are never auto-resolved.
     *
     * @return the number of unresolved prepared transport requests
     */
    public int recoverTransportOperations(UUID playerUuid) {
        if (playerUuid == null) return 0;
        try {
            // Terminal record files cannot accumulate unbounded; pruning bounds
            // replay dedup to the journal's documented retention window, same
            // as the bank and trade journals.
            transportOperationJournal.pruneTerminalRecords();
        } catch (IOException | RuntimeException ignored) {
            // Housekeeping must never block recovery.
        }
        com.storynpcs.persistence.DurableOperationJournal.PendingScan scan;
        try {
            scan = transportOperationJournal.pending();
        } catch (IOException | RuntimeException failure) {
            System.err.println("[StoryNPCs] transport operation journal recovery failed for "
                    + playerUuid + ": " + failure.getMessage());
            return 1;
        }
        for (String diagnostic : scan.diagnostics()) {
            System.err.println("[StoryNPCs] transport operation journal recovery for "
                    + playerUuid + ": " + diagnostic);
        }
        int unresolved = 0;
        String subjectPrefix = playerUuid + "|";
        for (var record : scan.records()) {
            if (record.subject() != null && record.subject().startsWith(subjectPrefix)) {
                unresolved++;
            }
        }
        return unresolved;
    }

    /**
     * Authoritative transport operation (P6-3): evaluates the destination via
     * {@link com.storynpcs.domain.transport.TransportEvaluator} BEFORE any fee
     * is charged, then charges emeralds and teleports the player on the server
     * thread. Cross-dimension transfers use {@code changeDimension} and recover
     * by returning the player to their origin when the target dimension cannot
     * accept them — matching the evaluator's bounded-recovery contract.
     */
    // Internal-only (issue #54): the typed request overload above is the
    // adapter-facing entry point; this performs the authorized mutation.
    /**
     * Pending cross-dimension arrival verifications (P6-3): a successful
     * cross-dimension transfer is re-checked after {@code transferTimeoutTicks};
     * a player not in the target dimension at the deadline is recovered per the
     * location's authored policy. In-memory by design — the world-scoped
     * service is recreated on reload, so a restart inside the window drops the
     * verification (the durable journal still records the request outcome).
     */
    private final java.util.Queue<PendingTransportVerification> pendingTransportVerifications =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private record PendingTransportVerification(
            UUID playerUuid,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> originLevel,
            double originX, double originY, double originZ, float originYaw,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> targetLevel,
            NamespacedId locationId, long deadlineTick,
            com.storynpcs.domain.transport.TransportEvaluator.DimensionPolicy.Recovery recovery) {}

    /** Test seam: number of outstanding cross-dimension arrival verifications. */
    int pendingTransportVerificationCount() {
        return pendingTransportVerifications.size();
    }

    /**
     * Drain expired arrival verifications — called once per server tick from
     * the mod's tick hook. An offline player at the deadline is dropped: there
     * is nobody to relocate, and their durable journal record already holds the
     * request outcome.
     */
    public void tickTransportVerifications(net.minecraft.server.MinecraftServer server) {
        if (server == null || pendingTransportVerifications.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        var it = pendingTransportVerifications.iterator();
        while (it.hasNext()) {
            var v = it.next();
            if (now < v.deadlineTick()) {
                continue;
            }
            it.remove();
            var player = server.getPlayerList().getPlayer(v.playerUuid());
            if (player == null) {
                continue; // offline at deadline — nothing to relocate
            }
            if (player.serverLevel().dimension().equals(v.targetLevel())) {
                continue; // arrived
            }
            System.err.println("[StoryNPCs] cross-dimension transport to '"
                    + v.locationId() + "' for " + v.playerUuid()
                    + " did not arrive within the transfer timeout — recovery "
                    + v.recovery());
            if (v.recovery()
                    == com.storynpcs.domain.transport.TransportEvaluator
                            .DimensionPolicy.Recovery.RETURN_TO_ORIGIN) {
                var origin = server.getLevel(v.originLevel());
                if (origin != null) {
                    player.teleportTo(origin, v.originX(), v.originY(), v.originZ(),
                            java.util.Set.of(), v.originYaw(), player.getXRot());
                }
            }
        }
    }

    TransportResult requestTransport(UUID playerUuid, NamespacedId locationId) {
        if (minecraftServer == null) {
            return new TransportResult(false, null, 0, "SERVER_UNAVAILABLE");
        }
        var player = minecraftServer.getPlayerList().getPlayer(playerUuid);
        if (player == null) {
            return new TransportResult(false, null, 0, "PLAYER_OFFLINE");
        }
        var location = registry.getTransportLocation(locationId).orElse(null);
        var progression = loadProgression(playerUuid);
        boolean conditionsMet = location != null
                && evalConditions(location.getUnlockConditions(), progression, null);
        var unlocked = unlockedTransportLocations(progression);
        var facts = transportFacts(location, player);
        var evaluation = new com.storynpcs.domain.transport.TransportEvaluator()
                .evaluate(location, unlocked, conditionsMet,
                        countHeldEmeralds(player), facts);
        if (evaluation instanceof com.storynpcs.domain.transport.TransportEvaluator.Evaluation.Rejected r) {
            return TransportResult.rejected(r.reason(), r.detail());
        }
        var approved = (com.storynpcs.domain.transport.TransportEvaluator.Evaluation.Approved) evaluation;
        var dest = approved.location();
        var levelRl = net.minecraft.resources.ResourceLocation.tryParse(
                dest.getDimensionId() != null ? dest.getDimensionId().toString() : "minecraft:overworld");
        var targetLevel = minecraftServer.getLevel(
                net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, levelRl));
        if (targetLevel == null) {
            return new TransportResult(false, dest.getName(), 0, "DIMENSION_UNAVAILABLE");
        }
        var policy = dest.getDimensionPolicy();
        // Evaluation passed — charge the fee, then move the player. Teleport
        // failure refunds the fee: the charge and the transfer are one leg.
        // Entity.teleportTo returns boolean — a refused transfer signals false,
        // not an exception — so BOTH outcomes must refund the charged fee.
        var originLevel = player.serverLevel().dimension();
        double ox = player.getX(), oy = player.getY(), oz = player.getZ();
        float originYaw = player.getYRot();
        if (approved.fee() > 0) {
            deductEmeralds(player, approved.fee());
        }
        boolean transferred;
        try {
            transferred = player.teleportTo(targetLevel, dest.getX(), dest.getY(), dest.getZ(),
                    java.util.Set.of(), dest.getYaw(), player.getXRot());
        } catch (RuntimeException teleportFailure) {
            transferred = false;
        }
        if (!transferred) {
            if (approved.fee() > 0) {
                refundEmeralds(player, approved.fee());
            }
            // A refused transfer can still leave the entity relocated
            // (platform partial-move edge); RETURN_TO_ORIGIN restores the
            // recorded pre-transfer position.
            if (policy.recovery()
                    == com.storynpcs.domain.transport.TransportEvaluator
                            .DimensionPolicy.Recovery.RETURN_TO_ORIGIN
                    && !player.serverLevel().dimension().equals(originLevel)) {
                var origin = minecraftServer.getLevel(originLevel);
                if (origin != null) {
                    player.teleportTo(origin, ox, oy, oz,
                            java.util.Set.of(), originYaw, player.getXRot());
                }
            }
            return new TransportResult(false, dest.getName(), 0, "TRANSFER_FAILED");
        }
        if (!originLevel.equals(targetLevel.dimension())) {
            // Cross-dimension success: deadline-verify the arrival so a player
            // bounced out of the target dimension within the timeout window is
            // recovered per the authored policy instead of stranded.
            long deadline = minecraftServer.overworld().getGameTime()
                    + policy.transferTimeoutTicks();
            pendingTransportVerifications.add(new PendingTransportVerification(
                    playerUuid, originLevel, ox, oy, oz, originYaw,
                    targetLevel.dimension(), locationId, deadline, policy.recovery()));
        }
        return new TransportResult(true, dest.getName(), approved.fee(), "transported");
    }

    private com.storynpcs.domain.transport.TransportEvaluator.DestinationFacts transportFacts(
            com.storynpcs.domain.transport.TransportLocation location,
            net.minecraft.server.level.ServerPlayer player) {
        if (location == null || minecraftServer == null || player == null) {
            return new com.storynpcs.domain.transport.TransportEvaluator.DestinationFacts(
                    false, false, false);
        }
        var levelRl = net.minecraft.resources.ResourceLocation.tryParse(
                location.getDimensionId() != null ? location.getDimensionId().toString()
                        : "minecraft:overworld");
        var level = levelRl == null ? null : minecraftServer.getLevel(
                net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, levelRl));
        if (level == null) {
            return new com.storynpcs.domain.transport.TransportEvaluator.DestinationFacts(
                    false, false, false);
        }
        var pos = net.minecraft.core.BlockPos.containing(
                location.getX(), location.getY(), location.getZ());
        var head = pos.above();
        var support = pos.below();
        var border = level.getWorldBorder();
        boolean withinBorder = border.isWithinBounds(pos)
                && border.isWithinBounds(head) && border.isWithinBounds(support);
        boolean loaded = withinBorder && level.hasChunkAt(pos)
                && level.hasChunkAt(head) && level.hasChunkAt(support);
        if (!loaded) {
            return new com.storynpcs.domain.transport.TransportEvaluator.DestinationFacts(
                    false, false, true);
        }
        var feetState = level.getBlockState(pos);
        var headState = level.getBlockState(head);
        var supportState = level.getBlockState(support);
        var landingBox = player.getBoundingBox().move(
                location.getX() - player.getX(),
                location.getY() - player.getY(),
                location.getZ() - player.getZ());
        boolean safe = feetState.getCollisionShape(level, pos).isEmpty()
                && headState.getCollisionShape(level, head).isEmpty()
                && feetState.getFluidState().isEmpty()
                && headState.getFluidState().isEmpty()
                && supportState.isFaceSturdy(level, support, net.minecraft.core.Direction.UP)
                && supportState.getFluidState().isEmpty()
                && !isTransportHazard(feetState)
                && !isTransportHazard(headState)
                && !isTransportHazard(supportState)
                && level.noCollision(player, landingBox);
        return new com.storynpcs.domain.transport.TransportEvaluator.DestinationFacts(
                true, safe, true);
    }

    private static boolean isTransportHazard(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(net.minecraft.world.level.block.Blocks.CACTUS)
                || state.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)
                || state.is(net.minecraft.world.level.block.Blocks.FIRE)
                || state.is(net.minecraft.world.level.block.Blocks.LAVA)
                || state.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                || state.is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE)
                || state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE)
                || state.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)
                || state.is(net.minecraft.world.level.block.Blocks.WITHER_ROSE);
    }

    public java.util.List<com.storynpcs.domain.transport.TransportLocation> listTransports(UUID playerUuid) {
        PlayerProgression progression = playerUuid == null ? null : loadProgression(playerUuid);
        java.util.Set<NamespacedId> unlocked = progression == null
                ? java.util.Set.of() : unlockedTransportLocations(progression);
        return new com.storynpcs.domain.transport.TransportEvaluator()
                .visibleFor(registry.getAllTransportLocations().stream().toList(), unlocked);
    }

    private java.util.Set<NamespacedId> unlockedTransportLocations(PlayerProgression progression) {
        // A location is available when it declares no unlock conditions, when
        // its authored conditions evaluate true for this player, or when an
        // explicit per-player grant was persisted via unlockTransportLocation.
        java.util.Set<NamespacedId> unlocked;
        synchronized (progression) {
            unlocked = new java.util.HashSet<>(progression.getUnlockedTransportLocations());
        }
        for (var location : registry.getAllTransportLocations()) {
            if (location.getUnlockConditions().isEmpty()
                    || evalConditions(location.getUnlockConditions(), progression, null)) {
                unlocked.add(location.getId());
            }
        }
        return java.util.Set.copyOf(unlocked);
    }

    private PlayerProgression loadProgression(UUID playerUuid) {
        try {
            var progression = progressionRepository.getOrCreate(playerUuid);
            return progression != null ? progression : blankProgression(playerUuid);
        } catch (Exception e) {
            return blankProgression(playerUuid);
        }
    }

    private static PlayerProgression blankProgression(UUID playerUuid) {
        var progression = new PlayerProgression();
        progression.setPlayerUuid(playerUuid);
        return progression;
    }

    /**
     * Withdraw a counted amount from a vault slot.
     * Compatibility delegate: routes through the typed canonical request boundary.
     */
    public java.util.Optional<com.storynpcs.domain.role.banker.BankVault.VaultItem> withdrawFromBank(
            UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo, int tab, int slot, int count) {
        if (playerUuid == null) return java.util.Optional.empty();
        try {
            var result = withdrawFromBank(new BankOperationRequest("player", playerUuid, playerUuid, null,
                    BankOperationRequest.Action.WITHDRAW, tab, slot, null, count,
                    UUID.randomUUID(), -1), bankRepo);
            return result.accepted() && result.item() != null
                    ? java.util.Optional.of(result.item()) : java.util.Optional.empty();
        } catch (IllegalArgumentException invalid) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Typed canonical bank withdrawal: authorizes the actor/subject/capability
     * envelope before any vault mutation, then routes {@code WITHDRAW} to the
     * counted-withdrawal transaction and {@code WITHDRAW_STACK} to the journaled
     * remove-and-deliver path. Every attempt on a banker-scoped request
     * publishes a canonical mutation event.
     */
    public BankWithdrawalOperationResult withdrawFromBank(BankOperationRequest request,
            com.storynpcs.persistence.BankRepository bankRepo) {
        Objects.requireNonNull(request, "request");
        AuthorizationDecision authorization = AuthorizationPolicy.evaluate(request);
        if (!authorization.allowed()) {
            publishBankEvent(request, false, "REJECTED_AUTHORIZATION");
            return BankWithdrawalOperationResult.rejected(authorization.code());
        }
        if (bankRepo == null) {
            publishBankEvent(request, false, "REJECTED_NO_SIDE_EFFECTS");
            return BankWithdrawalOperationResult.rejected("BANK_UNAVAILABLE");
        }
        String accessDeny = bankVaultAccessDenyCode(request, bankRepo);
        if (accessDeny != null) {
            publishBankEvent(request, false, accessDeny);
            return BankWithdrawalOperationResult.rejected(accessDeny);
        }
        BankWithdrawalOperationResult result = switch (request.action()) {
            case WITHDRAW -> {
                var item = withdrawFromBankInternal(request, bankRepo,
                        request.tab(), request.slot(), request.amount());
                yield item.isPresent() ? BankWithdrawalOperationResult.applied(item.get())
                        : BankWithdrawalOperationResult.rejected("WITHDRAW_NOT_APPLIED");
            }
            case WITHDRAW_STACK -> withdrawAndDeliverInternal(scopeFor(request), bankRepo,
                    request.tab(), request.slot(), request.requestId());
            case REMOVE_STACK -> withdrawEntireStackFromBank(scopeFor(request), bankRepo,
                    request.tab(), request.slot(), null, null);
            default -> BankWithdrawalOperationResult.rejected("ACTION_ROUTE_MISMATCH");
        };
        publishBankEvent(request, result.accepted(), result.accepted() ? "COMMITTED" : result.code());
        return result;
    }

    private java.util.Optional<com.storynpcs.domain.role.banker.BankVault.VaultItem> withdrawFromBankInternal(
            BankOperationRequest request, com.storynpcs.persistence.BankRepository bankRepo, int tab, int slot, int count) {
        if (bankRepo == null) return java.util.Optional.empty();
        UUID vaultOwner = request.resolvedVaultOwner();
        var result = bankRepo.transact(vaultOwner, vault -> {
            if (!vaultAccessAllowed(request, vault)) {
                return com.storynpcs.persistence.BankRepository.BankMutation.unchanged(
                        java.util.Optional.<com.storynpcs.domain.role.banker.BankVault.VaultItem>empty());
            }
            var itemOpt = vault.withdraw(tab, slot, count);
            return itemOpt.isPresent()
                    ? com.storynpcs.persistence.BankRepository.BankMutation.changed(itemOpt)
                    : com.storynpcs.persistence.BankRepository.BankMutation.unchanged(itemOpt);
        });
        if (!result.committed()) return java.util.Optional.empty();
        var itemOpt = result.value();
        itemOpt.ifPresent(item -> eventPublisher.publish(new com.storynpcs.api.event.BankTransactionEvent(
                vaultOwner, com.storynpcs.api.event.BankTransactionEvent.Type.WITHDRAW,
                tab, item.getItemId(), item.getCount())));
        return itemOpt;
    }

    /**
     * Canonical whole-stack withdrawal used by network/UI adapters. The adapter
     * supplies only the player, tab, and slot; it cannot inspect or choose the
     * amount from the live vault. The service snapshots the current item and
     * removes that exact stack inside the repository transaction.
     */
    /**
     * Vault-only whole-stack removal primitive.
     * Compatibility delegate: routes through the typed canonical request boundary
     * ({@link #withdrawFromBank(BankOperationRequest, com.storynpcs.persistence.BankRepository)}
     * with action {@code REMOVE_STACK}).
     */
    public BankWithdrawalOperationResult withdrawEntireStackFromBank(
            UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo, int tab, int slot) {
        if (playerUuid == null) {
            return BankWithdrawalOperationResult.rejected("INVALID_REQUEST");
        }
        try {
            return withdrawFromBank(new BankOperationRequest("player", playerUuid, playerUuid, null,
                    BankOperationRequest.Action.REMOVE_STACK, tab, slot, null, 0,
                    null, -1), bankRepo);
        } catch (IllegalArgumentException invalid) {
            return BankWithdrawalOperationResult.rejected(
                    tab < 0 ? "INVALID_TAB" : "INVALID_SLOT");
        }
    }

    private BankWithdrawalOperationResult withdrawEntireStackFromBank(
            BankOperationScope scope,
            com.storynpcs.persistence.BankRepository bankRepo,
            int tab,
            int slot,
            Long expectedVaultRevision,
            com.storynpcs.domain.role.banker.BankVault.VaultItem expectedItem) {
        if (scope == null || bankRepo == null) {
            return BankWithdrawalOperationResult.rejected("INVALID_REQUEST");
        }
        if (tab < 0 || slot < 0 || slot >= 54) {
            return BankWithdrawalOperationResult.rejected(
                    tab < 0 ? "INVALID_TAB" : "INVALID_SLOT");
        }
        var result = bankRepo.transact(scope.vaultOwnerUuid(), vault -> {
            if (!scope.mayAccess(vault)) {
                return com.storynpcs.persistence.BankRepository.BankMutation.unchanged(
                        BankWithdrawalOperationResult.rejected("VAULT_ACCESS_DENIED"));
            }
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
     * Compatibility delegate: routes through the typed canonical request boundary
     * ({@link #withdrawFromBank(BankOperationRequest, com.storynpcs.persistence.BankRepository)}
     * with action {@code WITHDRAW_STACK}). Callers without a request id are
     * journaled under a fresh one — the untracked path no longer exists.
     */
    public BankWithdrawalOperationResult withdrawAndDeliverFromBank(
            UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo, int tab, int slot) {
        return withdrawAndDeliverFromBank(playerUuid, bankRepo, tab, slot, null);
    }

    /** Replay-aware compatibility delegate used by adapters carrying a request ID. */
    public BankWithdrawalOperationResult withdrawAndDeliverFromBank(
            UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo,
            int tab, int slot, UUID requestId) {
        if (playerUuid == null) {
            return BankWithdrawalOperationResult.rejected("INVALID_REQUEST");
        }
        try {
            return withdrawFromBank(new BankOperationRequest("player", playerUuid, playerUuid, null,
                    BankOperationRequest.Action.WITHDRAW_STACK, tab, slot, null, 0,
                    requestId, -1), bankRepo);
        } catch (IllegalArgumentException invalid) {
            return BankWithdrawalOperationResult.rejected("INVALID_REQUEST");
        }
    }

    private BankWithdrawalOperationResult withdrawAndDeliverInternal(
            BankOperationScope scope, com.storynpcs.persistence.BankRepository bankRepo,
            int tab, int slot, UUID requestId) {
        if (scope == null || bankRepo == null || tab < 0 || slot < 0 || slot >= 54) {
            return BankWithdrawalOperationResult.rejected("INVALID_REQUEST");
        }
        // Every whole-stack withdrawal is journaled: a missing request id
        // mints a fresh one rather than falling back to the untracked legacy
        // path, so a crash mid-delivery is always recoverable.
        final UUID operationId = requestId != null ? requestId : UUID.randomUUID();
        var journal = bankRepo.operationJournal();
        return journal.withOperationLock(operationId,
                () -> withdrawAndDeliverJournaled(scope, bankRepo, tab, slot, operationId, journal));
    }

    private BankWithdrawalOperationResult withdrawAndDeliverJournaled(
            BankOperationScope scope,
            com.storynpcs.persistence.BankRepository bankRepo,
            int tab,
            int slot,
            UUID requestId,
            com.storynpcs.persistence.DurableOperationJournal journal) {
        final UUID playerUuid = scope.actorUuid();
        // Subject binds actor, vault, tab and slot — a request id cannot be
        // replayed across a different vault or on behalf of a different member.
        String requestPrefix = bankOperationSubject(scope) + "|" + tab + "|" + slot + "|";
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
            vaultSnapshot = bankRepo.getOrCreate(scope.vaultOwnerUuid()).copy();
        } catch (RuntimeException unavailable) {
            return BankWithdrawalOperationResult.rejected("VAULT_UNAVAILABLE");
        }
        if (!scope.mayAccess(vaultSnapshot)) {
            return BankWithdrawalOperationResult.rejected("VAULT_ACCESS_DENIED");
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
        String subject = bankOperationSubject(scope) + "|" + tab + "|" + slot + "|"
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

        BankWithdrawalOperationResult withdrawal = withdrawAndDeliverCore(
                scope, bankRepo, tab, slot, vaultSnapshot.getRevision(), current.get());
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

    /**
     * Withdrawal body executed inside the operation journal by
     * {@link #withdrawAndDeliverJournaled}: removes the stack, materializes it
     * for the acting member, and compensates on delivery failure. Never call
     * outside the journal — every reach is request-id bound so a mid-delivery
     * crash is recoverable.
     */
    private BankWithdrawalOperationResult withdrawAndDeliverCore(
            BankOperationScope scope,
            com.storynpcs.persistence.BankRepository bankRepo,
            int tab,
            int slot,
            Long expectedVaultRevision,
            com.storynpcs.domain.role.banker.BankVault.VaultItem expectedItem) {
        BankWithdrawalOperationResult withdrawal = withdrawEntireStackFromBank(
                scope, bankRepo, tab, slot, expectedVaultRevision, expectedItem);
        if (!withdrawal.accepted() || withdrawal.item() == null) return withdrawal;
        if (minecraftServer == null) {
            eventPublisher.publish(new com.storynpcs.api.event.BankTransactionEvent(
                    scope.vaultOwnerUuid(), com.storynpcs.api.event.BankTransactionEvent.Type.WITHDRAW,
                    tab, withdrawal.item().getItemId(), withdrawal.item().getCount()));
            return withdrawal;
        }
        // Delivery targets the acting member — never the vault owner.
        var player = minecraftServer.getPlayerList().getPlayer(scope.actorUuid());
        if (player != null && materializeVaultItem(player, withdrawal.item())) {
            eventPublisher.publish(new com.storynpcs.api.event.BankTransactionEvent(
                    scope.vaultOwnerUuid(), com.storynpcs.api.event.BankTransactionEvent.Type.WITHDRAW,
                    tab, withdrawal.item().getItemId(), withdrawal.item().getCount()));
            return withdrawal;
        }
        // Compensation returns the stack to the vault it was removed from —
        // bypasses the request boundary (restore is already authorized), but
        // keys on the same vault owner so a shared vault is restored correctly.
        boolean restored = depositToBankInternal(new BankOperationRequest(
                "system", null, scope.vaultOwnerUuid(), null,
                BankOperationRequest.Action.DEPOSIT, tab, slot,
                withdrawal.item().getItemId(), withdrawal.item().getCount(),
                null, -1, scope.vaultOwnerUuid()), bankRepo, tab, slot,
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

    /**
     * Rule/API adapter for faction reputation adjustments. Routes through the
     * canonical {@link #mutateFactionProgression} boundary — same authorization,
     * revision bump, idempotency record, and {@code FactionReputationChangeEvent}
     * publication as every other faction mutation. Throws on rejection
     * ({@link NoSuchElementException} for an unknown faction).
     */
    public void adjustFactionReputation(UUID playerUuid, NamespacedId factionId, int delta) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(factionId, "factionId");
        adjustFactionPoints(playerUuid, factionId, delta);
    }
}

