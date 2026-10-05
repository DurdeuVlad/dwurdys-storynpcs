package com.storynpcs.domain.npc;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #58 — entity variants, MPM cosmetic parts, and emote lifecycle:
 * wire parsing, validation bounds, serde round-trip, projection resolution,
 * and the server emote state machine.
 */
class NpcVariantPartsEmoteTest {

    @Test
    @DisplayName("all nine target-compatible variants parse from wire names and aliases")
    void variantWireRoundTrip() {
        assertEquals(NpcVariant.HUMANOID, NpcVariant.fromWire("humanoid"));
        assertEquals(NpcVariant.HUMANOID, NpcVariant.fromWire("player"));
        assertEquals(NpcVariant.HUMANOID, NpcVariant.fromWire(null));
        assertEquals(NpcVariant.ALEX, NpcVariant.fromWire("Alex"));
        assertEquals(NpcVariant.CLASSIC_64X32, NpcVariant.fromWire("classic-64x32"));
        assertEquals(NpcVariant.CLASSIC_64X32, NpcVariant.fromWire("64x32"));
        assertEquals(NpcVariant.GOLEM, NpcVariant.fromWire("golem"));
        assertEquals(NpcVariant.GOLEM, NpcVariant.fromWire("iron_golem"));
        assertEquals(NpcVariant.FLYING, NpcVariant.fromWire("flying"));
        assertEquals(NpcVariant.DRAGON, NpcVariant.fromWire("ender_dragon"));
        assertEquals(NpcVariant.SLIME, NpcVariant.fromWire("slime"));
        assertEquals(NpcVariant.CRYSTAL, NpcVariant.fromWire("crystal"));
        assertEquals(NpcVariant.PONY, NpcVariant.fromWire("pony"));
        assertThrows(IllegalArgumentException.class, () -> NpcVariant.fromWire("wither"));
        assertEquals(9, NpcVariant.values().length);
    }

    @Test
    @DisplayName("variant selection persists through JSON round-trip")
    void variantSerde() {
        NpcDefinition def = new NpcDefinition(NamespacedId.of("storynpcs", "dragon_guard"), "Dragon");
        def.getDisplay().setVariant(NpcVariant.DRAGON);
        NpcDefinition restored = NpcDefinitionSerde.fromJson(NpcDefinitionSerde.toJson(def)).orElseThrow();
        assertEquals(NpcVariant.DRAGON, restored.getDisplay().getVariant());
    }

    @Test
    @DisplayName("variant hitbox uses variant base dims before modelSize/scale factors")
    void variantHitboxProjection() {
        NpcDisplay display = new NpcDisplay();
        display.setVariant(NpcVariant.GOLEM);
        var projection = DisplayProjectionResolver.resolve(display);
        // golem base 1.4 x 2.7, eyeRatio 0.85; modelSize 5 → scale 1.0
        assertEquals(1.4f, projection.hitbox().width(), 1e-6);
        assertEquals(2.7f, projection.hitbox().height(), 1e-6);
        assertEquals(2.7f * 0.85f, projection.hitbox().eyeHeight(), 1e-6);
    }

