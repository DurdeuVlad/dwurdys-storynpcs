package com.storynpcs.creator.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.api.event.RecipesLoadedEvent;
import com.storynpcs.api.event.StoryNpcsEvent;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.service.MutationRequest;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.yaml.YamlDefinitionLoader;

/** P8-4: recipe schema, loader, matcher, canonical ops, load events. */
class P84RecipeTest {

    @TempDir
    Path tempDir;

    private static final String RECIPE_YAML = """
            schemaVersion: 1
            id: "storynpcs:bronze_blade"
            groupId: "storynpcs:smithing"
            shapeless: false
            grid:
              - ""
              - "storynpcs:bronze_ingot"
              - ""
              - ""
              - "storynpcs:bronze_ingot"
              - ""
              - ""
              - "minecraft:stick"
              - ""
            outputItemId: "storynpcs:bronze_blade"
            outputCount: 1
            """;

    private CarpentryRecipe sampleRecipe() {
        var recipe = new CarpentryRecipe();
        recipe.setId(NamespacedId.of("storynpcs:bronze_blade"));
        recipe.setGroupId(NamespacedId.of("storynpcs:smithing"));
        recipe.setGrid(List.of("", "storynpcs:bronze_ingot", "",
                "", "storynpcs:bronze_ingot", "", "", "minecraft:stick", ""));
        recipe.setOutputItemId("storynpcs:bronze_blade");
        recipe.setOutputCount(1);
        return recipe;
    }

    private StoryNpcsApplicationService service(DefinitionRegistry registry,
            YamlDefinitionLoader loader, EventPublisher events) throws Exception {
        Files.createDirectories(tempDir.resolve("progression"));
        loader.loadDirectory(tempDir); // sets the durable root so saves write files
        var service = new StoryNpcsApplicationService(
                registry, new ProgressionRepository(tempDir.resolve("progression")), events);
        service.setLoader(loader);
        return service;
    }

    // ── model bounds ─────────────────────────────────────────────────────────

