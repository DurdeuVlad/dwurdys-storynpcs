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
            if (npc.getJob() != null) {
                try {
                    npc.getJob().validate();
                } catch (RuntimeException jobFailure) {
                    result.addError(sourceName, 1, 1, "JOB_CONFIG_INVALID",
                            "Invalid job configuration: " + jobFailure.getMessage());
                    return null;
                }
            }
            if (npc.getTrader() != null) {
                // Every authored listing must be a coherent transaction BEFORE
                // it can be loaded — a malformed two-input listing that reaches
                // the registry would silently undercharge players at execute.
                boolean valid = true;
                var listings = npc.getTrader().getListings();
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
                        result.addError(sourceName, 1, 1, "TRADE_LISTING_INVALID",
                                "Invalid trade listing #" + i + " (" + label
                                        + "): " + listingFailure.getMessage());
                        valid = false;
                    }
                }
                if (!valid) return null;
            }
            {
                // #147: abilities are bounded effects — reject out-of-range
                // parameters and trigger combinations before they reach the
                // registry, mirroring the job/listing checks above.
                boolean abilitiesValid = true;
                var abilities = npc.getAbilities();
                for (int i = 0; i < abilities.size(); i++) {
                    var ability = abilities.get(i);
                    try {
                        if (ability == null) {
                            throw new IllegalStateException("null ability");
                        }
                        ability.validate();
                    } catch (RuntimeException abilityFailure) {
                        result.addError(sourceName, 1, 1, "ABILITY_INVALID",
                                "Invalid ability #" + i + ": " + abilityFailure.getMessage());
                        abilitiesValid = false;
                    }
                }
                if (!abilitiesValid) return null;
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
            // Structural validation: cycles/unreachable nodes/dangling edges
            // are definition errors — the graph must fail closed at load.
            var graphValidation = new com.storynpcs.domain.dialogue.DialogueGraphValidator()
                    .validate(dialogue);
            if (graphValidation.hasErrors()) {
                for (var err : graphValidation.getErrors()) {
                    result.addError(sourceName, 1, 1, err.code(), err.message());
                }
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

    public com.storynpcs.domain.transport.TransportLocation loadTransport(
            String yamlContent, String sourceName, ValidationResult result) {
        if (isEmptyOrCommentOnly(yamlContent)) {
            result.addError(sourceName, 1, 1, "SCHEMA_EMPTY_FILE", "File is empty or contains no valid YAML definitions");
            return null;
        }
        try {
            var location = readDefinition(yamlContent, sourceName, result,
                    com.storynpcs.domain.transport.TransportLocation.class);
            if (location == null) return null;
            if (location.getId() == null) {
                result.addError(sourceName, 1, 1, "SCHEMA_MISSING_ID",
                        "Transport definition must declare an 'id'");
                return null;
            }
            var contractErrors = location.validateDestinationContract();
            for (String contractError : contractErrors) {
                result.addError(sourceName, 1, 1, "TRANSPORT_CONTRACT_INVALID", contractError);
            }
            if (!contractErrors.isEmpty()) return null;
            if (registry.getTransportLocation(location.getId()).isPresent()) {
                result.addError(sourceName, 1, 1, "DUPLICATE_DEFINITION_ID",
                        "Duplicate Transport ID '" + location.getId() + "' is already defined in another file");
                return null;
            }
            registry.registerTransportLocation(location);
            return location;
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

    public com.storynpcs.creator.template.NpcTemplate loadTemplate(
            String yamlContent, String sourceName, ValidationResult result) {
        if (isEmptyOrCommentOnly(yamlContent)) {
            result.addError(sourceName, 1, 1, "SCHEMA_EMPTY_FILE", "File is empty or contains no valid YAML definitions");
            return null;
        }
        try {
            var template = readDefinition(yamlContent, sourceName, result,
                    com.storynpcs.creator.template.NpcTemplate.class);
            if (template == null) return null;
            if (template.getId() == null) {
                result.addError(sourceName, 1, 1, "SCHEMA_MISSING_ID",
                        "Template definition must declare an 'id'");
                return null;
            }
            if (template.getSchemaVersion() != com.storynpcs.creator.template.NpcTemplate.SCHEMA_VERSION) {
                result.addError(sourceName, 1, 1, "SCHEMA_VERSION_UNSUPPORTED",
                        "Template schemaVersion " + template.getSchemaVersion()
                                + " is not supported (expected "
                                + com.storynpcs.creator.template.NpcTemplate.SCHEMA_VERSION + ")");
                return null;
            }
            if (template.getDefinition() == null) {
                result.addError(sourceName, 1, 1, "TEMPLATE_MISSING_DEFINITION",
                        "Template must embed an NPC 'definition'");
                return null;
            }
            if (registry.getTemplate(template.getId()).isPresent()) {
                result.addError(sourceName, 1, 1, "DUPLICATE_DEFINITION_ID",
                        "Duplicate Template ID '" + template.getId() + "' is already defined in another file");
                return null;
            }
            registry.registerTemplate(template);
            return template;
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

    /**
     * P8-1 spawner family: an anchored rule points at a template and drives the
     * bounded spawner runtime. Anchor fields are all-or-none — a partial anchor
     * fails closed rather than spawning at an ambiguous origin.
     */
    public com.storynpcs.creator.template.SpawnerRule loadSpawner(
            String yamlContent, String sourceName, ValidationResult result) {
        if (isEmptyOrCommentOnly(yamlContent)) {
            result.addError(sourceName, 1, 1, "SCHEMA_EMPTY_FILE", "File is empty or contains no valid YAML definitions");
            return null;
        }
        try {
            var rule = readDefinition(yamlContent, sourceName, result,
                    com.storynpcs.creator.template.SpawnerRule.class);
            if (rule == null) return null;
            if (rule.getId() == null) {
                result.addError(sourceName, 1, 1, "SCHEMA_MISSING_ID",
                        "Spawner definition must declare an 'id'");
                return null;
            }
            if (rule.getSchemaVersion() != com.storynpcs.creator.template.SpawnerRule.SCHEMA_VERSION) {
                result.addError(sourceName, 1, 1, "SCHEMA_VERSION_UNSUPPORTED",
                        "Spawner schemaVersion " + rule.getSchemaVersion()
                                + " is not supported (expected "
                                + com.storynpcs.creator.template.SpawnerRule.SCHEMA_VERSION + ")");
                return null;
            }
            if (rule.getTemplateId() == null) {
                result.addError(sourceName, 1, 1, "SPAWNER_MISSING_TEMPLATE",
                        "Spawner must declare a 'templateId'");
                return null;
            }
            if (rule.hasPartialAnchor()) {
                result.addError(sourceName, 1, 1, "SPAWNER_PARTIAL_ANCHOR",
                        "Spawner anchor is all-or-none — set 'dimension', 'anchorX',"
                                + " 'anchorY' and 'anchorZ' together or none");
                return null;
            }
            if (registry.getSpawnerRule(rule.getId()).isPresent()) {
                result.addError(sourceName, 1, 1, "DUPLICATE_DEFINITION_ID",
                        "Duplicate Spawner ID '" + rule.getId() + "' is already defined in another file");
                return null;
            }
            registry.registerSpawnerRule(rule);
            if (registry.getTemplate(rule.getTemplateId()).isEmpty()) {
                result.addWarning(sourceName, 1, 1, "SPAWNER_TEMPLATE_UNKNOWN",
                        "Spawner '" + rule.getId() + "' references template '" + rule.getTemplateId()
                                + "' which is not loaded — it stays inert until the template registers");
            }
            return rule;
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

    /**
     * P8-3 world-tool family: typed tool defs with a family, optional dimension
     * binding, a bounded mutation budget, and inert typed hook bindings. Block-
     * placing families must declare {@code blockId}; the catalog supplies the
     * reversible/irreversible op contract at activation time.
     */
    public com.storynpcs.creator.world.WorldToolDefinition loadWorldTool(
            String yamlContent, String sourceName, ValidationResult result) {
        if (isEmptyOrCommentOnly(yamlContent)) {
            result.addError(sourceName, 1, 1, "SCHEMA_EMPTY_FILE", "File is empty or contains no valid YAML definitions");
            return null;
        }
        try {
            var tool = readDefinition(yamlContent, sourceName, result,
                    com.storynpcs.creator.world.WorldToolDefinition.class);
            if (tool == null) return null;
            if (tool.getId() == null) {
                result.addError(sourceName, 1, 1, "SCHEMA_MISSING_ID",
                        "World-tool definition must declare an 'id'");
                return null;
            }
            if (tool.getSchemaVersion() != com.storynpcs.creator.world.WorldToolDefinition.SCHEMA_VERSION) {
                result.addError(sourceName, 1, 1, "SCHEMA_VERSION_UNSUPPORTED",
                        "World-tool schemaVersion " + tool.getSchemaVersion()
                                + " is not supported (expected "
                                + com.storynpcs.creator.world.WorldToolDefinition.SCHEMA_VERSION + ")");
                return null;
            }
            if (tool.getFamily() == null) {
                result.addError(sourceName, 1, 1, "WORLDTOOL_MISSING_FAMILY",
                        "World tool must declare a 'family' (scripter|scene|scripted_block|"
                                + "scripted_door|mailbox|redstone|banner)");
                return null;
            }
            if (com.storynpcs.creator.world.WorldToolDefinition.placesBlock(tool.getFamily())
                    && tool.getBlockId() == null) {
                result.addError(sourceName, 1, 1, "WORLDTOOL_MISSING_BLOCK",
                        "World tool '" + tool.getId() + "' of family '" + tool.getFamily()
                                + "' places a block and must declare 'blockId'");
                return null;
            }
            for (var hook : tool.getHooks()) {
                if (hook.getHookId() == null || hook.getEvent() == null) {
                    result.addError(sourceName, 1, 1, "WORLDTOOL_HOOK_INCOMPLETE",
                            "World tool '" + tool.getId()
                                    + "' has a hook binding missing 'hookId' or 'event'");
                    return null;
                }
            }
            if (registry.getWorldTool(tool.getId()).isPresent()) {
                result.addError(sourceName, 1, 1, "DUPLICATE_DEFINITION_ID",
                        "Duplicate world-tool ID '" + tool.getId() + "' is already defined in another file");
                return null;
            }
            registry.registerWorldTool(tool);
            return tool;
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

    /**
     * P8-4 recipe family: carpentry-bench recipes — a namespaced id, a group,
     * an exact 3x3 grid (or shapeless multiset), and an output stack. The
     * model's {@link com.storynpcs.creator.recipe.CarpentryRecipe#validate()}
     * supplies slot-accurate diagnostics; duplicates fail deterministically.
     */
    public com.storynpcs.creator.recipe.CarpentryRecipe loadRecipe(
            String yamlContent, String sourceName, ValidationResult result) {
        if (isEmptyOrCommentOnly(yamlContent)) {
            result.addError(sourceName, 1, 1, "SCHEMA_EMPTY_FILE", "File is empty or contains no valid YAML definitions");
            return null;
        }
        try {
            var recipe = readDefinition(yamlContent, sourceName, result,
                    com.storynpcs.creator.recipe.CarpentryRecipe.class);
            if (recipe == null) return null;
            if (recipe.getId() == null) {
                result.addError(sourceName, 1, 1, "SCHEMA_MISSING_ID",
                        "Recipe definition must declare an 'id'");
                return null;
            }
            if (recipe.getSchemaVersion() != com.storynpcs.creator.recipe.CarpentryRecipe.SCHEMA_VERSION) {
                result.addError(sourceName, 1, 1, "SCHEMA_VERSION_UNSUPPORTED",
                        "Recipe schemaVersion " + recipe.getSchemaVersion()
                                + " is not supported (expected "
                                + com.storynpcs.creator.recipe.CarpentryRecipe.SCHEMA_VERSION + ")");
                return null;
            }
            if (recipe.getGroupId() == null) {
                result.addError(sourceName, 1, 1, "RECIPE_MISSING_GROUP",
                        "Recipe '" + recipe.getId() + "' must declare a 'groupId'");
                return null;
            }
            var modelValidation = recipe.validate();
            for (var error : modelValidation.getErrors()) {
                result.addError(sourceName, 1, 1, error.code(), error.message());
            }
            if (!modelValidation.getErrors().isEmpty()) {
                return null;
            }
            if (registry.getRecipe(recipe.getId()).isPresent()) {
                result.addError(sourceName, 1, 1, "DUPLICATE_DEFINITION_ID",
                        "Duplicate recipe ID '" + recipe.getId() + "' is already defined in another file");
                return null;
            }
            registry.registerRecipe(recipe);
            return recipe;
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

    /** P8-5 scene family: bounded scripted scenes — entity/duration budgets + cancel recovery. */
    public com.storynpcs.creator.scene.SceneDefinition loadScene(
            String yamlContent, String sourceName, ValidationResult result) {
        var scene = loadBoundedDefinition(yamlContent, sourceName, result,
                com.storynpcs.creator.scene.SceneDefinition.class, "scene",
                com.storynpcs.creator.scene.SceneDefinition.SCHEMA_VERSION,
                com.storynpcs.creator.scene.SceneDefinition::getId,
                com.storynpcs.creator.scene.SceneDefinition::getSchemaVersion,
                registry::getScene, registry::registerScene);
        if (scene != null) {
            result.merge(scene.validate());
            if (!result.getErrors().isEmpty()) {
                registry.removeScene(scene.getId());
                return null;
            }
        }
        return scene;
    }

    /** P8-5 transform family: data-driven actor transformations. */
    public com.storynpcs.creator.transform.TransformRule loadTransform(
            String yamlContent, String sourceName, ValidationResult result) {
        var rule = loadBoundedDefinition(yamlContent, sourceName, result,
                com.storynpcs.creator.transform.TransformRule.class, "transform",
                com.storynpcs.creator.transform.TransformRule.SCHEMA_VERSION,
                com.storynpcs.creator.transform.TransformRule::getId,
                com.storynpcs.creator.transform.TransformRule::getSchemaVersion,
                registry::getTransform, registry::registerTransform);
        if (rule != null && rule.getTargetTemplateId() == null) {
            result.addError(sourceName, 1, 1, "TRANSFORM_MISSING_TARGET",
                    "Transform '" + rule.getId() + "' must declare a 'targetTemplateId'");
            registry.removeTransform(rule.getId());
            return null;
        }
        return rule;
    }

    /** P8-5 natural-spawn family: bounded template spawning without a placed spawner. */
    public com.storynpcs.creator.spawn.NaturalSpawnRule loadNaturalSpawn(
            String yamlContent, String sourceName, ValidationResult result) {
        var rule = loadBoundedDefinition(yamlContent, sourceName, result,
                com.storynpcs.creator.spawn.NaturalSpawnRule.class, "natural spawn",
                com.storynpcs.creator.spawn.NaturalSpawnRule.SCHEMA_VERSION,
                com.storynpcs.creator.spawn.NaturalSpawnRule::getId,
                com.storynpcs.creator.spawn.NaturalSpawnRule::getSchemaVersion,
                registry::getNaturalSpawn, registry::registerNaturalSpawn);
        if (rule != null && rule.getTemplateId() == null) {
            result.addError(sourceName, 1, 1, "NATURALSPAWN_MISSING_TEMPLATE",
                    "Natural-spawn rule '" + rule.getId() + "' must declare a 'templateId'");
            registry.removeNaturalSpawn(rule.getId());
            return null;
        }
        return rule;
    }

    /** Shared bounded-definition load: parse, id/schema checks, duplicate rejection, register. */
    private <T> T loadBoundedDefinition(String yamlContent, String sourceName,
            ValidationResult result, Class<T> type, String family, int expectedSchema,
            java.util.function.Function<T, NamespacedId> idGetter,
            java.util.function.ToIntFunction<T> schemaGetter,
            java.util.function.Function<NamespacedId, java.util.Optional<T>> lookup,
            java.util.function.Consumer<T> register) {
        if (isEmptyOrCommentOnly(yamlContent)) {
            result.addError(sourceName, 1, 1, "SCHEMA_EMPTY_FILE", "File is empty or contains no valid YAML definitions");
            return null;
        }
        try {
            T def = readDefinition(yamlContent, sourceName, result, type);
            if (def == null) return null;
            NamespacedId id = idGetter.apply(def);
            int schemaVersion = schemaGetter.applyAsInt(def);
            if (id == null) {
                result.addError(sourceName, 1, 1, "SCHEMA_MISSING_ID",
                        family.substring(0, 1).toUpperCase() + family.substring(1)
                                + " definition must declare an 'id'");
                return null;
            }
            if (schemaVersion != expectedSchema) {
                result.addError(sourceName, 1, 1, "SCHEMA_VERSION_UNSUPPORTED",
                        family + " schemaVersion " + schemaVersion
                                + " is not supported (expected " + expectedSchema + ")");
                return null;
            }
            if (lookup.apply(id).isPresent()) {
                result.addError(sourceName, 1, 1, "DUPLICATE_DEFINITION_ID",
                        "Duplicate " + family + " ID '" + id + "' is already defined in another file");
                return null;
            }
            register.accept(def);
            return def;
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
            case "transport", "transports" -> "transports";
            case "template", "templates" -> "templates";
            case "spawner", "spawners" -> "spawners";
            case "worldtool", "worldtools" -> "worldtools";
            case "recipe", "recipes" -> "recipes";
            case "scene", "scenes" -> "scenes";
            case "transform", "transforms" -> "transforms";
            case "naturalspawn", "naturalspawns" -> "naturalspawns";
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
                  .forEach(p -> loadFile(p, rootPath, result));
        }

        // Run cross reference validation after all files are registered
        result.merge(CrossReferenceValidator.validate(registry));
        result.merge(new com.storynpcs.domain.quest.QuestDependencyValidator()
                .validate(new java.util.ArrayList<>(registry.getAllQuests())));
        return result;
    }

    /**
     * Definition families recognized by the roadmap but not yet loadable. A file
     * under one of these directories must fail closed with an explicit diagnostic
     * instead of being bound to the wrong domain model.
     */
    /**
     * Definition families recognized by the roadmap but not yet loadable. A file
     * under one of these directories must fail closed with an explicit diagnostic
     * instead of being bound to the wrong domain model. Jobs, companions, and
     * social roles are NPC-attached ({@code job:}, {@code companion:},
     * {@code bard:}/{@code healer:}/{@code postman:} inside npcs/*.yaml), never
     * standalone files — those directory names stay reserved on purpose.
     */
    private static final java.util.Set<String> RESERVED_UNSUPPORTED_FAMILIES = java.util.Set.of(
            "role", "roles", "job", "jobs", "tool", "tools",
            "world", "worlds", "companion", "companions", "trade", "trades",
            "bank", "banks", "follower", "followers",
            "linked_npc", "linked_npcs");

    private void loadFile(Path file, Path rootPath, ValidationResult result) {
        try {
            String content = Files.readString(file);
            Path parent = file.getParent();
            String parentName = parent != null ? parent.getFileName().toString().toLowerCase() : "";
            String fileName = file.getFileName().toString().toLowerCase();
            // Reserved families fail closed at ANY depth below the definitions
            // root — a nested subdirectory cannot launder a reserved family doc
            // into another domain by hiding it one level deeper.
            Path relativeParent = parent != null && parent.startsWith(rootPath)
                    ? rootPath.relativize(parent) : parent;
            if (relativeParent != null) {
                for (Path component : relativeParent) {
                    String dirName = component.getFileName().toString().toLowerCase();
                    if (RESERVED_UNSUPPORTED_FAMILIES.contains(dirName)) {
                        result.addError(file.toString(), 1, 1, "SCHEMA_FAMILY_UNSUPPORTED",
                                "Definition family '" + dirName + "' is recognized but not yet loadable;"
                                        + " remove the file or move it to a supported family directory"
                                        + " (npcs/, dialogues/, factions/, quests/, transports/, templates/, spawners/, worldtools/, recipes/, scenes/, transforms/, naturalspawns/)");
                        return;
                    }
                }
            }
            String type = definitionType(parentName, fileName, content);

            switch (type) {
                case "npcs" -> loadNpc(content, file.toString(), result);
                case "dialogues" -> loadDialogue(content, file.toString(), result);
                case "factions" -> loadFaction(content, file.toString(), result);
                case "quests" -> loadQuest(content, file.toString(), result);
                case "transports" -> loadTransport(content, file.toString(), result);
                case "templates" -> loadTemplate(content, file.toString(), result);
                case "spawners" -> loadSpawner(content, file.toString(), result);
                case "worldtools" -> loadWorldTool(content, file.toString(), result);
                case "recipes" -> loadRecipe(content, file.toString(), result);
                case "scenes" -> loadScene(content, file.toString(), result);
                case "transforms" -> loadTransform(content, file.toString(), result);
                case "naturalspawns" -> loadNaturalSpawn(content, file.toString(), result);
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
        if (parentName.equals("transports") || parentName.equals("transport")
                || fileName.startsWith("transport_")) {
            return "transports";
        }
        if (parentName.equals("templates") || parentName.equals("template")
                || fileName.startsWith("template_")) {
            return "templates";
        }
        if (parentName.equals("spawners") || parentName.equals("spawner")
                || fileName.startsWith("spawner_")) {
            return "spawners";
        }
        if (parentName.equals("worldtools") || parentName.equals("worldtool")
                || fileName.startsWith("worldtool_")) {
            return "worldtools";
        }
        if (parentName.equals("recipes") || parentName.equals("recipe")
                || fileName.startsWith("recipe_")) {
            return "recipes";
        }
        if (parentName.equals("scenes") || fileName.startsWith("scene_")) {
            return "scenes";
        }
        if (parentName.equals("transforms") || parentName.equals("transform")
                || fileName.startsWith("transform_")) {
            return "transforms";
        }
        if (parentName.equals("naturalspawns") || parentName.equals("naturalspawn")
                || fileName.startsWith("naturalspawn_")) {
            return "naturalspawns";
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
