package com.storynpcs.client.render;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcDisplay;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("StoryNpcRenderer Model & Formatting Tests")
class StoryNpcRenderModelTest {

    private NpcDefinition createDefinition(String name, String title, boolean showName, String skinTexture) {
        NpcDefinition def = new NpcDefinition(new NamespacedId("storynpcs", "test_npc"), name);
        NpcDisplay display = def.getDisplay();
        display.setName(name);
        display.setTitle(title);
        display.setShowName(showName);
        if (skinTexture != null) {
            display.setSkinTexture(skinTexture);
        }
        return def;
    }

    @Test
    @DisplayName("Should resolve default Steve texture when definition is absent or skin is empty")
    void testResolveDefaultTexture() {
        ResourceLocation defaultTex = StoryNpcRenderer.resolveTexture(Optional.empty());
        assertEquals(StoryNpcRenderer.DEFAULT_TEXTURE, defaultTex);

        NpcDefinition defBlank = createDefinition("Test", null, true, "   ");
        assertEquals(StoryNpcRenderer.DEFAULT_TEXTURE, StoryNpcRenderer.resolveTexture(Optional.of(defBlank)));

        NpcDefinition defNull = createDefinition("Test", null, true, null);
        defNull.getDisplay().setSkinTexture(null);
        assertEquals(StoryNpcRenderer.DEFAULT_TEXTURE, StoryNpcRenderer.resolveTexture(Optional.of(defNull)));
    }

    @Test
    @DisplayName("Should resolve custom skin texture when specified in NpcDisplay")
    void testResolveCustomTexture() {
        NpcDefinition def = createDefinition("Guard", null, true, "storynpcs:textures/entity/npc/guard.png");
        ResourceLocation loc = StoryNpcRenderer.resolveTexture(Optional.of(def));
        assertEquals("storynpcs", loc.getNamespace());
        assertEquals("textures/entity/npc/guard.png", loc.getPath());
    }

    @Test
    @DisplayName("Should suppress name tag when showName is false or definition is empty")
    void testSuppressNameTag() {
        assertTrue(StoryNpcRenderer.formatNameTag(Optional.empty()).isEmpty());

        NpcDefinition hidden = createDefinition("Silent Bob", null, false, null);
        assertTrue(StoryNpcRenderer.formatNameTag(Optional.of(hidden)).isEmpty());
    }

    @Test
    @DisplayName("Should format name tag with name and optional title bracket")
    void testFormatNameTag() {
        NpcDefinition withTitle = createDefinition("Ronald", "Village Chief", true, null);
        Optional<Component> compWithTitle = StoryNpcRenderer.formatNameTag(Optional.of(withTitle));
        assertTrue(compWithTitle.isPresent());
        assertEquals("Ronald [Village Chief]", compWithTitle.get().getString());

        NpcDefinition withoutTitle = createDefinition("Ronald", "", true, null);
        Optional<Component> compWithout = StoryNpcRenderer.formatNameTag(Optional.of(withoutTitle));
        assertTrue(compWithout.isPresent());
        assertEquals("Ronald", compWithout.get().getString());

        NpcDefinition defaultName = createDefinition(null, "Wanderer", true, null);
        Optional<Component> compDefault = StoryNpcRenderer.formatNameTag(Optional.of(defaultName));
        assertTrue(compDefault.isPresent());
        assertEquals("StoryNPC [Wanderer]", compDefault.get().getString());
    }

    @Test
    @DisplayName("Name mode can hide names or show them only during attack")
    void testNameVisibilityModes() {
        NpcDefinition definition = createDefinition("Guard", "Captain", true, null);
        definition.getDisplay().setShowNameMode(1);
        assertTrue(StoryNpcRenderer.formatNameTag(Optional.of(definition)).isEmpty());

        definition.getDisplay().setShowNameMode(2);
        assertTrue(StoryNpcRenderer.formatNameTag(Optional.of(definition), false).isEmpty());
        assertTrue(StoryNpcRenderer.formatNameTag(Optional.of(definition), true).isPresent());
    }
}
