package com.storynpcs.editor;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcAi;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcDefinitionSerde;
import com.storynpcs.domain.npc.NpcDisplay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NpcDisplayScreenModel} — the headless state behind the
 * Display & Render sub-screen (issue #58). The model is the parity contract:
 * every {@link NpcDisplay} field must round-trip through it, and invalid
 * values must be rejected before they can reach the save payload.
 */
class NpcDisplayScreenModelTest {

    private NpcDisplayScreenModel newModel() {
        return new NpcDisplayScreenModel(new NpcDefinition(NamespacedId.of("storynpcs:test_npc"), "Test NPC"));
    }

    @Test
    @DisplayName("model loads every display field from an existing definition")
    void testModelLoadsDefinitionState() {
        NpcDefinition npc = new NpcDefinition(NamespacedId.of("storynpcs:guard"), "Guard");
        NpcDisplay d = new NpcDisplay();
        d.setSkinTexture("storynpcs:textures/entity/knight.png");
        d.setSkinSource(NpcDisplay.SkinSource.PLAYER);
        d.setSkinPlayer("Notch");
        d.setSkinUrl("https://example.com/skin.png");
        d.setCloakTexture("storynpcs:textures/cloak.png");
        d.setGlowTexture("storynpcs:textures/glow.png");
        d.setOverlayGlowing(true);
        d.setShowLayers(false);
        d.setVisibility(2);
        d.setModelType("custom");
        d.setModelId("storynpcs:knight");
        d.setModelSize(20);
        d.setScaleX(1.5f);
        d.setScaleY(0.5f);
        d.setScaleZ(2.0f);
        d.setShowName(false);
        d.setShowNameMode(2);
        d.setTint(0x112233);
        d.setLivingAnimation(false);
        d.setHitboxState(1);
        d.setBossBarMode(1);
        d.setBossBarColor(NpcDisplay.BossBarColor.GREEN);
        npc.setDisplay(d);
        npc.getAi().setAnimationStance(NpcAi.AnimationStance.SITTING);

        NpcDisplayScreenModel m = new NpcDisplayScreenModel(npc);
        assertEquals("storynpcs:textures/entity/knight.png", m.getSkinTexture());
        assertEquals("Notch", m.getSkinPlayer());
        assertEquals("https://example.com/skin.png", m.getSkinUrl());
        assertEquals("storynpcs:textures/cloak.png", m.getCloakTexture());
        assertEquals("storynpcs:textures/glow.png", m.getGlowTexture());
        assertEquals("custom", m.getModelType());
        assertEquals("storynpcs:knight", m.getModelId());
        assertEquals("20", m.getModelSize());
        assertEquals("1.5", m.getScaleX());
        assertEquals("0.5", m.getScaleY());
        assertEquals("2", m.getScaleZ());
        assertEquals("112233", m.getTint());
        // setSkinUrl (non-blank, applied after setSkinPlayer) flips the
        // resolved source to URL — the domain's last-write-wins precedence.
        assertEquals(2, m.getSkinSourceIdx());
        assertEquals(2, m.getVisibilityIdx());
        assertEquals(2, m.getNameModeIdx());
        assertEquals(1, m.getHitboxIdx());
        assertEquals(1, m.getBossBarModeIdx());
        assertEquals(NpcDisplay.BossBarColor.GREEN.ordinal(), m.getBossBarColorIdx());
        assertEquals(NpcAi.AnimationStance.SITTING.ordinal(), m.getStanceIdx());
        assertTrue(m.isOverlayGlowing());
        assertFalse(m.isShowLayers());
        assertFalse(m.isShowName());
        assertFalse(m.isLivingAnimation());
    }

    @Test
    @DisplayName("apply() pushes every field into the domain and survives JSON round-trip")
    void testApplyRoundTrip() {
        NpcDisplayScreenModel m = newModel();
        m.setSkinTexture("storynpcs:textures/entity/mage.png");
        m.setSkinUrl("https://skins.example.com/a.png");
        m.setSkinPlayer("Dream");
        m.setCloakTexture("storynpcs:textures/cloak2.png");
        m.setGlowTexture("storynpcs:textures/glow2.png");
        m.setModelType("custom");
        m.setModelId("storynpcs:mage");
        m.setModelSize("12");
        m.setScaleX("2.5");
        m.setScaleY("1.25");
        m.setScaleZ("0.75");
        m.setTint("FF8040");
        m.cycleSkinSource(1);       // texture -> player
        m.cycleVisibility(1);       // visible -> hidden
        m.cycleNameMode(2);         // always -> while_attacking
        m.cycleHitbox(1);           // normal -> statue
        m.cycleBossBarMode(2);      // hidden -> while_attacking
        m.cycleBossBarColor(5);     // pink -> purple
        m.cycleStance(3);           // normal -> sneaking
        m.toggleOverlayGlowing();
        m.toggleShowLayers();
        m.toggleShowName();
        m.toggleLivingAnimation();

        assertNull(m.apply());
        NpcDisplay d = m.getNpc().getDisplay();
        assertEquals("storynpcs:textures/entity/mage.png", d.getSkinTexture());
        assertEquals(NpcDisplay.SkinSource.PLAYER, d.getSkinSource());
        assertEquals("https://skins.example.com/a.png", d.getSkinUrl());
        assertEquals("Dream", d.getSkinPlayer());
        assertEquals("storynpcs:textures/cloak2.png", d.getCloakTexture());
        assertEquals("storynpcs:textures/glow2.png", d.getGlowTexture());
        assertEquals("custom", d.getModelType());
        assertEquals("storynpcs:mage", d.getModelId());
        assertEquals(12, d.getModelSize());
        assertEquals(2.5f, d.getScaleX());
        assertEquals(1.25f, d.getScaleY());
        assertEquals(0.75f, d.getScaleZ());
        assertEquals(0xFF8040, d.getTint());
        assertEquals(1, d.getVisibility());
        assertEquals(2, d.getShowNameMode());
        assertEquals(1, d.getHitboxState());
        assertEquals(2, d.getBossBarMode());
        assertEquals(NpcDisplay.BossBarColor.PURPLE, d.getBossBarColor());
        assertEquals(NpcAi.AnimationStance.SNEAKING, m.getNpc().getAi().getAnimationStance());
        assertFalse(d.isOverlayGlowing()); // defaulted true, toggled off
        assertFalse(d.isShowLayers()); // defaulted true, toggled off
        assertFalse(d.isShowName());   // defaulted true, toggled off
        assertFalse(d.hasLivingAnimation());

        // The whole-definition save path is JSON — every field must round-trip
        // so the server-side saveNpc validation sees identical state.
        NpcDefinition restored = NpcDefinitionSerde.fromJson(NpcDefinitionSerde.toJson(m.getNpc()))
                .orElseThrow();
        NpcDisplay rd = restored.getDisplay();
        assertEquals(d.getSkinTexture(), rd.getSkinTexture());
        assertEquals(d.getSkinSource(), rd.getSkinSource());
        assertEquals(d.getSkinUrl(), rd.getSkinUrl());
        assertEquals(d.getSkinPlayer(), rd.getSkinPlayer());
        assertEquals(d.getCloakTexture(), rd.getCloakTexture());
        assertEquals(d.getGlowTexture(), rd.getGlowTexture());
        assertEquals(d.getModelType(), rd.getModelType());
        assertEquals(d.getModelId(), rd.getModelId());
        assertEquals(d.getModelSize(), rd.getModelSize());
        assertEquals(d.getScaleX(), rd.getScaleX());
        assertEquals(d.getTint(), rd.getTint());
        assertEquals(d.getVisibility(), rd.getVisibility());
        assertEquals(d.getShowNameMode(), rd.getShowNameMode());
        assertEquals(d.getHitboxState(), rd.getHitboxState());
        assertEquals(d.getBossBarMode(), rd.getBossBarMode());
        assertEquals(d.getBossBarColor(), rd.getBossBarColor());
        assertEquals(m.getNpc().getAi().getAnimationStance(), restored.getAi().getAnimationStance());
    }

    @Test
    @DisplayName("explicit skin-source picker wins over stale url/player field values")
    void testSkinSourcePickerIsAuthoritative() {
        NpcDisplayScreenModel m = newModel();
        // A stale URL left in the field must not silently re-flip the source
        // the user explicitly picked to TEXTURE.
        m.setSkinUrl("https://old.example.com/skin.png");
        m.cycleSkinSource(0); // stays TEXTURE (index 0)
        assertNull(m.apply());
        assertEquals(NpcDisplay.SkinSource.TEXTURE, m.getNpc().getDisplay().getSkinSource());
        assertEquals("https://old.example.com/skin.png", m.getNpc().getDisplay().getSkinUrl());
    }

    @Test
    @DisplayName("non-blank skinUrl still resolves to URL when the picker selects it")
    void testUrlSourceSelection() {
        NpcDisplayScreenModel m = newModel();
        m.setSkinUrl("https://skins.example.com/x.png");
        m.cycleSkinSource(1); // texture -> player
        m.cycleSkinSource(1); // player -> url
        assertNull(m.apply());
        assertEquals(NpcDisplay.SkinSource.URL, m.getNpc().getDisplay().getSkinSource());
    }

    @Test
    @DisplayName("invalid scale/modelSize/tint produce error strings and leave domain untouched")
    void testValidationErrorsBlockApply() {
        NpcDisplayScreenModel m = newModel();
        m.setScaleX("12"); // above the 0.1..8.0 bound
        String err = m.apply();
        assertNotNull(err);
        assertTrue(m.isStatusError());
        // modelSize never reached — display must keep defaults
        assertEquals(5, m.getNpc().getDisplay().getModelSize());

        m = newModel();
        m.setModelSize("abc");
        assertNotNull(m.apply());

        m = newModel();
        m.setTint("not-a-color");
        assertNotNull(m.apply());
        assertEquals(0xFFFFFF, m.getNpc().getDisplay().getTint());
    }

    @Test
    @DisplayName("tint accepts plain hex, #-prefixed hex, and empty")
    void testTintParsing() {
        NpcDisplayScreenModel m = newModel();
        m.setTint("#00FF7F");
        assertNull(m.apply());
        assertEquals(0x00FF7F, m.getNpc().getDisplay().getTint());

        m = newModel();
        m.setTint("");
        assertNull(m.apply());
        assertEquals(0xFFFFFF, m.getNpc().getDisplay().getTint());
    }

    @Test
    @DisplayName("apply() creates missing display/ai blocks instead of failing")
    void testApplyCreatesMissingBlocks() {
        NpcDefinition npc = new NpcDefinition(NamespacedId.of("storynpcs:bare"), "Bare");
        npc.setDisplay(null);
        npc.setAi(null);
        NpcDisplayScreenModel m = new NpcDisplayScreenModel(npc);
        assertNull(m.apply());
        assertNotNull(npc.getDisplay());
        assertNotNull(npc.getAi());
    }

    @Test
    @DisplayName("cycles wrap through every option and back")
    void testCycleWrap() {
        NpcDisplayScreenModel m = newModel();
        int n = NpcDisplayScreenModel.BOSS_BAR_MODES.length;
        for (int i = 0; i < n; i++) m.cycleBossBarMode(1);
        assertEquals(0, m.getBossBarModeIdx());
        m.cycleBossBarMode(-1);
        assertEquals(n - 1, m.getBossBarModeIdx());
    }
}
