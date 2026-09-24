package com.storynpcs.service;

import com.storynpcs.domain.common.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalMutationResultTest {
    @Test
    void onlyTheFirstSuccessfulApplicationIsNewlyApplied() {
        assertThat(new CanonicalMutationResult(true, false, 1L, ValidationResult.valid()).newlyApplied()).isTrue();
        assertThat(new CanonicalMutationResult(true, true, 1L, ValidationResult.valid()).newlyApplied()).isFalse();
        assertThat(new CanonicalMutationResult(false, false, 1L, ValidationResult.valid()).newlyApplied()).isFalse();
    }
}