    @Test
    void gridMustBeExactlyNineSlots() {
        var recipe = sampleRecipe();
        assertThatThrownBy(() -> recipe.setGrid(List.of("a:b", "c:d")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("9");
        assertThatThrownBy(() -> recipe.setOutputCount(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recipe.setOutputCount(65))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validationFlagsBadSlotBadOutputAndEmptyGrid() {
        var bad = sampleRecipe();
        bad.setGrid(List.of("", "notnamespaced", "", "", "", "", "", "", ""));
        var result = bad.validate();
        assertThat(result.getErrors()).extracting(e -> e.code()).contains("RECIPE_BAD_SLOT");

        var empty = sampleRecipe();
        empty.setGrid(List.of("", "", "", "", "", "", "", "", ""));
        assertThat(empty.validate().getErrors()).extracting(e -> e.code())
                .contains("RECIPE_EMPTY");

        var noOutput = sampleRecipe();
        noOutput.setOutputItemId("junk");
        assertThat(noOutput.validate().getErrors()).extracting(e -> e.code())
                .contains("RECIPE_BAD_OUTPUT");
    }

    // ── loader ───────────────────────────────────────────────────────────────

    @Test
    void recipeYamlLoadsAndRegisters() {
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        var result = ValidationResult.valid();
        assertThat(loader.loadRecipe(RECIPE_YAML, "recipes/bronze_blade.yaml", result)).isNotNull();
        assertThat(result.getErrors()).isEmpty();
        var recipe = registry.getRecipe(NamespacedId.of("storynpcs:bronze_blade")).orElseThrow();
        assertThat(recipe.getGroupId().toString()).isEqualTo("storynpcs:smithing");
        assertThat(recipe.getGrid()).hasSize(9);
        assertThat(registry.getRecipesInGroup(NamespacedId.of("storynpcs:smithing"))).hasSize(1);
    }

    @Test
    void duplicateRecipeIdFailsDeterministically() {
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        assertThat(loader.loadRecipe(RECIPE_YAML, "a.yaml", ValidationResult.valid())).isNotNull();
        var result = ValidationResult.valid();
        assertThat(loader.loadRecipe(RECIPE_YAML, "b.yaml", result)).isNull();
        assertThat(result.getErrors()).extracting(e -> e.code())
                .contains("DUPLICATE_DEFINITION_ID");
    }

    @Test
    void malformedRecipeCannotPartiallyRegister() {
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        var result = ValidationResult.valid();
        assertThat(loader.loadRecipe("""
                schemaVersion: 1
                id: "storynpcs:bad"
                groupId: "storynpcs:g"
                grid: ["", "", "", "", "", "", "", "", ""]
                outputItemId: "minecraft:stick"
                """, "recipes/bad.yaml", result)).isNull();
        assertThat(result.getErrors()).extracting(e -> e.code()).contains("RECIPE_EMPTY");
        assertThat(registry.getRecipe(NamespacedId.of("storynpcs:bad"))).isEmpty();
        assertThat(registry.getRecipesInGroup(NamespacedId.of("storynpcs:g"))).isEmpty();
    }

    @Test
    void unsupportedFieldsFailClosedAtLoad() {
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        var result = ValidationResult.valid();
        assertThat(loader.loadRecipe(RECIPE_YAML + "\nignoredTargetField: true\n",
                "recipes/x.yaml", result)).isNull();
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(registry.getAllRecipes()).isEmpty();
    }

    // ── matcher ──────────────────────────────────────────────────────────────

    @Test
    void shapedRecipeMatchesExactGridOnly() {
        var registry = new DefinitionRegistry();
        registry.registerRecipe(sampleRecipe());
        var input = List.of("", "storynpcs:bronze_ingot", "",
                "", "storynpcs:bronze_ingot", "", "", "minecraft:stick", "");
        var hit = CarpentryBenchMatcher.match(registry::getRecipesInGroup,
                NamespacedId.of("storynpcs:smithing"), input);
        assertThat(hit.match()).isNotNull();
        assertThat(hit.match().outputItemId()).isEqualTo("storynpcs:bronze_blade");

        // Rotated/shifted grids must NOT match a shaped recipe.
        var shifted = List.of("storynpcs:bronze_ingot", "", "",
                "storynpcs:bronze_ingot", "", "", "minecraft:stick", "", "");
        assertThat(CarpentryBenchMatcher.match(registry::getRecipesInGroup,
                NamespacedId.of("storynpcs:smithing"), shifted).match()).isNull();
    }

    @Test
    void shapelessRecipeMatchesAnyArrangementAsMultiset() {
        var shapeless = new CarpentryRecipe();
        shapeless.setId(NamespacedId.of("storynpcs:mixed_alloy"));
        shapeless.setGroupId(NamespacedId.of("storynpcs:smithing"));
        shapeless.setShapeless(true);
        shapeless.setGrid(List.of("storynpcs:copper", "", "",
                "", "storynpcs:tin", "", "", "", ""));
        shapeless.setOutputItemId("storynpcs:bronze_ingot");
        var registry = new DefinitionRegistry();
        registry.registerRecipe(shapeless);

        var rearranged = List.of("", "", "storynpcs:tin",
                "", "", "", "storynpcs:copper", "", "");
        var hit = CarpentryBenchMatcher.match(registry::getRecipesInGroup,
                NamespacedId.of("storynpcs:smithing"), rearranged);
        assertThat(hit.match()).isNotNull();
        assertThat(hit.match().recipe().getId().toString()).isEqualTo("storynpcs:mixed_alloy");

        // Extra input breaks the multiset.
        var extra = List.of("storynpcs:copper", "storynpcs:tin", "minecraft:dirt",
                "", "", "", "", "", "");
        assertThat(CarpentryBenchMatcher.match(registry::getRecipesInGroup,
                NamespacedId.of("storynpcs:smithing"), extra).match()).isNull();
    }

    @Test
    void ambiguousMatchesAreReportedNotSilentlyPicked() {
        var a = sampleRecipe();
        var b = sampleRecipe();
        b.setId(NamespacedId.of("storynpcs:bronze_blade_copy"));
        var registry = new DefinitionRegistry();
        registry.registerRecipe(b); // register out of order — sorted winner is still bronze_blade
        registry.registerRecipe(a);
        var input = List.of("", "storynpcs:bronze_ingot", "",
                "", "storynpcs:bronze_ingot", "", "", "minecraft:stick", "");
        var result = CarpentryBenchMatcher.match(registry::getRecipesInGroup,
                NamespacedId.of("storynpcs:smithing"), input);
        assertThat(result.ambiguous()).isTrue();
        assertThat(result.allMatches()).containsExactly(
                NamespacedId.of("storynpcs:bronze_blade"),
                NamespacedId.of("storynpcs:bronze_blade_copy"));
        assertThat(result.match().recipe().getId().toString())
                .isEqualTo("storynpcs:bronze_blade");
    }

    @Test
    void matchRejectsWrongSizedGrid() {
        assertThatThrownBy(() -> CarpentryBenchMatcher.match(
                g -> List.of(), NamespacedId.of("storynpcs:g"), List.of("a:b")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── canonical ops + events ───────────────────────────────────────────────

    @Test
    void canonicalSaveAndDeletePublishRecipesLoadedEvent() throws Exception {
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        var seen = new CopyOnWriteArrayList<StoryNpcsEvent>();
        var events = new EventPublisher();
        events.register(seen::add);
        var service = service(registry, loader, events);

        var saveResult = service.saveRecipe(sampleRecipe());
        assertThat(saveResult.getErrors()).isEmpty();
        assertThat(registry.getRecipe(NamespacedId.of("storynpcs:bronze_blade"))).isPresent();
        // The definition file exists — file-first durable write.
        assertThat(Files.exists(tempDir.resolve("recipes"))).isTrue();
        assertThat(seen).filteredOn(e -> e instanceof RecipesLoadedEvent r && r.origin().equals("recipe.replace"))
                .hasSize(1);

        var id = NamespacedId.of("storynpcs:bronze_blade");
        var delete = service.deleteRecipe(new MutationRequest(
                "recipe.delete", "command", "recipe.delete", id,
                service.currentRevision("recipe", id), UUID.randomUUID()));
        assertThat(delete.applied()).isTrue();
        assertThat(registry.getRecipe(id)).isEmpty();
        assertThat(seen).filteredOn(e -> e instanceof RecipesLoadedEvent r && r.origin().equals("recipe.delete"))
                .hasSize(1);
    }

    @Test
    void malformedCanonicalSaveIsRejectedBeforeAnyWrite() throws Exception {
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        var service = service(registry, loader, new EventPublisher());

        var bad = sampleRecipe();
        bad.setGrid(List.of("", "", "", "", "", "", "", "", ""));
        var result = service.saveRecipe(bad);
        assertThat(result.getErrors()).extracting(e -> e.code()).contains("RECIPE_EMPTY");
        assertThat(registry.getRecipe(bad.getId())).isEmpty();
        assertThat(Files.exists(tempDir.resolve("recipes"))).isFalse();
    }

    @Test
    void duplicateIdCanonicalSaveIsDeterministicReplace() throws Exception {
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        var service = service(registry, loader, new EventPublisher());

        assertThat(service.saveRecipe(sampleRecipe()).getErrors()).isEmpty();
        var v2 = sampleRecipe();
        v2.setOutputCount(2);
        assertThat(service.saveRecipe(v2).getErrors()).isEmpty();
        var stored = registry.getRecipe(NamespacedId.of("storynpcs:bronze_blade")).orElseThrow();
        assertThat(stored.getOutputCount()).isEqualTo(2);
        assertThat(registry.getAllRecipes()).hasSize(1);
    }
}
