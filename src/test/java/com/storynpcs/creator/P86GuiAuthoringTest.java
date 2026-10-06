package com.storynpcs.creator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.creator.gui.CustomGuiLayout;
import com.storynpcs.creator.gui.ModelPreset;
import com.storynpcs.creator.gui.OverlaySession;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.service.MutationRequest;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.yaml.YamlDefinitionLoader;

/** P8-6: custom GUI layouts, model presets, session-scoped overlays. */
class P86GuiAuthoringTest {

    @TempDir
    Path tempDir;

    private static final String LAYOUT_YAML = """
            schemaVersion: 1
            id: "storynpcs:quest_board"
            root:
              name: board
              type: panel
              x: 0
              y: 0
              width: 176
              height: 166
              children:
                - name: title
                  type: label
                  x: 8
                  y: 6
                  width: 160
                  height: 10
                - name: backdrop
                  type: texture
                  x: 0
                  y: 0
                  width: 176
                  height: 166
                  textureRef: "storynpcs:textures/gui/board.png"
            """;

    private static final String PRESET_YAML = """
            schemaVersion: 1
            id: "storynpcs:ember_guard"
            modelRef: "storynpcs:model/humanoid_guard"
            displayName: "Ember Guard"
            layers:
              - name: torso
                rgb: 16724736
              - name: trim
                rgb: 65535
            textureRefs: ["storynpcs:textures/entity/ember_guard.png"]
            """;

    private StoryNpcsApplicationService service(DefinitionRegistry registry) throws Exception {
        Files.createDirectories(tempDir.resolve("progression"));
        var loader = new YamlDefinitionLoader(registry);
        loader.loadDirectory(tempDir);
        var service = new StoryNpcsApplicationService(
                registry, new ProgressionRepository(tempDir.resolve("progression")),
                new EventPublisher());
        service.setLoader(loader);
        return service;
    }

    // ── layout schema + loader ───────────────────────────────────────────────

    @Test
    void layoutYamlLoadsBoundedTree() {
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        var result = ValidationResult.valid();
        assertThat(loader.loadGuiLayout(LAYOUT_YAML, "guilayouts/board.yaml", result)).isNotNull();
        assertThat(result.getErrors()).isEmpty();
        var layout = registry.getGuiLayout(NamespacedId.of("storynpcs:quest_board")).orElseThrow();
        assertThat(layout.getRoot().getChildren()).hasSize(2);
    }

    @Test
    void layoutViolationsFailWithElementPathDiagnostics() {
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        var result = ValidationResult.valid();
        // texture element without textureRef + unknown type + too many children
        var bad = LAYOUT_YAML
                .replace("textureRef: \"storynpcs:textures/gui/board.png\"\n", "")
                .replace("type: label", "type: laser_cannon");
        assertThat(loader.loadGuiLayout(bad, "guilayouts/bad.yaml", result)).isNull();
        assertThat(result.getErrors()).extracting(e -> e.code())
                .contains("GUI_UNKNOWN_ELEMENT_TYPE", "GUI_TEXTURE_REF_REQUIRED");
        assertThat(registry.getAllGuiLayouts()).isEmpty();
    }

    @Test
    void layoutDepthAndFanoutCapsReject() {
        var deep = new CustomGuiLayout();
        var el = new CustomGuiLayout.GuiElement("r", "panel");
        var cursor = el;
        for (int i = 0; i < CustomGuiLayout.MAX_DEPTH + 1; i++) {
            var child = new CustomGuiLayout.GuiElement("c" + i, "panel");
            cursor.setChildren(List.of(child));
            cursor = child;
        }
        deep.setId(NamespacedId.of("storynpcs:deep"));
        deep.setRoot(el);
        assertThat(deep.validate().getErrors()).extracting(e -> e.code())
                .contains("GUI_TOO_DEEP");

        var wide = new CustomGuiLayout();
        var root = new CustomGuiLayout.GuiElement("r", "panel");
        var kids = new java.util.ArrayList<CustomGuiLayout.GuiElement>();
        for (int i = 0; i < CustomGuiLayout.MAX_CHILDREN + 1; i++) {
            kids.add(new CustomGuiLayout.GuiElement("k" + i, "label"));
        }
        root.setChildren(kids);
        wide.setId(NamespacedId.of("storynpcs:wide"));
        wide.setRoot(root);
        assertThat(wide.validate().getErrors()).extracting(e -> e.code())
                .contains("GUI_TOO_MANY_CHILDREN");
    }

    // ── preset schema + loader ───────────────────────────────────────────────

    @Test
    void presetYamlLoadsWithLayers() {
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        var result = ValidationResult.valid();
        assertThat(loader.loadModelPreset(PRESET_YAML, "presets/ember.yaml", result)).isNotNull();
        var preset = registry.getModelPreset(NamespacedId.of("storynpcs:ember_guard")).orElseThrow();
        assertThat(preset.getLayers()).hasSize(2);
        assertThat(preset.getLayers().get(0).getRgb()).isEqualTo(16724736);
    }

    @Test
    void presetViolationsFailClosed() {
        var preset = new ModelPreset();
        preset.setId(NamespacedId.of("storynpcs:p"));
        // missing modelRef + dup layer + over-cap
        var layers = new java.util.ArrayList<ModelPreset.ColorLayer>();
        for (int i = 0; i < ModelPreset.MAX_LAYERS + 1; i++) {
            layers.add(new ModelPreset.ColorLayer("dup", i));
        }
        preset.setLayers(layers);
        assertThat(preset.validate().getErrors()).extracting(e -> e.code())
                .contains("PRESET_NO_MODEL", "PRESET_TOO_MANY_LAYERS", "PRESET_DUPLICATE_LAYER");
    }

