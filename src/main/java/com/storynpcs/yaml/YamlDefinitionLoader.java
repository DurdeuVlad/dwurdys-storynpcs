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

    public YamlDefinitionLoader(DefinitionRegistry registry) {
        this.registry = registry;
        this.mapper = new ObjectMapper(new YAMLFactory());
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public DefinitionRegistry getRegistry() {
        return registry;
    }

    public NpcDefinition loadNpc(String yamlContent, String sourceName, ValidationResult result) {
        try {
            NpcDefinition npc = mapper.readValue(yamlContent, NpcDefinition.class);
            if (npc.getId() == null) {
                result.addError(sourceName, 1, 1, "SCHEMA_MISSING_ID", "NPC definition must declare an 'id'");
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
        try {
            DialogueGraph dialogue = mapper.readValue(yamlContent, DialogueGraph.class);
            if (dialogue.getId() == null) {
                result.addError(sourceName, 1, 1, "SCHEMA_MISSING_ID", "Dialogue definition must declare an 'id'");
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
        try {
            Faction faction = mapper.readValue(yamlContent, Faction.class);
            if (faction.getId() == null) {
                result.addError(sourceName, 1, 1, "SCHEMA_MISSING_ID", "Faction definition must declare an 'id'");
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
        try {
            Quest quest = mapper.readValue(yamlContent, Quest.class);
            if (quest.getId() == null) {
                result.addError(sourceName, 1, 1, "SCHEMA_MISSING_ID", "Quest definition must declare an 'id'");
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

    public ValidationResult loadDirectory(Path rootPath) throws IOException {
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