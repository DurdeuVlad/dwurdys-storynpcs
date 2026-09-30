package com.storynpcs.domain.npc;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DisplayProjectionResolverTest {

    private NpcDisplay display() {
        return new NpcDisplay();
    }

    @Test
    void authoredTextureResolvesToItsNamespacedId() {
        NpcDisplay display = display();
        display.setSkinTexture("storynpcs:textures/entity/guard.png");

        DisplayProjection projection = DisplayProjectionResolver.resolve(display);

        assertThat(projection.skinTexture())
                .isEqualTo(NamespacedId.of("storynpcs:textures/entity/guard.png"));
    }

    @Test
    void invalidAuthoredTextureFallsBackWithDiagnostic() {
        NpcDisplay display = display();
        display.setSkinTexture("INVALID UPPERCASE!!");
        ValidationResult diagnostics = ValidationResult.valid();

        DisplayProjectionResolver.Resolution resolution =
                DisplayProjectionResolver.resolve(display, diagnostics);

        assertThat(resolution.projection().skinTexture())
                .isEqualTo(DisplayProjectionResolver.DEFAULT_SKIN);
        assertThat(diagnostics.getErrors())
                .anyMatch(error -> error.code().contains("SKIN_TEXTURE_INVALID"));
    }

    @Test
    void blankAuthoredTextureFallsBackWithEmptyDiagnostic() {
        NpcDisplay display = display();
        display.setSkinTexture("   ");
        ValidationResult diagnostics = ValidationResult.valid();

        DisplayProjection projection = DisplayProjectionResolver.resolve(display, diagnostics).projection();

        assertThat(projection.skinTexture()).isEqualTo(DisplayProjectionResolver.DEFAULT_SKIN);
        assertThat(diagnostics.getErrors()).anyMatch(error -> error.code().contains("SKIN_TEXTURE_EMPTY"));
    }

    @Test
    void urlSkinRequiresWellFormedHttpUrl() {
        NpcDisplay display = display();
        display.setSkinUrl("https://example.com/skin.png");
        assertThat(DisplayProjectionResolver.resolve(display).skinUrl())
                .isEqualTo("https://example.com/skin.png");

        display = display();
        display.setSkinUrl("javascript:alert(1)");
        ValidationResult diagnostics = ValidationResult.valid();
        DisplayProjection projection = DisplayProjectionResolver.resolve(display, diagnostics).projection();
        assertThat(projection.skinUrl()).isEmpty();
        assertThat(projection.skinTexture()).isEqualTo(DisplayProjectionResolver.DEFAULT_SKIN);
        assertThat(diagnostics.getErrors()).anyMatch(error -> error.code().contains("SKIN_URL_INVALID"));

        display = display();
        display.setSkinUrl("https://example.com/has space.png");
        assertThat(DisplayProjectionResolver.resolve(display).skinUrl()).isEmpty();
    }

    @Test
    void playerSkinValidatesUsernameShape() {
        NpcDisplay display = display();
        display.setSkinPlayer("Steve_01");
        assertThat(DisplayProjectionResolver.resolve(display).skinPlayer()).isEqualTo("Steve_01");

        display = display();
        display.setSkinPlayer("no!!");
        ValidationResult diagnostics = ValidationResult.valid();
        DisplayProjection projection = DisplayProjectionResolver.resolve(display, diagnostics).projection();
        assertThat(projection.skinPlayer()).isEmpty();
        assertThat(diagnostics.getErrors()).anyMatch(error -> error.code().contains("SKIN_PLAYER_INVALID"));
    }

    @Test
    void cloakAndGlowResolveIndependentlyWithDiagnostics() {
        NpcDisplay display = display();
        display.setCloakTexture("storynpcs:textures/cloak.png");
        display.setGlowTexture("not a valid id");

        ValidationResult diagnostics = ValidationResult.valid();
        DisplayProjection projection = DisplayProjectionResolver.resolve(display, diagnostics).projection();

        assertThat(projection.cloakTexture()).isEqualTo(NamespacedId.of("storynpcs:textures/cloak.png"));
        assertThat(projection.glowTexture()).isNull();
        assertThat(diagnostics.getErrors()).anyMatch(error -> error.code().contains("GLOW_TEXTURE_INVALID"));
    }

    @Test
    void nameVisibilityProjectsLegacyFlagAndModeTogether() {
        NpcDisplay display = display();
        display.setShowNameMode(0);
        assertThat(DisplayProjectionResolver.resolve(display).nameVisibility())
                .isEqualTo(DisplayProjection.NameVisibility.ALWAYS);

        display.setShowNameMode(1);
        assertThat(DisplayProjectionResolver.resolve(display).nameVisibility())
                .isEqualTo(DisplayProjection.NameVisibility.NEVER);

        display = display();
        display.setShowName(false);
        assertThat(DisplayProjectionResolver.resolve(display).nameVisibility())
                .isEqualTo(DisplayProjection.NameVisibility.NEVER);

        display = display();
        display.setShowNameMode(2);
        DisplayProjection attacking = DisplayProjectionResolver.resolve(display);
        assertThat(attacking.nameVisible(true)).isTrue();
        assertThat(attacking.nameVisible(false)).isFalse();
    }

    @Test
    void hitboxScalesWithModelSizeAndAuthoredScale() {
        NpcDisplay display = display();
        DisplayProjection.ProjectedHitbox base = DisplayProjectionResolver.resolve(display).hitbox();
        assertThat(base.width()).isEqualTo(0.6f);
        assertThat(base.height()).isEqualTo(1.8f);
        assertThat(base.solid()).isTrue();

        display = display();
        display.setModelSize(10); // 2x
        display.setScaleX(0.5f);
        DisplayProjection.ProjectedHitbox doubled = DisplayProjectionResolver.resolve(display).hitbox();
        assertThat(doubled.width()).isEqualTo(0.6f * 0.5f * 2.0f);
        assertThat(doubled.height()).isEqualTo(1.8f * 2.0f);
        assertThat(doubled.eyeHeight()).isEqualTo(1.8f * 2.0f * 0.9f);
    }

    @Test
    void statueModeProjectsNonSolidHitbox() {
        NpcDisplay display = display();
        display.setHitboxState(1);

        DisplayProjection.ProjectedHitbox hitbox = DisplayProjectionResolver.resolve(display).hitbox();

        assertThat(hitbox.solid()).isFalse();
        assertThat(hitbox.width()).isGreaterThan(0f);
        assertThat(hitbox.height()).isGreaterThan(0f);
    }

    @Test
    void nullDisplayResolvesDefaultProjectionWithDiagnostic() {
        ValidationResult diagnostics = ValidationResult.valid();

        DisplayProjectionResolver.Resolution resolution =
                DisplayProjectionResolver.resolve(null, diagnostics);

        assertThat(resolution.projection().skinTexture())
                .isEqualTo(DisplayProjectionResolver.DEFAULT_SKIN);
        assertThat(resolution.projection().hitbox().solid()).isTrue();
        assertThat(diagnostics.getErrors()).anyMatch(error -> error.code().contains("DISPLAY_MISSING"));
    }

    @Test
    void nullDisplayFieldsNormalizeToDefaultsAndFingerprintNeverThrows() {
        String json = """
            {"id":"storynpcs:nullfields","display":{"name":null,"title":null,
            "skinTexture":null,"modelType":null}}
            """;
        NpcDefinition restored = NpcDefinitionSerde.fromJson(json).orElseThrow();
        NpcDisplay display = restored.getDisplay();

        assertThat(display.getName()).isEqualTo("StoryNPC");
        assertThat(display.getTitle()).isEmpty();
        // Explicit null clears to blank; the resolver then falls back to the default skin.
        assertThat(display.getSkinTexture()).isEmpty();
        assertThat(display.getModelType()).isEqualTo("humanoid");
        org.assertj.core.api.Assertions.assertThatCode(
                        () -> DisplayProjectionResolver.fingerprint(display))
                .doesNotThrowAnyException();
        assertThat(DisplayProjectionResolver.resolve(display).name()).isEqualTo("StoryNPC");
        assertThat(DisplayProjectionResolver.resolve(display).skinTexture())
                .isEqualTo(DisplayProjectionResolver.DEFAULT_SKIN);
    }

    @Test
    void cacheIsPerActorAndRefreshesOnContentChange() {
        DisplayProjectionCache cache = new DisplayProjectionCache();
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();

        NpcDisplay displayA = display();
        displayA.setName("Captain");
        NpcDisplay displayB = display();
        displayB.setName("Merchant");

        DisplayProjection firstA = cache.projectionFor(actorA, displayA);
        assertThat(cache.projectionFor(actorA, displayA)).isSameAs(firstA); // cached
        assertThat(cache.projectionFor(actorB, displayB).name()).isEqualTo("Merchant");
        assertThat(cache.projectionFor(actorA, displayA).name()).isEqualTo("Captain");

        displayA.setName("Captain V2"); // content change invalidates the fingerprint
        DisplayProjection refreshed = cache.projectionFor(actorA, displayA);
        assertThat(refreshed).isNotSameAs(firstA);
        assertThat(refreshed.name()).isEqualTo("Captain V2");

        cache.invalidate(actorA);
        assertThat(cache.size()).isEqualTo(1);
    }
}
