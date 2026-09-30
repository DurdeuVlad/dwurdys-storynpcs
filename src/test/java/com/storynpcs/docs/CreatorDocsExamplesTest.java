package com.storynpcs.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.yaml.YamlDefinitionLoader;

/**
 * P10-3 gate: every example shipped in docs/creator/examples must parse through
 * the production YAML loader — docs can never drift from the real schema.
 */
class CreatorDocsExamplesTest {

    private static final Path EXAMPLES = Path.of("docs/creator/examples");

    private static String read(String file) throws Exception {
        return Files.readString(EXAMPLES.resolve(file));
    }

    @Test
    void examplesDirectoryExistsWithContent() throws Exception {
        try (var stream = Files.list(EXAMPLES)) {
            assertThat(stream.filter(p -> p.toString().endsWith(".yaml")).count())
                    .isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void guardNpcExampleParses() throws Exception {
        var loader = new YamlDefinitionLoader(new com.storynpcs.yaml.DefinitionRegistry());
        ValidationResult result = new ValidationResult();
        var npc = loader.loadNpc(read("guard_npc.yaml"), "guard_npc.yaml", result);
        assertThat(result.hasErrors()).as(result.formatReport()).isFalse();
        assertThat(npc).isNotNull();
        assertThat(npc.getInventory()).isNotNull();
        assertThat(npc.getInventory().getDrops()).hasSize(21);
    }

    @Test
    void dailyQuestExampleParses() throws Exception {
        var loader = new YamlDefinitionLoader(new com.storynpcs.yaml.DefinitionRegistry());
        ValidationResult result = new ValidationResult();
        var quest = loader.loadQuest(read("shopkeeper_quest.yaml"), "shopkeeper_quest.yaml", result);
        assertThat(result.hasErrors()).as(result.formatReport()).isFalse();
        assertThat(quest).isNotNull();
        assertThat(quest.getRepeatType().name()).isEqualTo("DAILY");
    }

    @Test
    void multiRoleNpcExampleParses() throws Exception {
        var loader = new YamlDefinitionLoader(new com.storynpcs.yaml.DefinitionRegistry());
        ValidationResult result = new ValidationResult();
        var npc = loader.loadNpc(read("merchant_guard.yaml"), "merchant_guard.yaml", result);
        assertThat(result.hasErrors()).as(result.formatReport()).isFalse();
        assertThat(npc).isNotNull();
        // Multi-role: combat AI + trader market in one definition.
        assertThat(npc.getTrader()).isNotNull();
        assertThat(npc.getTrader().getListings()).hasSize(2);
        assertThat(npc.getTrader().getListings().get(1).getSecondaryPriceItemId())
                .isEqualTo("minecraft:flint");
    }

    @Test
    void guardDialogueExampleParsesAndValidates() throws Exception {
        var loader = new YamlDefinitionLoader(new com.storynpcs.yaml.DefinitionRegistry());
        ValidationResult result = new ValidationResult();
        var graph = loader.loadDialogue(read("guard_dialogue.yaml"), "guard_dialogue.yaml", result);
        assertThat(result.hasErrors()).as(result.formatReport()).isFalse();
        assertThat(graph).isNotNull();
        // Full graph diagnostics: reachable, no dangling edges, no cycles.
        var validation = new com.storynpcs.domain.dialogue.DialogueGraphValidator().validate(graph);
        assertThat(validation.hasErrors()).as(validation.formatReport()).isFalse();
    }
}
