package com.storynpcs.domain.faction;

import com.storynpcs.domain.common.NamespacedId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FactionTest {

    private final NamespacedId self = NamespacedId.of("storynpcs:townsfolk");
    private final NamespacedId other = NamespacedId.of("storynpcs:bandits");

    private Faction faction() {
        return new Faction(self, "Townsfolk", 1000, 500, 1500);
    }

    @Test
    void undeclaredRelationshipDefaultsToNeutral() {
        assertThat(faction().getDeclaredRelationship(other)).isEqualTo(FactionStanding.NEUTRAL);
    }

    @Test
    void setRelationshipToIsReadableViaGetDeclaredRelationship() {
        Faction f = faction();
        f.setRelationshipTo(other, FactionStanding.HOSTILE);
        assertThat(f.getDeclaredRelationship(other)).isEqualTo(FactionStanding.HOSTILE);
    }

    @Test
    void setRelationshipToRejectsSelfRelationship() {
        Faction f = faction();
        assertThatThrownBy(() -> f.setRelationshipTo(self, FactionStanding.FRIENDLY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itself");
    }

    @Test
    void setRelationshipsRejectsSelfRelationshipInBulk() {
        Faction f = faction();
        assertThatThrownBy(() -> f.setRelationships(Map.of(self, FactionStanding.HOSTILE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itself");
    }

    @Test
    void removeRelationshipToRevertsToNeutralDefault() {
        Faction f = faction();
        f.setRelationshipTo(other, FactionStanding.FRIENDLY);
        f.removeRelationshipTo(other);
        assertThat(f.getDeclaredRelationship(other)).isEqualTo(FactionStanding.NEUTRAL);
    }

    @Test
    void setRelationshipsReplacesTheWholeMap() {
        Faction f = faction();
        NamespacedId third = NamespacedId.of("storynpcs:merchants");
        f.setRelationshipTo(other, FactionStanding.HOSTILE);

        f.setRelationships(Map.of(third, FactionStanding.FRIENDLY));

        assertThat(f.getDeclaredRelationship(other)).isEqualTo(FactionStanding.NEUTRAL);
        assertThat(f.getDeclaredRelationship(third)).isEqualTo(FactionStanding.FRIENDLY);
    }

    @Test
    void setRelationshipsRejectsNullKeyOrValue() {
        Faction f = faction();
        java.util.Map<NamespacedId, FactionStanding> withNullValue = new java.util.HashMap<>();
        withNullValue.put(other, null);
        assertThatThrownBy(() -> f.setRelationships(withNullValue)).isInstanceOf(IllegalArgumentException.class);
    }
}
