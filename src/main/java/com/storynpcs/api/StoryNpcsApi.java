package com.storynpcs.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.api.event.StoryNpcsEvent;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.service.CanonicalMutationResult;
import com.storynpcs.service.CapabilityRegistry;
import com.storynpcs.service.MutationRequest;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;

/**
 * Typed public extension API (P9-1).
 *
 * Mutations are granted through server-owned capability sessions: a caller
 * opens a session naming a principal and a set of DEFINITION-policy
 * capabilities; the session yields an opaque actor identity
 * {@code api:<sessionUuid>} that the canonical service enforces through
 * {@code AuthorizationPolicy}. The API never exposes the application service,
 * never hands out mutable domain objects, and never bypasses stale-revision or
 * capability checks.
 *
 * Boundaries that are structural, not configurable:
 * <ul>
 *   <li>Sessions may only hold DEFINITION-policy capabilities — player-scoped
 *       operations (vaults, trades, progression) require a real player subject
 *       and cannot be reached through an API session.</li>
 *   <li>Reads expose only detached views ({@link NpcView},
 *       {@link DefinitionDomain.DefinitionView}).</li>
 *   <li>Every save/delete flows through the canonical mutation envelope —
 *       same diagnostics, same durability, same event publication as any
 *       other adapter.</li>
 * </ul>
 */
public final class StoryNpcsApi {

    private final DefinitionRegistry registry;
    private final StoryNpcsApplicationService service;
    private final EventPublisher events;

    public StoryNpcsApi(DefinitionRegistry registry) {
        this(registry, null, null);
    }

    public StoryNpcsApi(DefinitionRegistry registry, StoryNpcsApplicationService service,
                        EventPublisher events) {
        this.registry = registry;
        this.service = service;
        this.events = events;
    }

    // --- version negotiation -------------------------------------------------

    /** Negotiate API compatibility before any other call. */
    public ApiVersion.Negotiation negotiate(ApiVersion client) {
        return ApiVersion.negotiate(client);
    }

    // --- sessions ------------------------------------------------------------

    /**
     * Open a capability session for {@code principal} over the requested
     * capabilities. Only DEFINITION-policy capabilities may be granted;
     * requesting anything else (or a valid set reduced to nothing) fails.
     */
    public Optional<ApiSessionRegistry.Session> openSession(
            String principal, Collection<String> capabilities) {
        if (service == null || capabilities == null) {
            return Optional.empty();
        }
        var granted = new java.util.LinkedHashSet<String>();
        for (String cap : capabilities) {
            var policy = cap == null ? null : CapabilityRegistry.policyOf(cap);
            if (policy == null) {
                return Optional.empty(); // unknown capability — fail closed
            }
            if (policy != CapabilityRegistry.Policy.DEFINITION) {
                return Optional.empty(); // sessions never hold player-scoped authority
            }
            granted.add(cap.trim().toLowerCase());
        }
        return service.getApiSessions().issue(principal, granted, service.apiSessionTick());
    }

    /** Revoke a session — subsequent mutations deny immediately. */
    public boolean revokeSession(ApiSessionRegistry.Session session) {
        return service != null && session != null
                && service.getApiSessions().revoke(session.sessionId());
    }

    // --- events --------------------------------------------------------------

    /**
     * Subscribe to typed runtime events. The returned handle closes the
     * subscription; listener failures are isolated by the publisher.
     */
    public Optional<AutoCloseable> subscribe(Consumer<StoryNpcsEvent> listener) {
        if (events == null || listener == null) {
            return Optional.empty();
        }
        events.register(listener);
        return Optional.of(() -> events.unregister(listener));
    }

    // --- definition domains --------------------------------------------------

    /** NPC definitions — view access plus canonical create/replace/delete. */
    public DefinitionDomain<NpcDefinition> npcs() {
        return new DefinitionDomain<>(this, "npc",
                registry::getAllNpcs, NpcDefinition::getId, d -> 0,
                service::replaceNpc, service::deleteNpc);
    }

    /** Dialogue graphs — canonical replace/delete. */
    public DefinitionDomain<DialogueGraph> dialogues() {
        return new DefinitionDomain<>(this, "dialogue",
                registry::getAllDialogues, DialogueGraph::getId, d -> 0,
                service::replaceDialogue, service::deleteDialogue);
    }

    /** Quest definitions — canonical replace/delete. */
    public DefinitionDomain<Quest> quests() {
        return new DefinitionDomain<>(this, "quest",
                registry::getAllQuests, Quest::getId, d -> 0,
                service::replaceQuest, service::deleteQuest);
    }

    /** Faction definitions — canonical replace/delete. */
    public DefinitionDomain<Faction> factions() {
        return new DefinitionDomain<>(this, "faction",
                registry::getAllFactions, Faction::getId, d -> 0,
                service::replaceFaction, service::deleteFaction);
    }

    /** Transport locations — canonical create only; no delete op exists (P9-4 scope). */
    public DefinitionDomain<com.storynpcs.domain.transport.TransportLocation> transports() {
        return new DefinitionDomain<>(this, "transport",
                registry::getAllTransportLocations,
                com.storynpcs.domain.transport.TransportLocation::getId, l -> 0,
                service::createTransportLocation,
                req -> unsupported("TRANSPORT_DELETE_UNSUPPORTED",
                        "transport deletion is not exposed canonically (P9-4)"));
    }

    /** NPC templates — canonical save/delete. */
    public DefinitionDomain<com.storynpcs.creator.template.NpcTemplate> templates() {
        return new DefinitionDomain<>(this, "template",
                registry::getAllTemplates,
                com.storynpcs.creator.template.NpcTemplate::getId,
                com.storynpcs.creator.template.NpcTemplate::getSchemaVersion,
                service::saveTemplate, service::deleteTemplate);
    }

