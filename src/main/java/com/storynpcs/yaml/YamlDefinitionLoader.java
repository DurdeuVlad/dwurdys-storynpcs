package com.storynpcs.yaml;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.quest.Quest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Loads, parses, and validates YAML content definitions.
 */
public class YamlDefinitionLoader {
    private final ObjectMapper mapper;
    private final DefinitionRegistry registry;
    /** Root path of the last loaded definitions directory — used to delete files on /npc delete (VULN-57 fix). */
    private Path lastLoadedRootPath;

    public YamlDefinitionLoader(DefinitionRegistry registry) {
        this.registry = registry;
        this.mapper = new ObjectMapper(new YAMLFactory());
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public DefinitionRegistry getRegistry() {
        return registry;
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
            NpcDefinition npc = mapper.readValue(yamlContent, NpcDefinition.class);
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
            DialogueGraph dialogue = mapper.readValue(yamlContent, DialogueGraph.class);
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
            Faction faction = mapper.readValue(yamlContent, Faction.class);
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
            Quest quest = mapper.readValue(yamlContent, Quest.class);
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
        } catch (JsonMappingException e) {
            result.addError(sourceName, e.getLocation() != null ? e.getLocation().getLineNr() : 1,
                    e.getLocation() != null ? e.getLocation().getColumnNr() : 1,
                    "YAML_MAPPING_ERROR", e.getOriginalMessage());
        } catch (Exception e) {
            result.addError(sourceName, 1, 1, "LOAD_ERROR", e.getMessage());
        }
        return null;
    }

    /** Returns the definitions root path that was passed to the last {@code loadDirectory} call. */
    public Path getLastLoadedRootPath() {
        return lastLoadedRootPath;
    }

    /**
     * Deletes the YAML definition file that defines {@code id}.
     * File must be located somewhere under {@link #lastLoadedRootPath}.
     * VULN-57: Without this, deleting an NPC from the registry leaves the file on disk and it
     * resurrects the next time the server reloads definitions.
     *
     * @return true if a file was found and deleted, false if not found or already missing.
     */
    public boolean deleteDefinitionFile(String type, com.storynpcs.domain.common.NamespacedId id) {
        if (lastLoadedRootPath == null || id == null) return false;
        // Convention: files live under <root>/<type>/<namespace>/<name>.yml or flat <root>/*.yml
        // We search the whole tree for the first file whose parsed id matches.
        try (Stream<Path> stream = Files.walk(lastLoadedRootPath)) {
            return stream
                    .filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                    .filter(p -> {
                        // Quick heuristic: check if filename contains the id's name part
                        String fn = p.getFileName().toString();
                        return fn.contains(id.getPath()) || fn.contains(id.toString().replace(":", "_"));
                    })
                    .filter(p -> {
                        // Confirm by attempting to parse and checking the id field
                        try {
                            var node = mapper.readTree(p.toFile());
                            if (node.has("id") && id.toString().equals(node.get("id").asText())) return true;
                            // Fallback: check namespace+name as separate fields
                            if (node.has("namespace") && node.has("name")) {
                                return id.getNamespace().equals(node.get("namespace").asText())
                                        && id.getPath().equals(node.get("name").asText());
                            }
                        } catch (Exception ignored) {}
                        return false;
                    })
                    .findFirst()
                    .map(p -> {
                        try {
                            Files.delete(p);
                            return true;
                        } catch (IOException e) {
                            System.err.println("[StoryNPCs] Failed to delete definition file " + p + ": " + e.getMessage());
                            return false;
                        }
                    })
                    .orElse(false);
        } catch (IOException e) {
            System.err.println("[StoryNPCs] Error scanning definitions directory for delete: " + e.getMessage());
            return false;
        }
    }

    public ValidationResult loadDirectory(Path rootPath) throws IOException {
        this.lastLoadedRootPath = rootPath; // VULN-57: remember for later file deletion
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

            if (parentName.equals("npcs") || parentName.equals("npc") || fileName.startsWith("npc_")) {
                loadNpc(content, file.toString(), result);
            } else if (parentName.equals("dialogues") || parentName.equals("dialogue") || fileName.startsWith("dialogue_")) {
                loadDialogue(content, file.toString(), result);
            } else if (parentName.equals("factions") || parentName.equals("faction") || fileName.startsWith("faction_")) {
                loadFaction(content, file.toString(), result);
            } else if (parentName.equals("quests") || parentName.equals("quest") || fileName.startsWith("quest_")) {
                loadQuest(content, file.toString(), result);
            } else {
                // Fallback: inspect content signatures
                if (content.contains("entryNodeId:") || content.contains("nodes:")) {
                    loadDialogue(content, file.toString(), result);
                } else if (content.contains("hostileThreshold:") || content.contains("friendlyThreshold:")) {
                    loadFaction(content, file.toString(), result);
                } else if (content.contains("objectives:") || content.contains("rewards:")) {
                    loadQuest(content, file.toString(), result);
                } else {
                    loadNpc(content, file.toString(), result);
                }
            }
        } catch (IOException e) {
            result.addError(file.toString(), 1, 1, "IO_ERROR", "Could not read file: " + e.getMessage());
        }
    }
}