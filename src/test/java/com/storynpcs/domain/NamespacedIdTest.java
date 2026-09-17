package com.storynpcs.domain;

import com.storynpcs.domain.common.NamespacedId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class NamespacedIdTest {

    @Test
    void shouldParseValidNamespacedId() {
        NamespacedId id = NamespacedId.of("storynpcs:elder_dialogue");
        assertThat(id.getNamespace()).isEqualTo("storynpcs");
        assertThat(id.getPath()).isEqualTo("elder_dialogue");
        assertThat(id.toString()).isEqualTo("storynpcs:elder_dialogue");
    }

    @Test
    void shouldDefaultNamespaceWhenOmitted() {
        NamespacedId id = NamespacedId.of("blacksmith");
        assertThat(id.getNamespace()).isEqualTo("storynpcs");
        assertThat(id.getPath()).isEqualTo("blacksmith");
        assertThat(id.toString()).isEqualTo("storynpcs:blacksmith");
    }

    @Test
    void shouldRejectInvalidNamespace() {
        assertThatThrownBy(() -> NamespacedId.of("InvalidCaps:path"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid namespace");
    }

    @Test
    void shouldSupportEqualityAndHashCode() {
        NamespacedId id1 = NamespacedId.of("storynpcs", "guard");
        NamespacedId id2 = NamespacedId.of("storynpcs:guard");
        assertThat(id1).isEqualTo(id2);
        assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
    }
}
