package com.storynpcs.domain.role;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoleCooldownPolicyTest {

    @Test
    void neverActivatedIsAlwaysReady() {
        assertThat(RoleCooldownPolicy.canActivate(10_000L, 0L, 5_000L)).isTrue();
        assertThat(RoleCooldownPolicy.canActivate(10_000L, -1L, 5_000L)).isTrue();
    }

    @Test
    void blockedWhileWithinCooldownWindow() {
        assertThat(RoleCooldownPolicy.canActivate(10_000L, 1_000L, 5_000L)).isFalse();
    }

    @Test
    void readyExactlyAtCooldownBoundary() {
        assertThat(RoleCooldownPolicy.canActivate(10_000L, 1_000L, 11_000L)).isTrue();
    }

    @Test
    void readyAfterCooldownElapses() {
        assertThat(RoleCooldownPolicy.canActivate(10_000L, 1_000L, 20_000L)).isTrue();
    }

    @Test
    void nonPositiveCooldownIsAlwaysReady() {
        assertThat(RoleCooldownPolicy.canActivate(0L, 1_000L, 1_001L)).isTrue();
        assertThat(RoleCooldownPolicy.canActivate(-5L, 1_000L, 1_001L)).isTrue();
    }
}
