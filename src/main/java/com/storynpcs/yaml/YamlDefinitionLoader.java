package com.storynpcs.yaml;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcDefinitionSerde;
import com.storynpcs.domain.quest.Quest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Loads, parses, and validates YAML content definitions.
 */
public class YamlDefinitionLoader {
    private final ObjectMapper mapper;
    private final DefinitionRegistry registry;
    /** Source paths indexed during loading so saves can update existing YAML without rescanning it all. */
    private final Map<String, Map<NamespacedId, List<Path>>> definitionFilesByTypeAndId = new HashMap<>();
    /** Shared by this loader's delete path and application-service writers bound to it. */
    private final DefinitionWriteCoordinator definitionWriteCoordinator;
    /** Root path of the last loaded definitions directory — used to delete files on /npc delete (VULN-57 fix). */
    private Path lastLoadedRootPath;

    public YamlDefinitionLoader(DefinitionRegistry registry) {
        this(registry, new DefinitionWriteCoordinator());
    }

    /** Creates a loader that shares write coordination with other owners of the same definitions root. */
    public YamlDefinitionLoader(DefinitionRegistry registry,
                                DefinitionWriteCoordinator definitionWriteCoordinator) {
        this.registry = registry;
        this.definitionWriteCoordinator = Objects.requireNonNull(
                definitionWriteCoordinator, "definitionWriteCoordinator");
        this.mapper = new ObjectMapper(new YAMLFactory()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION));
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public DefinitionRegistry getRegistry() {
        return registry;
    }

    /** Returns the lifecycle-owned lock coordinator used for writes and deletes under this loader. */
    public DefinitionWriteCoordinator getDefinitionWriteCoordinator() {
        return definitionWriteCoordinator;
    }