    @Test
    @DisplayName("cosmetic part validates type range, color range, and required part name")
    void cosmeticPartValidation() {
        var tail = new NpcCosmeticPart(NpcBodyPart.TAIL, 2, 0xAA3355,
                NpcCosmeticPart.PartBehavior.ANIMATED);
        assertEquals(2, tail.type());
        assertEquals(0xAA3355, tail.color());
        assertThrows(IllegalArgumentException.class,
                () -> new NpcCosmeticPart(NpcBodyPart.FIN, 5, 0, null));     // fin max 2
        assertThrows(IllegalArgumentException.class,
                () -> new NpcCosmeticPart(NpcBodyPart.TAIL, -1, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new NpcCosmeticPart(NpcBodyPart.EARS, 1, 0x1000000, null));
        assertThrows(IllegalArgumentException.class,
                () -> new NpcCosmeticPart(null, 0, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> NpcCosmeticPart.PartBehavior.fromWire("spin"));
        assertEquals(NpcCosmeticPart.PartBehavior.FOLLOW_HEAD,
                NpcCosmeticPart.PartBehavior.fromWire("follow-head"));
    }

    @Test
    @DisplayName("parts map rejects key/spec mismatches and round-trips")
    void partsMapValidation() {
        NpcDisplay display = new NpcDisplay();
        var spec = new NpcCosmeticPart(NpcBodyPart.EARS, 1, 0xFF0000,
                NpcCosmeticPart.PartBehavior.NONE);
        display.setPart(spec);
        assertEquals(1, display.getParts().size());
        var wrong = new NpcCosmeticPart(NpcBodyPart.TAIL, 0, 0, null);
        var mismatch = new java.util.EnumMap<NpcBodyPart, NpcCosmeticPart>(NpcBodyPart.class);
        mismatch.put(NpcBodyPart.EARS, wrong); // key ears, spec tail
        assertThrows(IllegalArgumentException.class, () -> display.setParts(mismatch));
        display.clearPart(NpcBodyPart.EARS);
        assertTrue(display.getParts().isEmpty());
    }

    @Test
    @DisplayName("parts persist through JSON and reach the projection")
    void partsSerdeAndProjection() {
        NpcDefinition def = new NpcDefinition(NamespacedId.of("storynpcs", "fancy"), "Fancy");
        def.getDisplay().setPart(new NpcCosmeticPart(NpcBodyPart.WINGS, 3, 0x2244FF,
                NpcCosmeticPart.PartBehavior.ANIMATED));
        NpcDefinition restored = NpcDefinitionSerde.fromJson(NpcDefinitionSerde.toJson(def)).orElseThrow();
        var parts = restored.getDisplay().getParts();
        assertEquals(1, parts.size());
        assertEquals(NpcBodyPart.WINGS, parts.get(NpcBodyPart.WINGS).part());
        assertEquals(3, parts.get(NpcBodyPart.WINGS).type());
        assertEquals(0x2244FF, parts.get(NpcBodyPart.WINGS).color());
        var projection = DisplayProjectionResolver.resolve(restored.getDisplay());
        assertEquals(1, projection.parts().size());
    }

    @Test
    @DisplayName("parts on non-humanoid variants emit PARTS_VARIANT_INCOMPATIBLE")
    void partsVariantDiagnostic() {
        NpcDisplay display = new NpcDisplay();
        display.setVariant(NpcVariant.DRAGON);
        display.setPart(new NpcCosmeticPart(NpcBodyPart.HORNS, 0, 0xFFAA00, null));
        var diagnostics = ValidationResult.valid();
        var projection = DisplayProjectionResolver.resolve(display, diagnostics).projection();
        assertTrue(diagnostics.getErrors().stream()
                .anyMatch(e -> e.code().equals("PARTS_VARIANT_INCOMPATIBLE")));
        // Authored data is preserved in the projection despite the diagnostic.
        assertEquals(1, projection.parts().size());
    }

    @Test
    @DisplayName("emote lifecycle: start, tick, interrupt, expiry, clamps")
    void emoteStateMachine() {
        NpcEmoteState state = new NpcEmoteState();
        assertFalse(state.isActive());
        assertEquals(NpcEmote.NONE, state.current());

        assertTrue(state.start(NpcEmote.WAVE, 0) || state.current() == NpcEmote.WAVE);
        assertEquals(NpcEmote.WAVE, state.current());
        assertTrue(state.isActive());
        assertEquals(NpcEmote.DEFAULT_DURATION_TICKS, state.totalTicks());
        assertEquals(0f, state.progress(), 1e-6);

        // Interrupt with a clamped-over-max duration
        assertTrue(state.start(NpcEmote.DANCE, 99999));
        assertEquals(NpcEmote.DANCE, state.current());
        assertEquals(NpcEmote.MAX_DURATION_TICKS, state.totalTicks());

        for (int i = 0; i < NpcEmote.MAX_DURATION_TICKS - 1; i++) {
            assertFalse(state.tick());
        }
        assertTrue(state.tick());
        assertEquals(NpcEmote.NONE, state.current());
        assertFalse(state.isActive());
        assertFalse(state.tick());

        // Stopping an inactive emote reports no change
        assertFalse(state.start(NpcEmote.NONE, 0));
    }

    @Test
    @DisplayName("emote wire names parse with target-compatible aliases")
    void emoteWireParse() {
        assertEquals(NpcEmote.NONE, NpcEmote.fromWire(null));
        assertEquals(NpcEmote.NONE, NpcEmote.fromWire("none"));
        assertEquals(NpcEmote.AIM, NpcEmote.fromWire("aim"));
        assertEquals(NpcEmote.CRAWL, NpcEmote.fromWire("crawl"));
        assertEquals(NpcEmote.HUG, NpcEmote.fromWire("hug"));
        assertEquals(NpcEmote.NO, NpcEmote.fromWire("no"));
        assertEquals(NpcEmote.YES, NpcEmote.fromWire("yes"));
        assertThrows(IllegalArgumentException.class, () -> NpcEmote.fromWire("salute"));
        assertEquals(10, NpcEmote.EMOTE_COUNT);
    }
}
