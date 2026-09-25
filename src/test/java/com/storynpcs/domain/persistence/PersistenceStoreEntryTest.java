package com.storynpcs.domain.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersistenceStoreEntryTest {

    @Test
    void mappedEntryRequiresAnEquivalentButNoRationale() {
        assertThatThrownBy(() -> new PersistenceStoreEntry(
                "target.persistence_stores.0001", "x", "Owner",
                PersistenceStoreStatus.MAPPED, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        PersistenceStoreEntry entry = new PersistenceStoreEntry(
                "target.persistence_stores.0001", "x", "Owner",
                PersistenceStoreStatus.MAPPED, "SomeRepository", null);
        assertThat(entry.getStorynpcsEquivalent()).isEqualTo("SomeRepository");
        assertThat(entry.getRationale()).isNull();
    }

    @Test
    void partiallyMappedEntryRequiresBothAnEquivalentAndARationale() {
        assertThatThrownBy(() -> new PersistenceStoreEntry(
                "target.persistence_stores.0001", "x", "Owner",
                PersistenceStoreStatus.PARTIALLY_MAPPED, null, "reason"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new PersistenceStoreEntry(
                "target.persistence_stores.0001", "x", "Owner",
                PersistenceStoreStatus.PARTIALLY_MAPPED, "SomeRepository", null))
                .isInstanceOf(IllegalArgumentException.class);

        PersistenceStoreEntry entry = new PersistenceStoreEntry(
                "target.persistence_stores.0001", "x", "Owner",
                PersistenceStoreStatus.PARTIALLY_MAPPED, "SomeRepository", "reason");
        assertThat(entry.getStorynpcsEquivalent()).isEqualTo("SomeRepository");
        assertThat(entry.getRationale()).isEqualTo("reason");
    }

    @Test
    void unmappedEntryRequiresARationaleAndRejectsAnEquivalent() {
        assertThatThrownBy(() -> new PersistenceStoreEntry(
                "target.persistence_stores.0001", "x", "Owner",
                PersistenceStoreStatus.UNMAPPED, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        PersistenceStoreEntry entry = new PersistenceStoreEntry(
                "target.persistence_stores.0001", "x", "Owner",
                PersistenceStoreStatus.UNMAPPED, null, "no equivalent exists yet");
        assertThat(entry.getRationale()).isEqualTo("no equivalent exists yet");
        assertThat(entry.getStorynpcsEquivalent()).isNull();
    }

    @Test
    void intentionalDeviationRequiresARationale() {
        assertThatThrownBy(() -> new PersistenceStoreEntry(
                "target.persistence_stores.0001", "x", "Owner",
                PersistenceStoreStatus.INTENTIONAL_DEVIATION, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        PersistenceStoreEntry entry = new PersistenceStoreEntry(
                "target.persistence_stores.0001", "x", "Owner",
                PersistenceStoreStatus.INTENTIONAL_DEVIATION, null, "architectural decision");
        assertThat(entry.getRationale()).isEqualTo("architectural decision");
    }

    @Test
    void blankRequiredFieldsAreRejected() {
        assertThatThrownBy(() -> new PersistenceStoreEntry(
                "", "x", "Owner", PersistenceStoreStatus.UNMAPPED, null, "reason"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PersistenceStoreEntry(
                "target.persistence_stores.0001", "", "Owner", PersistenceStoreStatus.UNMAPPED, null, "reason"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PersistenceStoreEntry(
                "target.persistence_stores.0001", "x", "", PersistenceStoreStatus.UNMAPPED, null, "reason"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
