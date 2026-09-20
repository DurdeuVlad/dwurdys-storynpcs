package com.storynpcs.service;

import com.storynpcs.api.event.*;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.dialogue.*;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.npc.*;
import com.storynpcs.domain.progression.PlayerProgression;
import com.storynpcs.domain.progression.QuestProgressState;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.domain.quest.QuestObjective;
import com.storynpcs.domain.quest.QuestReward;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.yaml.CrossReferenceValidator;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.yaml.YamlDefinitionLoader;
import com.storynpcs.yaml.YamlDefinitionWriter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
    /** May be null in unit-test contexts — all code that uses this must null-check. */
    private final net.minecraft.server.MinecraftServer minecraftServer;
    /** May be null in unit-test contexts — needed for VULN-57 file deletion on deleteNpc. */
    private YamlDefinitionLoader loader;

    public void setLoader(YamlDefinitionLoader loader) {
        this.loader = loader;
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
    }

    // ==========================================
    // 1. NPC Lifecycle Operations
    // ==========================================

    public void createNpc(NpcDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (definition.getId() == null) {
            throw new IllegalArgumentException("NPC definition must have an ID");
        }
        registry.registerNpc(definition);
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
                new YamlDefinitionWriter().writeDefinition(
                        loader.getLastLoadedRootPath(), "npcs",
                        YamlDefinitionWriter.fileNameFor(definition.getId()), definition);
            } catch (IOException e) {
                result.addError("PERSIST_WRITE_FAILED", "Failed to write NPC file: " + e.getMessage());
                return result;
            }
        }

        registry.registerNpc(definition);
        return result;
    }

    public boolean deleteNpc(NamespacedId id) {
        Objects.requireNonNull(id, "id");
        if (registry.getNpc(id).isPresent()) {
            registry.removeNpc(id);
            // VULN-57: also delete the YAML file so the NPC doesn't resurrect on the next reload
            if (loader != null) {
                boolean fileDeleted = loader.deleteDefinitionFile("npc", id);
                if (!fileDeleted) {
                    System.err.println("[StoryNPCs] Warning: NPC '" + id + "' removed from registry but definition file could not be found on disk. It may resurrect on reload.");
                }
            }
            return true;
        }
        return false;
    }

    public void updateNpcDisplay(NamespacedId id, NpcDisplay display) {
        NpcDefinition npc = registry.getNpc(id)
                .orElseThrow(() -> new NoSuchElementException("NPC not found: " + id));
        npc.setDisplay(Objects.requireNonNull(display, "display"));
    }

    public void updateNpcStats(NamespacedId id, NpcStats stats) {
        NpcDefinition npc = registry.getNpc(id)
                .orElseThrow(() -> new NoSuchElementException("NPC not found: " + id));
        npc.setStats(Objects.requireNonNull(stats, "stats"));
    }

    public void updateNpcAi(NamespacedId id, NpcAi ai) {
        NpcDefinition npc = registry.getNpc(id)
                .orElseThrow(() -> new NoSuchElementException("NPC not found: " + id));
        npc.setAi(Objects.requireNonNull(ai, "ai"));
    }

    public void updateNpcInventory(NamespacedId id, List<String> inventory) {
        NpcDefinition npc = registry.getNpc(id)
                .orElseThrow(() -> new NoSuchElementException("NPC not found: " + id));
        npc.setInventory(new ArrayList<>(inventory));
    }

    public void setNpcMark(NamespacedId id, NpcMark mark) {
        NpcDefinition npc = registry.getNpc(id)
                .orElseThrow(() -> new NoSuchElementException("NPC not found: " + id));
        npc.setMark(mark);
    }

    public void clearNpcMark(NamespacedId id) {
        setNpcMark(id, null);
    }

    public ValidationResult assignDialogue(NamespacedId npcId, NamespacedId dialogueId) {
        Objects.requireNonNull(npcId, "npcId");
        NpcDefinition npc = registry.getNpc(npcId)
                .orElseThrow(() -> new NoSuchElementException("NPC not found: " + npcId));
        if (dialogueId != null && registry.getDialogue(dialogueId).isEmpty()) {
            throw new NoSuchElementException("Dialogue not found: " + dialogueId);
        }
        npc.setDialogueId(dialogueId);
        return saveNpc(npc);
    }

    public ValidationResult assignFaction(NamespacedId npcId, NamespacedId factionId) {
        Objects.requireNonNull(npcId, "npcId");
        NpcDefinition npc = registry.getNpc(npcId)
                .orElseThrow(() -> new NoSuchElementException("NPC not found: " + npcId));
        if (factionId != null && registry.getFaction(factionId).isEmpty()) {
            throw new NoSuchElementException("Faction not found: " + factionId);
        }
        npc.setFactionId(factionId);
        return saveNpc(npc);
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
    public ValidationResult saveDialogue(NamespacedId expectedId, DialogueGraph graph) {
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
                new YamlDefinitionWriter().writeDialogue(loader.getLastLoadedRootPath(), graph);
            } catch (IOException e) {
                result.addError("PERSIST_WRITE_FAILED", "Failed to write dialogue file: " + e.getMessage());
                return result;
            }
        }

        registry.registerDialogue(graph);
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
                new YamlDefinitionWriter().writeDefinition(
                        loader.getLastLoadedRootPath(), "quests",
                        YamlDefinitionWriter.fileNameFor(quest.getId()), quest);
            } catch (IOException e) {
                result.addError("PERSIST_WRITE_FAILED", "Failed to write quest file: " + e.getMessage());
                return result;
            }
        }

        registry.registerQuest(quest);
        return result;
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

    /**
     * Persists a faction definition: validates, writes YAML atomically, then registers
     * it live. Canonical mutation path for `/storynpcs faction create/configure` —
     * mirrors {@link #saveQuest}.
     *
     * @return validation result; on errors nothing is written or registered.
     */
    public ValidationResult saveFaction(Faction faction) {
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
                new YamlDefinitionWriter().writeDefinition(
                        loader.getLastLoadedRootPath(), "factions",
                        YamlDefinitionWriter.fileNameFor(faction.getId()), faction);
            } catch (IOException e) {
                result.addError("PERSIST_WRITE_FAILED", "Failed to write faction file: " + e.getMessage());
                return result;
            }
        }

        registry.registerFaction(faction);
        return result;
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

    /**
     * Removes a quest definition from the live registry and removes its YAML file from
     * disk. Mirrors {@link #deleteNpc}. Callers should check
     * {@link #findDialoguesStartingQuest(NamespacedId)} first — deleted quests leave
     * dangling START_QUEST actions in dialogue graphs.
     */
    public boolean deleteQuest(NamespacedId id) {
        Objects.requireNonNull(id, "id");
        if (registry.getQuest(id).isPresent()) {
            registry.removeQuest(id);
            if (loader != null) {
                boolean fileDeleted = loader.deleteDefinitionFile("quest", id);
                if (!fileDeleted) {
                    System.err.println("[StoryNPCs] Warning: Quest '" + id + "' removed from registry but definition file could not be found on disk. It may resurrect on reload.");
                }
            }
            return true;
        }
        return false;
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
    public boolean deleteFaction(NamespacedId id) {
        Objects.requireNonNull(id, "id");
        if (registry.getFaction(id).isPresent()) {
            registry.removeFaction(id);
            if (loader != null) {
                boolean fileDeleted = loader.deleteDefinitionFile("faction", id);
                if (!fileDeleted) {
                    System.err.println("[StoryNPCs] Warning: Faction '" + id + "' removed from registry but definition file could not be found on disk. It may resurrect on reload.");
                }
            }
            return true;
        }
        return false;
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

    /**
     * Removes a dialogue definition from the live registry and removes its YAML file from disk.
     * Mirrors {@link #deleteNpc}.
     */
    public boolean deleteDialogue(NamespacedId id) {
        Objects.requireNonNull(id, "id");
        if (registry.getDialogue(id).isPresent()) {
            registry.removeDialogue(id);
            if (loader != null) {
                boolean fileDeleted = loader.deleteDefinitionFile("dialogue", id);
                if (!fileDeleted) {
                    System.err.println("[StoryNPCs] Warning: Dialogue '" + id + "' removed from registry but definition file could not be found on disk. It may resurrect on reload.");
                }
            }
            return true;
        }
        return false;
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
                        int delta = Integer.parseInt(action.getValue() != null ? action.getValue().trim() : "0");
                        adjustFactionPoints(playerUuid, fid, delta);
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

    // ==========================================
    // 3. Faction Operations
    // ==========================================

    public void setFactionPoints(UUID playerUuid, NamespacedId factionId, int points) {
        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        Faction faction = registry.getFaction(factionId)
                .orElseThrow(() -> new NoSuchElementException("Faction not found: " + factionId));
        int oldPoints = progression.getFactionScore(factionId, faction.getDefaultPoints());
        int clamped = Math.max(-100_000, Math.min(100_000, points));
        progression.setFactionScore(factionId, clamped);
        saveProgression(playerUuid);

        eventPublisher.publish(new FactionReputationChangeEvent(playerUuid, factionId, oldPoints, clamped));
    }

    public void adjustFactionPoints(UUID playerUuid, NamespacedId factionId, int delta) {
        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        Faction faction = registry.getFaction(factionId)
                .orElseThrow(() -> new NoSuchElementException("Faction not found: " + factionId));
        int oldPoints = progression.getFactionScore(factionId, faction.getDefaultPoints());
        long sum = (long) oldPoints + (long) delta;
        int newPoints = (int) Math.max(-100_000, Math.min(100_000, sum));
        progression.setFactionScore(factionId, newPoints);
        saveProgression(playerUuid);

        eventPublisher.publish(new FactionReputationChangeEvent(playerUuid, factionId, oldPoints, newPoints));
    }

    // ==========================================
    // 4. Quest Operations
    // ==========================================

    public void startQuest(UUID playerUuid, NamespacedId questId) {
        Quest quest = registry.getQuest(questId)
                .orElseThrow(() -> new NoSuchElementException("Quest not found: " + questId));

        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        QuestProgressState state = progression.getQuestState(questId);

        // Guard against restarting completed non-repeatable quest (VULN-22)
        if (state.getStatus() == QuestProgressState.Status.COMPLETED && quest.getRepeatType() == Quest.RepeatType.ONCE) {
            return;
        }

        // Check prerequisites
        if (quest.getPrerequisites() != null) {
            for (NamespacedId prereq : quest.getPrerequisites()) {
                if (progression.getQuestState(prereq).getStatus() != QuestProgressState.Status.COMPLETED) {
                    throw new IllegalStateException("Prerequisite quest not completed: " + prereq);
                }
            }
        }

        state.setStatus(QuestProgressState.Status.IN_PROGRESS);
        saveProgression(playerUuid);
        eventPublisher.publish(new QuestStartEvent(playerUuid, questId));
    }

    public void progressQuest(UUID playerUuid, NamespacedId questId, String objectiveId, int amount) {
        Quest quest = registry.getQuest(questId)
                .orElseThrow(() -> new NoSuchElementException("Quest not found: " + questId));

        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        QuestProgressState state = progression.getQuestState(questId);

        if (state.getStatus() != QuestProgressState.Status.IN_PROGRESS) {
            return;
        }

        QuestObjective objective = quest.getObjectives().stream()
                .filter(o -> o.getId().equals(objectiveId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Objective not found: " + objectiveId));

        state.incrementCount(objectiveId, amount);
        saveProgression(playerUuid);
        int currentCount = state.getCount(objectiveId);

        eventPublisher.publish(new QuestObjectiveProgressEvent(
                playerUuid, questId, objectiveId, currentCount, objective.getRequiredCount()));

        // Auto check if all objectives satisfied
        boolean allComplete = quest.getObjectives().stream()
                .allMatch(o -> state.getCount(o.getId()) >= o.getRequiredCount());

        if (allComplete) {
            completeQuest(playerUuid, questId);
        }
    }

    public void completeQuest(UUID playerUuid, NamespacedId questId) {
        Quest quest = registry.getQuest(questId)
                .orElseThrow(() -> new NoSuchElementException("Quest not found: " + questId));

        PlayerProgression progression = progressionRepository.getOrCreate(playerUuid);
        QuestProgressState state = progression.getQuestState(questId);

        // Guard against duplicate completion and infinite rewards exploit (VULN-22)
        if (state.getStatus() == QuestProgressState.Status.COMPLETED) {
            return;
        }

        state.setStatus(QuestProgressState.Status.COMPLETED);
        saveProgression(playerUuid);

        // Deliver rewards — VULN-47: all reward types now implemented
        if (quest.getRewards() != null) {
            for (QuestReward reward : quest.getRewards()) {
                try {
                    switch (reward.getType()) {
                        case FACTION_POINTS -> {
                            if (reward.getTarget() != null) {
                                adjustFactionPoints(playerUuid, NamespacedId.of(reward.getTarget()), reward.getAmount());
                            }
                        }
                        case EXPERIENCE -> {
                            if (minecraftServer != null) {
                                var player = minecraftServer.getPlayerList().getPlayer(playerUuid);
                                if (player != null) {
                                    player.giveExperiencePoints(reward.getAmount());
                                }
                            }
                        }
                        case ITEM -> {
                            if (minecraftServer != null && reward.getTarget() != null) {
                                var player = minecraftServer.getPlayerList().getPlayer(playerUuid);
                                if (player != null) {
                                    var itemRl = net.minecraft.resources.ResourceLocation.tryParse(reward.getTarget().trim());
                                    if (itemRl != null) {
                                        var itemOpt = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(itemRl);
                                        itemOpt.ifPresent(item -> {
                                            net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item, Math.max(1, reward.getAmount()));
                                            if (!player.getInventory().add(stack)) {
                                                player.drop(stack, false);
                                            }
                                        });
                                    }
                                }
                            }
                        }
                        case COMMAND -> {
                            if (minecraftServer != null && reward.getTarget() != null) {
                                var player = minecraftServer.getPlayerList().getPlayer(playerUuid);
                                String cmd = reward.getTarget().trim();
                                if (!cmd.isEmpty()) {
                                    if (player != null) {
                                        String safeName = player.getScoreboardName().replaceAll("[^a-zA-Z0-9_]", "");
                                        if (safeName.length() < 2 || safeName.length() > 16) safeName = "unknown";
                                        cmd = cmd.replace("%player%", safeName);
                                    }
                                    if (cmd.startsWith("/")) cmd = cmd.substring(1);
                                    minecraftServer.getCommands().performPrefixedCommand(
                                            minecraftServer.createCommandSourceStack().withSuppressedOutput().withMaximumPermission(4),
                                            cmd
                                    );
                                }
                            }
                        }
                        default -> System.err.println("[StoryNPCs] Unhandled quest reward type: " + reward.getType());
                    }
                } catch (Exception e) {
                    System.err.println("Failed to deliver quest reward " + reward.getType() + " for quest " + questId + " to player " + playerUuid + ": " + e.getMessage());
                }
            }
        }

        eventPublisher.publish(new QuestCompleteEvent(playerUuid, questId));
    }

    // ==========================================
    // 5. Role & Subsystem Operations
    // ==========================================

    public boolean executeTrade(UUID playerUuid, NamespacedId npcId, com.storynpcs.domain.role.trader.TradeListing trade) {
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

        // VULN-48: Verify player has the required price item; deduct it before giving offer item
        if (minecraftServer != null && trade.getPriceItemId() != null && !trade.getPriceItemId().isBlank()) {
            var player = minecraftServer.getPlayerList().getPlayer(playerUuid);
            if (player == null) return false;
            var priceRl = net.minecraft.resources.ResourceLocation.tryParse(trade.getPriceItemId().trim());
            if (priceRl == null) return false;
            var priceItemOpt = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(priceRl);
            if (priceItemOpt.isEmpty()) return false;
            int required = Math.max(1, trade.getPriceCount());
            var priceItem = priceItemOpt.get();
            int held = player.getInventory().items.stream()
                    .filter(s -> !s.isEmpty() && s.getItem() == priceItem)
                    .mapToInt(net.minecraft.world.item.ItemStack::getCount)
                    .sum();
            if (held < required) {
                return false; // insufficient items — abort without recording
            }
            // Deduct price
            int toRemove = required;
            for (net.minecraft.world.item.ItemStack slot : player.getInventory().items) {
                if (!slot.isEmpty() && slot.getItem() == priceItem && toRemove > 0) {
                    int take = Math.min(slot.getCount(), toRemove);
                    slot.shrink(take);
                    toRemove -= take;
                }
            }
            // Give offer item
            if (trade.getOfferItemId() != null && !trade.getOfferItemId().isBlank()) {
                var offerRl = net.minecraft.resources.ResourceLocation.tryParse(trade.getOfferItemId().trim());
                if (offerRl != null) {
                    var offerOpt = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(offerRl);
                    offerOpt.ifPresent(offerItem -> {
                        net.minecraft.world.item.ItemStack offerStack = new net.minecraft.world.item.ItemStack(offerItem, Math.max(1, trade.getOfferCount()));
                        if (!player.getInventory().add(offerStack)) {
                            player.drop(offerStack, false);
                        }
                    });
                }
            }
        }

        // VULN-48: recordTrade is synchronized inside TradeListing to prevent race conditions
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
        var vault = bankRepo.getOrCreate(playerUuid);
        boolean success = vault.deposit(tab, slot, itemId, count, tag);
        if (success) {
            try {
                bankRepo.save(playerUuid);
            } catch (IOException e) {
                System.err.println("Failed to persist bank vault for " + playerUuid + ": " + e.getMessage());
            }
            eventPublisher.publish(new com.storynpcs.api.event.BankTransactionEvent(
                    playerUuid, com.storynpcs.api.event.BankTransactionEvent.Type.DEPOSIT, tab, itemId, count));
        }
        return success;
    }

    public java.util.Optional<com.storynpcs.domain.role.banker.BankVault.VaultItem> withdrawFromBank(
            UUID playerUuid, com.storynpcs.persistence.BankRepository bankRepo, int tab, int slot, int count) {
        if (bankRepo == null) return java.util.Optional.empty();
        var vault = bankRepo.getOrCreate(playerUuid);
        var itemOpt = vault.withdraw(tab, slot, count);
        itemOpt.ifPresent(item -> {
            try {
                bankRepo.save(playerUuid);
            } catch (IOException e) {
                System.err.println("Failed to persist bank vault for " + playerUuid + ": " + e.getMessage());
            }
            eventPublisher.publish(new com.storynpcs.api.event.BankTransactionEvent(
                    playerUuid, com.storynpcs.api.event.BankTransactionEvent.Type.WITHDRAW, tab, item.getItemId(), item.getCount()));
        });
        return itemOpt;
    }

    public void adjustFactionReputation(UUID playerUuid, NamespacedId factionId, int delta) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(factionId, "factionId");
        PlayerProgression prog = progressionRepository.getOrCreate(playerUuid);
        int defaultPoints = registry.getFaction(factionId).map(Faction::getDefaultPoints).orElse(0);
        int oldScore = prog.getFactionScore(factionId, defaultPoints);
        prog.adjustFactionScore(factionId, delta, defaultPoints);
        int newScore = prog.getFactionScore(factionId, defaultPoints);
        try {
            progressionRepository.save(playerUuid);
        } catch (IOException e) {
            System.err.println("Failed to persist progression for " + playerUuid + ": " + e.getMessage());
        }
        eventPublisher.publish(new FactionReputationChangeEvent(playerUuid, factionId, oldScore, newScore));
    }
}