    private boolean isEmptyOrCommentOnly(String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) return true;
        return yamlContent.lines().allMatch(l -> l.trim().isEmpty() || l.trim().startsWith("#"));
    }

    public NpcDefinition loadNpc(String yamlContent, String sourceName, ValidationResult result) {
        if (isEmptyOrCommentOnly(yamlContent)) {
            result.addError(sourceName, 1, 1, "SCHEMA_EMPTY_FILE", "File is empty or contains no valid YAML definitions");
            return null;
        }
        try {
            NpcDefinition npc = readDefinition(yamlContent, sourceName, result, NpcDefinition.class);
            if (npc == null) return null;
            if (npc == null || npc.getId() == null) {
                result.addError(sourceName, 1, 1, "SCHEMA_MISSING_ID", "NPC definition must declare an 'id'");
                return null;
            }
            if (registry.getNpc(npc.getId()).isPresent()) {
                result.addError(sourceName, 1, 1, "DUPLICATE_DEFINITION_ID",
                        "Duplicate NPC ID '" + npc.getId() + "' is already defined in another file");
                return null;
            }
            registry.registerNpc(npc);
            return npc;
        } catch (JsonParseException e) {
            result.addError(sourceName, e.getLocation().getLineNr(), e.getLocation().getColumnNr(),
                    "YAML_PARSE_ERROR", e.getOriginalMessage());
        } catch (UnrecognizedPropertyException e) {
            addUnknownFieldError(yamlContent, sourceName, e, result);
        } catch (JsonMappingException e) {
            result.addError(sourceName, e.getLocation() != null ? e.getLocation().getLineNr() : 1,
                    e.getLocation() != null ? e.getLocation().getColumnNr() : 1,
                    "YAML_MAPPING_ERROR", e.getOriginalMessage());
        } catch (Exception e) {
            result.addError(sourceName, 1, 1, "LOAD_ERROR", e.getMessage());
        }
        return null;
    }

    public DialogueGraph loadDialogue(String yamlContent, String sourceName, ValidationResult result) {
        if (isEmptyOrCommentOnly(yamlContent)) {
            result.addError(sourceName, 1, 1, "SCHEMA_EMPTY_FILE", "File is empty or contains no valid YAML definitions");
            return null;
        }
        try {
            DialogueGraph dialogue = readDefinition(yamlContent, sourceName, result, DialogueGraph.class);
            if (dialogue == null) return null;
            if (dialogue == null || dialogue.getId() == null) {
                result.addError(sourceName, 1, 1, "SCHEMA_MISSING_ID", "Dialogue definition must declare an 'id'");
                return null;
            }
            if (registry.getDialogue(dialogue.getId()).isPresent()) {
                result.addError(sourceName, 1, 1, "DUPLICATE_DEFINITION_ID",
                        "Duplicate Dialogue ID '" + dialogue.getId() + "' is already defined in another file");
                return null;
            }
            registry.registerDialogue(dialogue);
            return dialogue;
        } catch (JsonParseException e) {
            result.addError(sourceName, e.getLocation().getLineNr(), e.getLocation().getColumnNr(),
                    "YAML_PARSE_ERROR", e.getOriginalMessage());
        } catch (UnrecognizedPropertyException e) {
            addUnknownFieldError(yamlContent, sourceName, e, result);
        } catch (JsonMappingException e) {
            result.addError(sourceName, e.getLocation() != null ? e.getLocation().getLineNr() : 1,
                    e.getLocation() != null ? e.getLocation().getColumnNr() : 1,
                    "YAML_MAPPING_ERROR", e.getOriginalMessage());
        } catch (Exception e) {
            result.addError(sourceName, 1, 1, "LOAD_ERROR", e.getMessage());
        }
        return null;
    }

    public Faction loadFaction(String yamlContent, String sourceName, ValidationResult result) {
        if (isEmptyOrCommentOnly(yamlContent)) {
            result.addError(sourceName, 1, 1, "SCHEMA_EMPTY_FILE", "File is empty or contains no valid YAML definitions");
            return null;
        }
        try {
            Faction faction = readDefinition(yamlContent, sourceName, result, Faction.class);
            if (faction == null) return null;
            if (faction == null || faction.getId() == null) {
                result.addError(sourceName, 1, 1, "SCHEMA_MISSING_ID", "Faction definition must declare an 'id'");
                return null;
            }
            if (registry.getFaction(faction.getId()).isPresent()) {
                result.addError(sourceName, 1, 1, "DUPLICATE_DEFINITION_ID",
                        "Duplicate Faction ID '" + faction.getId() + "' is already defined in another file");
                return null;
            }
            registry.registerFaction(faction);
            return faction;
        } catch (JsonParseException e) {
            result.addError(sourceName, e.getLocation().getLineNr(), e.getLocation().getColumnNr(),
                    "YAML_PARSE_ERROR", e.getOriginalMessage());
        } catch (UnrecognizedPropertyException e) {
            addUnknownFieldError(yamlContent, sourceName, e, result);
        } catch (JsonMappingException e) {
            result.addError(sourceName, e.getLocation() != null ? e.getLocation().getLineNr() : 1,
                    e.getLocation() != null ? e.getLocation().getColumnNr() : 1,
                    "YAML_MAPPING_ERROR", e.getOriginalMessage());
        } catch (Exception e) {
            result.addError(sourceName, 1, 1, "LOAD_ERROR", e.getMessage());
        }
        return null;
    }

    public Quest loadQuest(String yamlContent, String sourceName, ValidationResult result) {
        if (isEmptyOrCommentOnly(yamlContent)) {
            result.addError(sourceName, 1, 1, "SCHEMA_EMPTY_FILE", "File is empty or contains no valid YAML definitions");
            return null;
        }
        try {
            Quest quest = readDefinition(yamlContent, sourceName, result, Quest.class);
            if (quest == null) return null;
            if (quest == null || quest.getId() == null) {
                result.addError(sourceName, 1, 1, "SCHEMA_MISSING_ID", "Quest definition must declare an 'id'");
                return null;
            }
            if (registry.getQuest(quest.getId()).isPresent()) {
                result.addError(sourceName, 1, 1, "DUPLICATE_DEFINITION_ID",
                        "Duplicate Quest ID '" + quest.getId() + "' is already defined in another file");
                return null;
            }
            registry.registerQuest(quest);
            return quest;
        } catch (JsonParseException e) {
            result.addError(sourceName, e.getLocation().getLineNr(), e.getLocation().getColumnNr(),
                    "YAML_PARSE_ERROR", e.getOriginalMessage());
        } catch (UnrecognizedPropertyException e) {
            addUnknownFieldError(yamlContent, sourceName, e, result);
        } catch (JsonMappingException e) {
            result.addError(sourceName, e.getLocation() != null ? e.getLocation().getLineNr() : 1,
                    e.getLocation() != null ? e.getLocation().getColumnNr() : 1,
                    "YAML_MAPPING_ERROR", e.getOriginalMessage());
        } catch (Exception e) {
            result.addError(sourceName, 1, 1, "LOAD_ERROR", e.getMessage());
        }
        return null;
    }

    private <T> T readDefinition(String yamlContent, String sourceName,
                                 ValidationResult result, Class<T> type) throws IOException {
        JsonNode normalized = DefinitionSchema.normalize(mapper, yamlContent, sourceName, result);
        if (normalized == null) return null;
        T definition = mapper.treeToValue(normalized, type);
        if (definition instanceof NpcDefinition npc) {
            NpcDefinitionSerde.restoreSkinSourceAfterDeserialization(npc, normalized);
        }
        return definition;
    }

    private void addUnknownFieldError(String yamlContent, String sourceName,
                                      UnrecognizedPropertyException exception,
                                      ValidationResult result) {
        int line = DefinitionSchema.lineOfField(yamlContent, exception.getPropertyName());
        int column = DefinitionSchema.columnOfField(yamlContent, exception.getPropertyName());
        result.addError(sourceName, line, column, "SCHEMA_UNKNOWN_FIELD",
                "Unknown field '" + exception.getPropertyName() + "'"
                        + (exception.getPathReference() != null ? " at " + exception.getPathReference() : ""));
    }

    /** Returns the definitions root path that was passed to the last {@code loadDirectory} call. */
    public synchronized Path getLastLoadedRootPath() {
        return lastLoadedRootPath;
    }

    /** Returns all loaded YAML source paths for a definition type and ID. */
    public synchronized List<Path> getDefinitionFiles(String type, NamespacedId id) {
        if (type == null || id == null) {
            return List.of();
        }
        return List.copyOf(definitionFilesByTypeAndId
                .getOrDefault(normalizeDefinitionType(type), Map.of())
                .getOrDefault(id, List.of()));
    }

    /** Replaces the indexed source path after a successful application-service save. */
    public synchronized void recordDefinitionFile(String type, NamespacedId id, Path file) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(file, "file");
        String normalizedType = normalizeDefinitionType(type);
        if (normalizedType == null || lastLoadedRootPath == null) {
            return;
        }
        Path normalizedRoot = lastLoadedRootPath.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();
        if (!normalizedFile.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Definition source path is outside the loaded root: " + file);
        }
        List<Path> files = definitionFilesByTypeAndId
                .computeIfAbsent(normalizedType, ignored -> new HashMap<>())
                .computeIfAbsent(id, ignored -> new ArrayList<>());
        files.clear();
        files.add(normalizedFile);
    }

    /**
     * Deletes the YAML definition file that defines {@code id}.
     * File must be located somewhere under {@link #lastLoadedRootPath}.
     * VULN-57: Without this, deleting an NPC from the registry leaves the file on disk and it
     * resurrects the next time the server reloads definitions.
     *
     * @return true when at least one matching source file was deleted, false when no source file
     *         is known for this ID. A stale indexed path or an I/O/security failure is reported
     *         as {@link IOException}; callers must not remove the live definition in that case.
     */
    public synchronized boolean deleteDefinitionFile(String type, NamespacedId id) throws IOException {
        if (lastLoadedRootPath == null || id == null) return false;
        String normalizedType = normalizeDefinitionType(type);
        if (normalizedType == null) {
            throw new IOException("Unsupported definition type for deletion: " + type);
        }
        if (!Files.isDirectory(lastLoadedRootPath)) {
            throw new IOException("Loaded definitions root is no longer available: " + lastLoadedRootPath);
        }

        Path root = lastLoadedRootPath;
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path typeDirectory = normalizedRoot.resolve(normalizedType).normalize();
        if (!typeDirectory.startsWith(normalizedRoot) || Files.isSymbolicLink(typeDirectory)) {
            throw new IOException("Refusing to delete from unsafe definition directory: " + typeDirectory);
        }

        Files.createDirectories(typeDirectory);
        Path realRoot = root.toRealPath();
        Path realTypeDirectory = typeDirectory.toRealPath();
        if (!realTypeDirectory.startsWith(realRoot)) {
            throw new IOException("Definition directory resolves outside the loaded root");
        }

        DefinitionWriteLock writeLock = definitionWriteCoordinator.acquire(typeDirectory);
        boolean deletionCommitted = false;
        try {
            List<Path> sourceFiles = new ArrayList<>(getDefinitionFiles(normalizedType, id));
            boolean hasIndexedSource = !sourceFiles.isEmpty();
            if (!hasIndexedSource) {
                sourceFiles.addAll(fallbackDefinitionPaths(root, normalizedType, id));
            }
            if (sourceFiles.isEmpty()) {
                return false;
            }

            List<Path> verifiedSources = new ArrayList<>();
            for (Path sourceFile : sourceFiles) {
                Path normalizedFile = sourceFile.toAbsolutePath().normalize();
                if (!normalizedFile.startsWith(normalizedRoot)) {
                    throw new IOException("Definition source is outside the loaded root: " + sourceFile);
                }
                if (!Files.exists(normalizedFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    if (hasIndexedSource) {
                        throw new IOException("Indexed definition source no longer exists for " + id
                                + "; reload definitions before deleting: " + normalizedFile);
                    }
                    continue;
                }
                if (Files.isSymbolicLink(normalizedFile)) {
                    throw new IOException("Refusing to delete symbolic-link definition file: " + normalizedFile);
                }
                Path realParent = normalizedFile.getParent().toRealPath();
                if (!realParent.equals(realRoot) && !realParent.startsWith(realTypeDirectory)) {
                    throw new IOException("Definition source is outside the flat or typed directory: " + normalizedFile);
                }
                if (!id.equals(readDefinitionId(normalizedFile))) {
                    throw new IOException("Definition source no longer defines " + id + ": " + normalizedFile);
                }
                verifiedSources.add(normalizedFile);
            }
            if (verifiedSources.isEmpty()) {
                return false;
            }
            if (verifiedSources.size() > 1) {
                throw new IOException("Refusing to delete " + id + ": multiple definition files must be resolved first: "
                        + verifiedSources);
            }

            // A single delete is the only durable mutation; duplicate sources fail closed above.
            Files.delete(verifiedSources.get(0));
            removeDefinitionFiles(normalizedType, id);
            deletionCommitted = true;
            return true;
        } finally {
            try {
                writeLock.close();
            } catch (IOException closeFailure) {
                if (!deletionCommitted) {
                    throw closeFailure;
                }
                System.err.println("[StoryNPCs] Definition was deleted but its writer lock did not close cleanly: "
                        + closeFailure.getMessage());
            }
        }
    }

    private List<Path> fallbackDefinitionPaths(Path root, String type, NamespacedId id) {
        List<Path> candidates = new ArrayList<>();
        for (String name : YamlDefinitionWriter.fileNameCandidatesFor(id)) {
            candidates.add(root.resolve(name + ".yaml"));
            candidates.add(root.resolve(type).resolve(name + ".yaml"));
        }
        List<Path> matches = new ArrayList<>();
        for (Path candidate : candidates) {
            if (!Files.exists(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(candidate)) {
                continue;
            }
            try {
                if (id.equals(readDefinitionId(candidate))) {
                    matches.add(candidate);
                }
            } catch (IOException e) {
                System.err.println("[StoryNPCs] Skipping unreadable fallback definition " + candidate + ": "
                        + e.getMessage());
            }
        }
        return matches;
    }

    private NamespacedId readDefinitionId(Path file) throws IOException {
        JsonNode definition = mapper.readTree(file.toFile());
        JsonNode idNode = definition == null ? null : definition.get("id");
        if (idNode != null && idNode.isTextual()) {
            try {
                return NamespacedId.of(idNode.asText());
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid definition ID in " + file, e);
            }
        }
        throw new IOException("Missing textual definition ID in " + file);
    }

    private void removeDefinitionFiles(String type, NamespacedId id) {
        Map<NamespacedId, List<Path>> filesById = definitionFilesByTypeAndId.get(type);
        if (filesById != null) {
            filesById.remove(id);
            if (filesById.isEmpty()) {
                definitionFilesByTypeAndId.remove(type);
            }
        }
    }

    private String normalizeDefinitionType(String type) {
        if (type == null) return null;
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "npc", "npcs" -> "npcs";
            case "dialogue", "dialogues" -> "dialogues";
            case "faction", "factions" -> "factions";
            case "quest", "quests" -> "quests";
            default -> null;
        };
    }

    public synchronized ValidationResult loadDirectory(Path rootPath) throws IOException {
        this.lastLoadedRootPath = rootPath; // VULN-57: remember for later file deletion
        definitionFilesByTypeAndId.clear();
        ValidationResult result = ValidationResult.valid();
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            result.addWarning(rootPath.toString(), 0, 0, "DIR_NOT_FOUND", "Directory does not exist: " + rootPath);
            return result;
        }

        try (Stream<Path> stream = Files.walk(rootPath)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                  .forEach(p -> loadFile(p, result));
        }

        // Run cross reference validation after all files are registered
        result.merge(CrossReferenceValidator.validate(registry));
        return result;
    }

    private void loadFile(Path file, ValidationResult result) {
        try {
            String content = Files.readString(file);
            Path parent = file.getParent();
            String parentName = parent != null ? parent.getFileName().toString().toLowerCase() : "";
            String fileName = file.getFileName().toString().toLowerCase();
            String type = definitionType(parentName, fileName, content);

            switch (type) {
                case "npcs" -> loadNpc(content, file.toString(), result);
                case "dialogues" -> loadDialogue(content, file.toString(), result);
                case "factions" -> loadFaction(content, file.toString(), result);
                case "quests" -> loadQuest(content, file.toString(), result);
                default -> throw new IllegalStateException("Unsupported definition type: " + type);
            }
            indexDefinitionFile(type, file, content, result);
        } catch (IOException e) {
            result.addError(file.toString(), 1, 1, "IO_ERROR", "Could not read file: " + e.getMessage());
        }
    }

    private String definitionType(String parentName, String fileName, String content) {
        if (parentName.equals("npcs") || parentName.equals("npc") || fileName.startsWith("npc_")) {
            return "npcs";
        }
        if (parentName.equals("dialogues") || parentName.equals("dialogue") || fileName.startsWith("dialogue_")) {
            return "dialogues";
        }
        if (parentName.equals("factions") || parentName.equals("faction") || fileName.startsWith("faction_")) {
            return "factions";
        }
        if (parentName.equals("quests") || parentName.equals("quest") || fileName.startsWith("quest_")) {
            return "quests";
        }

        // Fallback: inspect content signatures, matching the legacy loader behavior.
        if (content.contains("entryNodeId:") || content.contains("nodes:")) {
            return "dialogues";
        }
        if (content.contains("hostileThreshold:") || content.contains("friendlyThreshold:")) {
            return "factions";
        }
        if (content.contains("objectives:") || content.contains("rewards:")) {
            return "quests";
        }
        return "npcs";
    }

    private synchronized void indexDefinitionFile(String type, Path file, String content, ValidationResult result) {
        try {
            JsonNode definition = mapper.readTree(content);
            JsonNode idNode = definition == null ? null : definition.get("id");
            if (idNode == null || !idNode.isTextual()) {
                return;
            }
            NamespacedId id = NamespacedId.of(idNode.asText());
            definitionFilesByTypeAndId
                    .computeIfAbsent(type, ignored -> new HashMap<>())
                    .computeIfAbsent(id, ignored -> new ArrayList<>())
                    .add(file.toAbsolutePath().normalize());
        } catch (IOException | IllegalArgumentException e) {
            // The typed loader reports the invalid record; surface that its source path could not be indexed.
            result.addWarning(file.toString(), 1, 1, "DEFINITION_SOURCE_INDEX_FAILED",
                    "Could not index the definition ID for safe future saves: " + e.getMessage());
        }
    }
}
