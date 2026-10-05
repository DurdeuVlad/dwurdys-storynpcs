package com.storynpcs.domain.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Lifecycle rules for chair mounts and fake-living puppets (#148). */
class SupportEntityRulesTest {

    @Test
    void chairDiscardsOnlyAfterSpawnGraceWithNoPassenger() {
        // Spawn grace lets the mount call land on the next tick.
        assertThat(SupportEntityRules.chairShouldDiscard(0, 0)).isFalse();
        assertThat(SupportEntityRules.chairShouldDiscard(0,
                SupportEntityRules.CHAIR_SPAWN_GRACE_TICKS)).isFalse();
        assertThat(SupportEntityRules.chairShouldDiscard(0,
                SupportEntityRules.CHAIR_SPAWN_GRACE_TICKS + 1)).isTrue();
        // A ridden chair never discards.
        assertThat(SupportEntityRules.chairShouldDiscard(1, 10_000)).isFalse();
    }

    @Test
    void fakeLivingDiscardsWhenOwnerBoundAndOwnerAbsent() {
        assertThat(SupportEntityRules.fakeLivingShouldDiscard(true, false)).isTrue();
        assertThat(SupportEntityRules.fakeLivingShouldDiscard(true, true)).isFalse();
        // Ownerless props persist.
        assertThat(SupportEntityRules.fakeLivingShouldDiscard(false, false)).isFalse();
    }
}