    /** Template spawner rules — canonical save/delete. */
    public DefinitionDomain<com.storynpcs.creator.template.SpawnerRule> spawners() {
        return new DefinitionDomain<>(this, "spawner",
                registry::getAllSpawnerRules,
                com.storynpcs.creator.template.SpawnerRule::getId,
                com.storynpcs.creator.template.SpawnerRule::getSchemaVersion,
                service::saveSpawner, service::deleteSpawner);
    }

    /** World-tool definitions — canonical save/delete. */
    public DefinitionDomain<com.storynpcs.creator.world.WorldToolDefinition> worldTools() {
        return new DefinitionDomain<>(this, "worldtool",
                registry::getAllWorldTools,
                com.storynpcs.creator.world.WorldToolDefinition::getId,
                com.storynpcs.creator.world.WorldToolDefinition::getSchemaVersion,
                service::saveWorldTool, service::deleteWorldTool);
    }

    /** Carpentry recipes — canonical save/delete. */
    public DefinitionDomain<com.storynpcs.creator.recipe.CarpentryRecipe> recipes() {
        return new DefinitionDomain<>(this, "recipe",
                registry::getAllRecipes,
                com.storynpcs.creator.recipe.CarpentryRecipe::getId,
                com.storynpcs.creator.recipe.CarpentryRecipe::getSchemaVersion,
                service::saveRecipe, service::deleteRecipe);
    }

    /** Scene definitions — canonical save/delete. */
    public DefinitionDomain<com.storynpcs.creator.scene.SceneDefinition> scenes() {
        return new DefinitionDomain<>(this, "scene",
                registry::getAllScenes,
                com.storynpcs.creator.scene.SceneDefinition::getId,
                com.storynpcs.creator.scene.SceneDefinition::getSchemaVersion,
                service::saveScene, service::deleteScene);
    }

    /** Transform rules — canonical save/delete. */
    public DefinitionDomain<com.storynpcs.creator.transform.TransformRule> transforms() {
        return new DefinitionDomain<>(this, "transform",
                registry::getAllTransforms,
                com.storynpcs.creator.transform.TransformRule::getId,
                com.storynpcs.creator.transform.TransformRule::getSchemaVersion,
                service::saveTransform, service::deleteTransform);
    }

    /** Natural spawn rules — canonical save/delete. */
    public DefinitionDomain<com.storynpcs.creator.spawn.NaturalSpawnRule> naturalSpawns() {
        return new DefinitionDomain<>(this, "naturalspawn",
                registry::getAllNaturalSpawns,
                com.storynpcs.creator.spawn.NaturalSpawnRule::getId,
                com.storynpcs.creator.spawn.NaturalSpawnRule::getSchemaVersion,
                service::saveNaturalSpawn, service::deleteNaturalSpawn);
    }

    /** Custom GUI layouts — canonical save/delete. */
    public DefinitionDomain<com.storynpcs.creator.gui.CustomGuiLayout> guiLayouts() {
        return new DefinitionDomain<>(this, "guilayout",
                registry::getAllGuiLayouts,
                com.storynpcs.creator.gui.CustomGuiLayout::getId,
                com.storynpcs.creator.gui.CustomGuiLayout::getSchemaVersion,
                service::saveGuiLayout, service::deleteGuiLayout);
    }

    /** Model presets — canonical save/delete. */
    public DefinitionDomain<com.storynpcs.creator.gui.ModelPreset> modelPresets() {
        return new DefinitionDomain<>(this, "modelpreset",
                registry::getAllModelPresets,
                com.storynpcs.creator.gui.ModelPreset::getId,
                com.storynpcs.creator.gui.ModelPreset::getSchemaVersion,
                service::saveModelPreset, service::deleteModelPreset);
    }

    private static CanonicalMutationResult unsupported(String code, String message) {
        ValidationResult denied = ValidationResult.valid();
        denied.addError(code, message);
        return new CanonicalMutationResult(false, false, 0L, denied);
    }

    // --- read views ----------------------------------------------------------

    /** Immutable view of an NPC definition — detached snapshot. */
    public Optional<NpcView> npc(NamespacedId id) {
        if (registry == null) {
            return Optional.empty();
        }
        return registry.getNpc(id).map(NpcView::of);
    }

    /** Validation surface — the same diagnostics adapters and UIs see. */
    public ValidationResult diagnostics(NamespacedId definitionId) {
        ValidationResult result = new ValidationResult();
        if (registry == null) {
            result.addError("REGISTRY_UNAVAILABLE", "definition registry is not initialized");
            return result;
        }
        if (registry.getNpc(definitionId).isEmpty()) {
            result.addError("DEFINITION_MISSING", "no npc definition: " + definitionId);
        }
        return result;
    }

    // --- internal ------------------------------------------------------------

    /**
     * Build the canonical mutation envelope for a session mutation — the API
     * fills actor identity and current revision; callers can never supply
     * either. Returns null when the session is not live (defense in depth:
     * the service re-checks the grant regardless).
     */
    MutationRequest mutationRequest(ApiSessionRegistry.Session session, String operation,
                                    String capability, NamespacedId targetId) {
        // The canonical envelope re-checks the live grant against the service
        // clock; here we only require the session still be registered.
        if (service == null || session == null
                || service.getApiSessions().session(session.sessionId()).isEmpty()) {
            return null;
        }
        String kind = capability.contains(".")
                ? capability.substring(0, capability.indexOf('.')) : capability;
        long revision = service.currentRevision(kind, targetId);
        return DefinitionDomain.request(session, operation, capability, targetId, revision);
    }
}
