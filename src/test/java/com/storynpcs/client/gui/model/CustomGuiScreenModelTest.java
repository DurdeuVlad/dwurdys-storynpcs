package com.storynpcs.client.gui.model;

import com.storynpcs.creator.gui.CustomGuiLayout;
import com.storynpcs.domain.common.NamespacedId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Custom-GUI screen model (issue #150): the runtime renderer's headless
 * flatten/hit-test/input surface. The screen renders exactly what this model
 * reports — a wrong coordinate or dropped element is a player-visible bug, so
 * the math is pinned without Minecraft classes.
 */
class CustomGuiScreenModelTest {

    private static CustomGuiLayout layout() {
        var root = new CustomGuiLayout.GuiElement("root", "panel");
        root.setX(4);
        root.setY(6);
        root.setWidth(200);
        root.setHeight(120);

        var label = new CustomGuiLayout.GuiElement("title", "label");
        label.setX(8);
        label.setY(8);
        label.setTextKey("gui.test.title");

        var button = new CustomGuiLayout.GuiElement("go", "button");
        button.setX(10);
        button.setY(20);
        button.setWidth(60);
        button.setHeight(16);

        var input = new CustomGuiLayout.GuiElement("name", "input");
        input.setX(10);
        input.setY(44);
        input.setWidth(100);
        input.setHeight(12);

        var scroll = new CustomGuiLayout.GuiElement("list", "scroll");
        scroll.setX(120);
        scroll.setY(20);
        scroll.setWidth(60);
        scroll.setHeight(80);

        var nested = new CustomGuiLayout.GuiElement("inner", "panel");
        nested.setX(30);
        nested.setY(40);
        var innerBtn = new CustomGuiLayout.GuiElement("sub", "button");
        innerBtn.setX(5);
        innerBtn.setY(5);
        innerBtn.setWidth(20);
        innerBtn.setHeight(10);
        nested.setChildren(List.of(innerBtn));

        root.setChildren(List.of(label, button, input, scroll, nested));

        var layout = new CustomGuiLayout();
        layout.setId(NamespacedId.of("storynpcs:menu"));
        layout.setRoot(root);
        return layout;
    }

    @Test
    void flattenProducesDocumentOrderWithAbsoluteCoordinates() {
        var model = new CustomGuiScreenModel(layout());
        var entries = model.entries();
        assertThat(entries).hasSize(7);
        // Root at authored (4,6); children offset by parent origin.
        var title = entries.stream().filter(e -> e.name().equals("title")).findFirst().orElseThrow();
        assertThat(title.absX()).isEqualTo(12);
        assertThat(title.absY()).isEqualTo(14);
        // Nested button inherits both offsets: 4+30+5 / 6+40+5.
        var sub = entries.stream().filter(e -> e.name().equals("sub")).findFirst().orElseThrow();
        assertThat(sub.absX()).isEqualTo(39);
        assertThat(sub.absY()).isEqualTo(51);
        assertThat(sub.path()).isEqualTo("0/4/0");
        // Parents precede children.
        assertThat(entries.get(0).type()).isEqualTo("panel");
    }

    @Test
    void buttonHitTestUsesLatestDrawnElementAndAbsoluteBounds() {
        var model = new CustomGuiScreenModel(layout());
        // Inside "go": layout coords 10..70 x 20..36 → abs 14..74 x 26..42.
        assertThat(model.buttonAt(20, 30)).isEqualTo("0/1");
        // Inside nested "sub" only.
        assertThat(model.buttonAt(45, 55)).isEqualTo("0/4/0");
        // Misses every button.
        assertThat(model.buttonAt(0, 0)).isNull();
        assertThat(model.buttonAt(500, 500)).isNull();
    }

    @Test
    void inputsCollectAndRejectUnknownPaths() {
        var model = new CustomGuiScreenModel(layout());
        assertThat(model.inputs()).hasSize(1);
        String inputPath = model.inputs().get(0).path();
        model.setInputValue(inputPath, "Dwurdy");
        assertThat(model.inputValues()).containsEntry(inputPath, "Dwurdy");
        // Unknown element path is ignored, not recorded.
        model.setInputValue("0/9", "nope");
        assertThat(model.inputValues()).doesNotContainKey("0/9");
    }

    @Test
    void unsupportedElementsStayVisibleButNotInteractive() {
        var model = new CustomGuiScreenModel(layout());
        var scroll = model.entries().stream()
                .filter(e -> e.type().equals("scroll")).findFirst().orElseThrow();
        assertThat(scroll.supported()).isFalse();
        assertThat(model.buttons()).extracting(CustomGuiScreenModel.RenderEntry::type)
                .containsOnly("button");
    }

    @Test
    void extentCoversTheRenderedFootprint() {
        var model = new CustomGuiScreenModel(layout());
        int[] e = model.extent();
        assertThat(e[0]).isEqualTo(204); // root x+w
        assertThat(e[1]).isEqualTo(126); // root y+h
    }

    @Test
    void emptyAndNullLayoutsFlattenToNothing() {
        assertThat(new CustomGuiScreenModel(null).entries()).isEmpty();
        assertThat(new CustomGuiScreenModel(new CustomGuiLayout()).entries()).isEmpty();
    }
}