    // ── canonical ops ────────────────────────────────────────────────────────

    @Test
    void canonicalLayoutAndPresetRoundTrip() throws Exception {
        var registry = new DefinitionRegistry();
        var service = service(registry);

        var layout = new CustomGuiLayout();
        layout.setId(NamespacedId.of("storynpcs:quest_board"));
        var root = new CustomGuiLayout.GuiElement("board", "panel");
        root.setWidth(176);
        root.setHeight(166);
        layout.setRoot(root);
        assertThat(service.saveGuiLayout(layout).getErrors()).isEmpty();
        assertThat(registry.getGuiLayout(layout.getId())).isPresent();
        assertThat(Files.exists(tempDir.resolve("guilayouts"))).isTrue();

        var preset = new ModelPreset();
        preset.setId(NamespacedId.of("storynpcs:ember_guard"));
        preset.setModelRef(NamespacedId.of("storynpcs:model/humanoid_guard"));
        preset.setLayers(List.of(new ModelPreset.ColorLayer("torso", 0xFF2400)));
        assertThat(service.saveModelPreset(preset).getErrors()).isEmpty();
        assertThat(Files.exists(tempDir.resolve("presets"))).isTrue();

        var deleteLayout = service.deleteGuiLayout(new MutationRequest(
                "guilayout.delete", "command", "guilayout.delete", layout.getId(),
                service.currentRevision("guilayout", layout.getId()), UUID.randomUUID()));
        assertThat(deleteLayout.applied()).isTrue();
        assertThat(registry.getGuiLayout(layout.getId())).isEmpty();

        var deletePreset = service.deleteModelPreset(new MutationRequest(
                "modelpreset.delete", "command", "modelpreset.delete", preset.getId(),
                service.currentRevision("modelpreset", preset.getId()), UUID.randomUUID()));
        assertThat(deletePreset.applied()).isTrue();
    }

    @Test
    void malformedLayoutRejectsBeforeWrite() throws Exception {
        var registry = new DefinitionRegistry();
        var service = service(registry);
        var layout = new CustomGuiLayout();
        layout.setId(NamespacedId.of("storynpcs:broken"));
        layout.setRoot(new CustomGuiLayout.GuiElement("r", "not_a_type"));
        var result = service.saveGuiLayout(layout);
        assertThat(result.getErrors()).extracting(e -> e.code())
                .contains("GUI_UNKNOWN_ELEMENT_TYPE");
        assertThat(Files.exists(tempDir.resolve("guilayouts"))).isFalse();
        assertThat(registry.getAllGuiLayouts()).isEmpty();
    }

    @Test
    void duplicateLayoutIdsFailDeterministically() {
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        var result = ValidationResult.valid();
        assertThat(loader.loadGuiLayout(LAYOUT_YAML, "guilayouts/a.yaml", result)).isNotNull();
        assertThat(loader.loadGuiLayout(LAYOUT_YAML, "guilayouts/b.yaml", result)).isNull();
        assertThat(result.getErrors()).extracting(e -> e.code())
                .contains("DUPLICATE_DEFINITION_ID");
    }

    // ── overlay sessions ─────────────────────────────────────────────────────

    @Test
    void overlaysBoundPerSessionAndExpireDeterministically() {
        var session = new OverlaySession();
        var player = UUID.randomUUID();
        session.openSession(player, player);
        // Session cap is enforced — the 9th show returns null.
        for (int i = 0; i < OverlaySession.MAX_OVERLAYS_PER_SESSION; i++) {
            assertThat(session.show(player, "el" + i, 100, 0)).isNotNull();
        }
        assertThat(session.show(player, "overflow", 100, 0)).isNull();
        // Expiry prunes on read — active(50) keeps all 8 (expiry 100), active(100) none.
        assertThat(session.active(player, 50)).hasSize(OverlaySession.MAX_OVERLAYS_PER_SESSION);
        assertThat(session.active(player, 100)).isEmpty();
    }

    @Test
    void sessionCloseDropsEverythingAndClearAllIsTotal() {
        var session = new OverlaySession();
        var p1 = UUID.randomUUID();
        var p2 = UUID.randomUUID();
        session.openSession(p1, p1);
        session.openSession(p2, p2);
        session.show(p1, "a", 1000, 0);
        session.show(p2, "b", 1000, 0);
        assertThat(session.closeSession(p1)).isEqualTo(1);
        assertThat(session.active(p1, 0)).isEmpty();
        assertThat(session.active(p2, 0)).hasSize(1); // p2 unaffected
        session.clearAll();
        assertThat(session.sessionIds()).isEmpty();
        assertThat(session.active(p2, 0)).isEmpty();
    }

    @Test
    void overlayRejectsZeroDurationAndUnknownSession() {
        var session = new OverlaySession();
        var player = UUID.randomUUID();
        assertThat(session.show(player, "el", 100, 0)).isNull(); // no session
        session.openSession(player, player);
        assertThat(session.show(player, "el", 0, 0)).isNull();  // zero duration
        assertThat(session.show(player, "el", -5, 0)).isNull(); // negative duration
    }
}
