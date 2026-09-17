package com.storynpcs.yaml;

import com.storynpcs.domain.common.DiagnosticError;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.faction.FactionStanding;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.quest.Quest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YamlDefinitionLoaderTest {
    private DefinitionRegistry registry;
    private YamlDefinitionLoader loader;

    @BeforeEach
    void setUp() {
        registry = new DefinitionRegistry();
        loader = new YamlDefinitionLoader(registry);
    }

    @Test
    void shouldLoadValidNpcDefinition() {
        String yaml = """
                id: "storynpcs:guard_captain"
                display:
                  name: "Captain Valerie"
                  title: "Town Guard"
                  skinTexture: "storynpcs:textures/entity/captain.png"
                  showName: true
                stats:
                  maxHealth: 50.0
                  attackDamage: 8.0
                  respawnTimeSeconds: 30
                ai:
                  movementType: "WANDERING"
                  walkingRange: 15
                  doorInteract: true
                dialogueId: "storynpcs:captain_dialogue"
                factionId: "storynpcs:town_guard"
                """;

        ValidationResult result = ValidationResult.valid();
        NpcDefinition npc = loader.loadNpc(yaml, "guard_captain.yaml", result);

        assertThat(result.isValid()).isTrue();
        assertThat(npc).isNotNull();
        assertThat(npc.getId()).isEqualTo(NamespacedId.of("storynpcs:guard_captain"));
        assertThat(npc.getDisplay().getName()).isEqualTo("Captain Valerie");
        assertThat(npc.getStats().getMaxHealth()).isEqualTo(50.0);
        assertThat(npc.getAi().getMovementType().name()).isEqualTo("WANDERING");
        assertThat(registry.getNpc(npc.getId())).isPresent();
    }

    @Test
    void shouldLoadValidDialogueGraph() {
        String yaml = """
                id: "storynpcs:tavern_keeper"
                title: "Tavern Keeper Gossip"
                entryNodeId: "welcome"
                nodes:
                  welcome:
                    id: "welcome"
                    text: "What can I pour for you tonight, stranger?"
                    options:
                      - text: "Heard any interesting rumors?"
                        targetNodeId: "rumors"
                      - text: "Just passing through."
                        targetNodeId: "leave"
                  rumors:
                    id: "rumors"
                    text: "They say bandits have taken up camp in the northern ruins."
                    options:
                      - text: "Thanks for the tip."
                        targetNodeId: "welcome"
                  leave:
                    id: "leave"
                    text: "Watch your back out there."
                    options: []
                """;

        ValidationResult result = ValidationResult.valid();
        DialogueGraph graph = loader.loadDialogue(yaml, "tavern_keeper.yaml", result);

        assertThat(result.isValid()).isTrue();
        assertThat(graph).isNotNull();
        assertThat(graph.getId()).isEqualTo(NamespacedId.of("storynpcs:tavern_keeper"));
        assertThat(graph.getNodes()).hasSize(3);
        assertThat(graph.canReach("welcome", "rumors")).isTrue();
        assertThat(graph.canReach("rumors", "welcome")).isTrue(); // cycle
        assertThat(registry.getDialogue(graph.getId())).isPresent();
    }

    @Test
    void shouldLoadValidFactionDefinition() {
        String yaml = """
                id: "storynpcs:town_guard"
                name: "Town Guard"
                defaultPoints: 1000
                hostileThreshold: 400
                friendlyThreshold: 1600
                """;

        ValidationResult result = ValidationResult.valid();
        Faction faction = loader.loadFaction(yaml, "town_guard.yaml", result);

        assertThat(result.isValid()).isTrue();
        assertThat(faction).isNotNull();
        assertThat(faction.getId()).isEqualTo(NamespacedId.of("storynpcs:town_guard"));
        assertThat(faction.getStandingForPoints(200)).isEqualTo(FactionStanding.HOSTILE);
        assertThat(faction.getStandingForPoints(1000)).isEqualTo(FactionStanding.NEUTRAL);
        assertThat(faction.getStandingForPoints(1800)).isEqualTo(FactionStanding.FRIENDLY);
    }

    @Test
    void shouldLoadValidQuestDefinition() {
        String yaml = """
                id: "storynpcs:clear_ruins"
                title: "Clear the Northern Ruins"
                description: "Defeat 5 bandits occupying the ruins."
                category: "combat"
                repeatType: "ONCE"
                objectives:
                  - id: "kill_bandits"
                    type: "KILL_ENTITY"
                    target: "storynpcs:bandit"
                    requiredCount: 5
                rewards:
                  - type: "FACTION_POINTS"
                    target: "storynpcs:town_guard"
                    amount: 250
                """;

        ValidationResult result = ValidationResult.valid();
        Quest quest = loader.loadQuest(yaml, "clear_ruins.yaml", result);

        assertThat(result.isValid()).isTrue();
        assertThat(quest).isNotNull();
        assertThat(quest.getObjectives()).hasSize(1);
        assertThat(quest.getObjectives().get(0).getRequiredCount()).isEqualTo(5);
        assertThat(quest.getRewards().get(0).getAmount()).isEqualTo(250);
    }

    @Test
    void shouldReportDiagnosticsWhenSchemaMissingId() {
        String invalidYaml = """
                display:
                  name: "No ID NPC"
                """;

        ValidationResult result = ValidationResult.valid();
        loader.loadNpc(invalidYaml, "invalid.yaml", result);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).hasSize(1);
        DiagnosticError error = result.getErrors().get(0);
        assertThat(error.code()).isEqualTo("SCHEMA_MISSING_ID");
        assertThat(error.file()).isEqualTo("invalid.yaml");
    }

    @Test
    void shouldReportDiagnosticsWhenYamlMalformed() {
        String malformed = """
                id: "broken"
                  nested_with_wrong_indent: [
                """;

        ValidationResult result = ValidationResult.valid();
        loader.loadNpc(malformed, "broken.yaml", result);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).isNotEmpty();
        DiagnosticError error = result.getErrors().get(0);
        assertThat(error.code()).isEqualTo("YAML_PARSE_ERROR");
        assertThat(error.line()).isGreaterThan(0);
    }
}
