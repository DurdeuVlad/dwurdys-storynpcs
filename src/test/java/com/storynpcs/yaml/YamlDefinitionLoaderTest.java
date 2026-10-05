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
    void shouldRejectExplicitNullCombatSections() {
        for (String section : java.util.List.of("resistances", "immunities")) {
            NamespacedId id = NamespacedId.of("storynpcs:null_" + section);
            String yaml = """
                    id: "%s"
                    stats:
                      %s: null
                    """.formatted(id, section);
            ValidationResult result = ValidationResult.valid();

            assertThat(loader.loadNpc(yaml, section + ".yaml", result)).isNull();
            assertThat(result.getErrors()).singleElement().satisfies(error ->
                    assertThat(error.code()).isEqualTo("YAML_MAPPING_ERROR"));
            assertThat(registry.getNpc(id)).isEmpty();
        }
    }

    @Test
    void explicitTextureSkinSourceSurvivesYamlLoadWhenStalePlayerNameIsPresent() {
        String yaml = """
                id: "storynpcs:yaml_skin_source_order"
                display:
                  name: "YAML Skin Source"
                  skinPlayer: "Alex"
                  skinSource: "TEXTURE"
                """;

        ValidationResult result = ValidationResult.valid();
        NpcDefinition npc = loader.loadNpc(yaml, "yaml_skin_source_order.yaml", result);

        assertThat(result.isValid()).isTrue();
        assertThat(npc).isNotNull();
        assertThat(npc.getDisplay().getSkinPlayer()).isEqualTo("Alex");
        assertThat(npc.getDisplay().getSkinSource())
                .isEqualTo(com.storynpcs.domain.npc.NpcDisplay.SkinSource.TEXTURE);
    }

    @Test
    void shouldAcceptExplicitCurrentSchemaVersion() {
        String yaml = """
                schemaVersion: 1
                id: "storynpcs:versioned_npc"
                display:
                  name: "Versioned NPC"
                """;

        ValidationResult result = ValidationResult.valid();
        NpcDefinition npc = loader.loadNpc(yaml, "versioned_npc.yaml", result);

        assertThat(result.isValid()).isTrue();
        assertThat(npc).isNotNull();
        assertThat(npc.getId()).isEqualTo(NamespacedId.of("storynpcs:versioned_npc"));
    }

    @Test
    void shouldRejectFutureSchemaVersionWithFieldLocation() {
        String yaml = """
                id: "storynpcs:future_npc"
                schemaVersion: 2
                display:
                  name: "Future NPC"
                """;

        ValidationResult result = ValidationResult.valid();
        NpcDefinition npc = loader.loadNpc(yaml, "future_npc.yaml", result);

        assertThat(npc).isNull();
        assertThat(result.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo("SCHEMA_VERSION_UNSUPPORTED");
            assertThat(error.file()).isEqualTo("future_npc.yaml");
            assertThat(error.line()).isEqualTo(2);
            assertThat(error.column()).isEqualTo(1);
            assertThat(error.message()).contains("highest supported version is 1");
        });
        assertThat(registry.getNpc(NamespacedId.of("storynpcs:future_npc"))).isEmpty();
    }

    @Test
    void shouldRejectMalformedSchemaVersion() {
        String yaml = """
                schemaVersion: "one"
                id: "storynpcs:bad_version"
                """;

        ValidationResult result = ValidationResult.valid();
        loader.loadNpc(yaml, "bad_version.yaml", result);

        assertThat(result.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo("SCHEMA_VERSION_INVALID");
            assertThat(error.line()).isEqualTo(1);
            assertThat(error.column()).isEqualTo(1);
        });
    }

    @Test
    void shouldRejectNonIntegerSchemaVersionForms() {
        // Null, float, >int32, and negative envelope values all fail closed.
        for (String versionLiteral : new String[]{"null", "1.5", "9999999999", "-1"}) {
            ValidationResult result = ValidationResult.valid();
            loader.loadNpc("schemaVersion: " + versionLiteral
                    + "\nid: \"storynpcs:v_bad\"\n", "v_bad.yaml", result);
            assertThat(result.hasErrors())
                    .as("schemaVersion " + versionLiteral + " must be rejected")
                    .isTrue();
            assertThat(result.getErrors().get(0).code())
                    .isIn("SCHEMA_VERSION_INVALID", "SCHEMA_VERSION_UNSUPPORTED");
        }

        // Aliased schemaVersion resolves to a non-integral node under Jackson's
        // YAML tree and fails closed rather than silently accepting.
        ValidationResult aliasResult = ValidationResult.valid();
        loader.loadNpc("version: &v 1\nschemaVersion: *v\nid: \"storynpcs:v_alias\"\n",
                "v_alias.yaml", aliasResult);
        assertThat(aliasResult.hasErrors()).isTrue();
        assertThat(aliasResult.getErrors().get(0).code()).isEqualTo("SCHEMA_VERSION_INVALID");
        assertThat(registry.getNpc(NamespacedId.of("storynpcs:v_alias"))).isEmpty();
    }

    @Test
    void shouldRejectUnknownFieldsWithSourceLocation() {
        String yaml = """
                id: "storynpcs:unknown_field"
                display:
                  name: "Unknown Field"
                unexpectedField: true
                """;

        ValidationResult result = ValidationResult.valid();
        loader.loadNpc(yaml, "unknown_field.yaml", result);

        assertThat(result.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo("SCHEMA_UNKNOWN_FIELD");
            assertThat(error.file()).isEqualTo("unknown_field.yaml");
            assertThat(error.line()).isGreaterThan(0);
            assertThat(error.column()).isGreaterThan(0);
            assertThat(error.message()).contains("unexpectedField");
        });
    }

    @Test
    void shouldRejectDuplicateYamlKeysInsteadOfUsingLastValue() {
        String yaml = """
                id: "storynpcs:duplicate_key"
                id: "storynpcs:overwritten_key"
                display:
                  name: "Duplicate Key"
                """;

        ValidationResult result = ValidationResult.valid();
        NpcDefinition npc = loader.loadNpc(yaml, "duplicate_key.yaml", result);

        assertThat(npc).isNull();
        assertThat(result.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo("YAML_PARSE_ERROR");
            assertThat(error.file()).isEqualTo("duplicate_key.yaml");
            assertThat(error.line()).isGreaterThan(0);
        });
        assertThat(registry.getNpc(NamespacedId.of("storynpcs:duplicate_key"))).isEmpty();
        assertThat(registry.getNpc(NamespacedId.of("storynpcs:overwritten_key"))).isEmpty();
    }

    @Test
    void shouldRejectMultipleYamlDocumentsInOneDefinitionFile() {
        String yaml = """
                id: "storynpcs:first_document"
                display:
                  name: "First"
                ---
                id: "storynpcs:second_document"
                display:
                  name: "Second"
                """;

        ValidationResult result = ValidationResult.valid();
        NpcDefinition npc = loader.loadNpc(yaml, "multiple_documents.yaml", result);

        assertThat(npc).isNull();
        assertThat(result.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo("SCHEMA_MULTIPLE_DOCUMENTS");
            assertThat(error.file()).isEqualTo("multiple_documents.yaml");
        });
        assertThat(registry.getNpc(NamespacedId.of("storynpcs:first_document"))).isEmpty();
        assertThat(registry.getNpc(NamespacedId.of("storynpcs:second_document"))).isEmpty();
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

    @Test
    void shouldReportDiagnosticsWhenYamlEmptyOrCommentOnly() {
        String emptyYaml = "# Only comments in this file\n# Another comment\n";
        ValidationResult result = ValidationResult.valid();

        loader.loadNpc(emptyYaml, "empty_npc.yaml", result);
        loader.loadDialogue(emptyYaml, "empty_dialogue.yaml", result);
        loader.loadFaction(emptyYaml, "empty_faction.yaml", result);
        loader.loadQuest(emptyYaml, "empty_quest.yaml", result);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).hasSize(4);
        assertThat(result.getErrors()).allMatch(e -> e.code().equals("SCHEMA_EMPTY_FILE"));
    }

    @Test
    void shouldLoadTraderAndBankerRolesFromYaml() {
        String yaml = """
                id: "storynpcs:market_stall"
                display:
                  name: "Stall Keeper"
                trader:
                  marketName: "Riverside Market"
                  restockIntervalTicks: 12000
                  listings:
                    - offerItemId: "minecraft:bread"
                      offerCount: 3
                      priceItemId: "minecraft:emerald"
                      priceCount: 1
                      maxUses: 10
                      requiredFaction: "storynpcs:river_pirates"
                      requiredFactionPoints: 100
                banker:
                  bankName: "Iron Vault"
                  maxTabs: 3
                  tabUpgradeCost: 64
                """;

        ValidationResult result = ValidationResult.valid();
        NpcDefinition npc = loader.loadNpc(yaml, "market_stall.yaml", result);

        assertThat(result.isValid()).isTrue();
        assertThat(npc).isNotNull();
        assertThat(npc.getTrader()).isNotNull();
        assertThat(npc.getTrader().getMarketName()).isEqualTo("Riverside Market");
        assertThat(npc.getTrader().getRestockIntervalTicks()).isEqualTo(12000);
        assertThat(npc.getTrader().getListings()).hasSize(1);
        var listing = npc.getTrader().getListings().get(0);
        assertThat(listing.getOfferItemId()).isEqualTo("minecraft:bread");
        assertThat(listing.getOfferCount()).isEqualTo(3);
        assertThat(listing.getPriceItemId()).isEqualTo("minecraft:emerald");
        assertThat(listing.getPriceCount()).isEqualTo(1);
        assertThat(listing.getMaxUses()).isEqualTo(10);
        assertThat(listing.getRequiredFaction()).isEqualTo(NamespacedId.of("storynpcs:river_pirates"));
        assertThat(listing.getRequiredFactionPoints()).isEqualTo(100);

        assertThat(npc.getBanker()).isNotNull();
        assertThat(npc.getBanker().getBankName()).isEqualTo("Iron Vault");
        assertThat(npc.getBanker().getMaxTabs()).isEqualTo(3);
        assertThat(npc.getBanker().getTabUpgradeCost()).isEqualTo(64);
    }

    @Test
    void shouldLoadCoherentTwoInputTradeListingFromYaml() {
        String yaml = """
                id: "storynpcs:two_input_stall"
                trader:
                  listings:
                    - offerItemId: "minecraft:bread"
                      offerCount: 2
                      priceItemId: "minecraft:emerald"
                      priceCount: 1
                      secondaryPriceItemId: "minecraft:dirt"
                      secondaryPriceCount: 3
                """;

        ValidationResult result = ValidationResult.valid();
        NpcDefinition npc = loader.loadNpc(yaml, "two_input_stall.yaml", result);

        assertThat(result.isValid()).isTrue();
        assertThat(npc).isNotNull();
        var listing = npc.getTrader().getListings().get(0);
        assertThat(listing.getSecondaryPriceItemId()).isEqualTo("minecraft:dirt");
        assertThat(listing.getSecondaryPriceCount()).isEqualTo(3);
        assertThat(listing.hasTwoInputs()).isTrue();
    }

    @Test
    void shouldRejectIncoherentTwoInputTradeListingAtLoad() {
        // A secondary item id with no count can never be charged — loading it
        // would ship an undercharging contract, so the listing must fail here.
        String yaml = """
                id: "storynpcs:bad_stall"
                trader:
                  listings:
                    - offerItemId: "minecraft:bread"
                      offerCount: 2
                      priceItemId: "minecraft:emerald"
                      priceCount: 1
                      secondaryPriceItemId: "minecraft:dirt"
                """;

        ValidationResult result = ValidationResult.valid();
        NpcDefinition npc = loader.loadNpc(yaml, "bad_stall.yaml", result);

        assertThat(npc).isNull();
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anySatisfy(error ->
                assertThat(error.code()).isEqualTo("TRADE_LISTING_INVALID"));
    }

    @Test
    void shouldRoundTripRolesThroughNpcDefinitionSerde() {
        NpcDefinition npc = new NpcDefinition(NamespacedId.of("storynpcs:banker_npc"), "Vault Keeper");
        var banker = new com.storynpcs.domain.role.banker.BankerRole("Deep Vault");
        banker.setMaxTabs(2);
        npc.setBanker(banker);
        var trader = new com.storynpcs.domain.role.trader.TraderRole("Stall");
        trader.addListing(new com.storynpcs.domain.role.trader.TradeListing(
                "minecraft:apple", 2, "minecraft:emerald", 1));
        npc.setTrader(trader);

        String json = com.storynpcs.domain.npc.NpcDefinitionSerde.toJson(npc);
        var restored = com.storynpcs.domain.npc.NpcDefinitionSerde.fromJson(json);

        assertThat(restored).isPresent();
        assertThat(restored.get().getTrader()).isNotNull();
        assertThat(restored.get().getTrader().getListings()).hasSize(1);
        assertThat(restored.get().getBanker()).isNotNull();
        assertThat(restored.get().getBanker().getBankName()).isEqualTo("Deep Vault");
        assertThat(restored.get().getBanker().getMaxTabs()).isEqualTo(2);
    }

    @Test
    void shouldRejectRecognizedButUnsupportedFamilyDirectories(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path jobs = tempDir.resolve("jobs");
        java.nio.file.Files.createDirectories(jobs);
        java.nio.file.Files.writeString(jobs.resolve("cook.yaml"), """
                schemaVersion: 1
                id: "storynpcs:cook"
                schedule: "day"
                """);
        java.nio.file.Path companions = tempDir.resolve("companions");
        java.nio.file.Files.createDirectories(companions);
        java.nio.file.Files.writeString(companions.resolve("aide.yaml"), """
                schemaVersion: 1
                id: "storynpcs:aide"
                wageAmount: 3
                """);

        ValidationResult result = loader.loadDirectory(tempDir);

        assertThat(result.getErrors())
                .extracting(DiagnosticError::code)
                .containsExactlyInAnyOrder("SCHEMA_FAMILY_UNSUPPORTED", "SCHEMA_FAMILY_UNSUPPORTED");
        assertThat(result.getErrors())
                .allSatisfy(error -> assertThat(error.message()).contains("not yet loadable"));
        assertThat(registry.getAllNpcs()).isEmpty();
    }

    @Test
    void shouldRejectReservedFamilyAtNestedDepth(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        // A reserved-family document hidden one level deeper must still fail
        // closed — nesting cannot launder it into the NPC domain.
        java.nio.file.Path nested = tempDir.resolve("roles").resolve("deep");
        java.nio.file.Files.createDirectories(nested);
        java.nio.file.Files.writeString(nested.resolve("guard.yaml"), """
                schemaVersion: 1
                id: "storynpcs:smuggled"
                display:
                  name: "Smuggled Role"
                """);

        ValidationResult result = loader.loadDirectory(tempDir);

        assertThat(result.getErrors())
                .extracting(DiagnosticError::code)
                .containsExactly("SCHEMA_FAMILY_UNSUPPORTED");
        assertThat(registry.getNpc(NamespacedId.of("storynpcs:smuggled"))).isEmpty();
    }

    @Test
    void shouldRejectMalformedInventoryFieldsInsteadOfDroppingThem() {
        // Unknown inventory keys, unknown equipment slots, malformed stacks,
        // out-of-range chance, and invalid lootMode each fail the document
        // rather than being silently dropped (P2-1 strict boundary).
        ValidationResult keyResult = ValidationResult.valid();
        assertThat(loader.loadNpc("""
                schemaVersion: 1
                id: "storynpcs:bad_inv_key"
                inventory:
                  lootmode: AUTO_PICKUP
                """, "bad_inv_key.yaml", keyResult)).isNull();
        assertThat(keyResult.hasErrors()).isTrue();

        ValidationResult slotResult = ValidationResult.valid();
        assertThat(loader.loadNpc("""
                schemaVersion: 1
                id: "storynpcs:bad_slot"
                inventory:
                  equipment:
                    jetpack: {itemId: "minecraft:stone", count: 1}
                """, "bad_slot.yaml", slotResult)).isNull();
        assertThat(slotResult.hasErrors()).isTrue();
        assertThat(slotResult.formatReport()).contains("jetpack");

        ValidationResult chanceResult = ValidationResult.valid();
        assertThat(loader.loadNpc("""
                schemaVersion: 1
                id: "storynpcs:bad_chance"
                inventory:
                  drops:
                    - item: {itemId: "minecraft:stone", count: 1}
                      chancePercent: 500
                """, "bad_chance.yaml", chanceResult)).isNull();
        assertThat(chanceResult.hasErrors()).isTrue();
        assertThat(chanceResult.formatReport()).contains("chancePercent");

        ValidationResult lootResult = ValidationResult.valid();
        assertThat(loader.loadNpc("""
                schemaVersion: 1
                id: "storynpcs:bad_loot"
                inventory:
                  lootMode: EVERYTHING
                """, "bad_loot.yaml", lootResult)).isNull();
        assertThat(lootResult.hasErrors()).isTrue();
        assertThat(lootResult.formatReport()).contains("lootMode");

        ValidationResult expResult = ValidationResult.valid();
        assertThat(loader.loadNpc("""
                schemaVersion: 1
                id: "storynpcs:bad_exp"
                inventory:
                  minExp: -5
                """, "bad_exp.yaml", expResult)).isNull();
        assertThat(expResult.hasErrors()).isTrue();
        assertThat(expResult.formatReport()).contains("minExp");

        assertThat(registry.getAllNpcs()).isEmpty();
    }

    @Test
    void shouldLoadBundledStarterResourcesWithCurrentSchemaVersion(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        // Every bundled starter resource ships versioned and loads cleanly —
        // the same documents copied into a fresh world's definitions dir.
        java.nio.file.Path npcs = tempDir.resolve("npcs");
        java.nio.file.Path dialogues = tempDir.resolve("dialogues");
        java.nio.file.Files.createDirectories(npcs);
        java.nio.file.Files.createDirectories(dialogues);
        copyBundled("npcs/guard_captain.yaml", npcs.resolve("guard_captain.yaml"));
        copyBundled("npcs/quickstart_demo.yaml", npcs.resolve("quickstart_demo.yaml"));
        copyBundled("dialogues/captain_dialogue.yaml", dialogues.resolve("captain_dialogue.yaml"));
        copyBundled("dialogues/quickstart_dialogue.yaml", dialogues.resolve("quickstart_dialogue.yaml"));
        java.nio.file.Path factions = tempDir.resolve("factions");
        java.nio.file.Path quests = tempDir.resolve("quests");
        java.nio.file.Files.createDirectories(factions);
        java.nio.file.Files.createDirectories(quests);
        copyBundled("factions/town_guard.yaml", factions.resolve("town_guard.yaml"));
        copyBundled("quests/bounty_goblins.yaml", quests.resolve("bounty_goblins.yaml"));

        ValidationResult result = loader.loadDirectory(tempDir);

        assertThat(result.hasErrors()).as("starter resources must load cleanly: %s",
                result.formatReport()).isFalse();
        assertThat(registry.getNpc(NamespacedId.of("storynpcs:guard_captain"))).isPresent();
        assertThat(registry.getNpc(NamespacedId.of("storynpcs:quickstart_demo"))).isPresent();
        assertThat(registry.getDialogue(NamespacedId.of("storynpcs:quickstart_dialogue"))).isPresent();
    }

    private static void copyBundled(String relative, java.nio.file.Path target) throws Exception {
        try (var in = YamlDefinitionLoaderTest.class.getClassLoader()
                .getResourceAsStream("data/storynpcs/definitions/" + relative)) {
            assertThat(in).as("bundled starter resource %s", relative).isNotNull();
            java.nio.file.Files.write(target, in.readAllBytes());
        }
    }
}
