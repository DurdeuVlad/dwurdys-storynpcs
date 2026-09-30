package com.storynpcs.domain.persistence;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceStoreCatalogTest {

    @Test
    void containsExactlyTheEighteenTargetStoreCategoriesFromTheManifest() {
        assertThat(PersistenceStoreCatalog.all()).hasSize(PersistenceStoreCatalog.TARGET_STORE_COUNT);
        assertThat(PersistenceStoreCatalog.TARGET_STORE_COUNT).isEqualTo(18);
    }

    @Test
    void everyInventoryIdIsUniqueAndFollowsTheManifestNamingScheme() {
        Set<String> seen = new HashSet<>();
        for (PersistenceStoreEntry entry : PersistenceStoreCatalog.all()) {
            assertThat(entry.getInventoryId()).matches("target\\.persistence_stores\\.\\d{4}");
            assertThat(seen.add(entry.getInventoryId()))
                    .as("duplicate inventoryId: " + entry.getInventoryId())
                    .isTrue();
        }
    }

    @Test
    void everyMappedOrPartiallyMappedEntryNamesItsStorynpcsEquivalent() {
        for (PersistenceStoreEntry entry : PersistenceStoreCatalog.all()) {
            if (entry.getStatus() == PersistenceStoreStatus.MAPPED
                    || entry.getStatus() == PersistenceStoreStatus.PARTIALLY_MAPPED) {
                assertThat(entry.getStorynpcsEquivalent()).isNotBlank();
            }
        }
    }

    @Test
    void everyNonMappedEntryGivesARationale() {
        for (PersistenceStoreEntry entry : PersistenceStoreCatalog.all()) {
            if (entry.getStatus() != PersistenceStoreStatus.MAPPED) {
                assertThat(entry.getRationale()).isNotBlank();
            }
        }
    }

    @Test
    void statusCountsMatchTheAuditedSplit() {
        long mapped = count(PersistenceStoreStatus.MAPPED);
        long partiallyMapped = count(PersistenceStoreStatus.PARTIALLY_MAPPED);
        long deviation = count(PersistenceStoreStatus.INTENTIONAL_DEVIATION);
        long unmapped = count(PersistenceStoreStatus.UNMAPPED);

        assertThat(mapped).isEqualTo(3);
        assertThat(partiallyMapped).isEqualTo(3);
        assertThat(deviation).isEqualTo(1);
        assertThat(unmapped).isEqualTo(11);
        assertThat(mapped + partiallyMapped + deviation + unmapped).isEqualTo(18);
    }

    @Test
    void everyTargetSymbolIsUniqueAndNonBlank() {
        Set<String> symbols = new HashSet<>();
        for (PersistenceStoreEntry entry : PersistenceStoreCatalog.all()) {
            assertThat(entry.getTargetSymbol()).isNotBlank();
            assertThat(symbols.add(entry.getTargetSymbol()))
                    .as("duplicate targetSymbol: " + entry.getTargetSymbol())
                    .isTrue();
        }
    }

    @Test
    void bankStoreIsMappedToTheDurableBankRepository() {
        PersistenceStoreEntry banks = PersistenceStoreCatalog.all().stream()
                .filter(e -> e.getTargetSymbol().equals("banks"))
                .findFirst().orElseThrow();
        assertThat(banks.getStatus()).isEqualTo(PersistenceStoreStatus.MAPPED);
        assertThat(banks.getStorynpcsEquivalent()).contains("BankRepository");
    }

    private static long count(PersistenceStoreStatus status) {
        return PersistenceStoreCatalog.all().stream().filter(e -> e.getStatus() == status).count();
    }
}
