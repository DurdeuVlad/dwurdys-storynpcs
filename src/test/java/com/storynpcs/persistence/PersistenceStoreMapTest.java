package com.storynpcs.persistence;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the 18 target-store mapping: unique manifest symbols, declared
 * ownership, and a concrete store or deferred owner issue per category.
 */
class PersistenceStoreMapTest {

    private static final Set<String> TARGET_SYMBOLS = Set.of(
            "banks", "client_presets", "clones", "config", "database", "dialogs",
            "factions", "global_data", "linked_npcs", "npc_entity_and_modules",
            "player_data", "quests", "recipes", "schematics",
            "scripted_item_and_blocks", "scripts", "spawns", "transport");

    @Test
    void mapCoversAllEighteenTargetStoresExactly() {
        var entries = PersistenceStoreMap.all();
        assertThat(entries).hasSize(18);
        assertThat(entries.stream().map(PersistenceStoreMap.Entry::targetSymbol))
                .containsExactlyInAnyOrderElementsOf(TARGET_SYMBOLS);
        assertThat(new HashSet<>(entries.stream().map(PersistenceStoreMap.Entry::targetSymbol).toList()))
                .hasSize(18);
    }

    @Test
    void everyEntryDeclaresOwnershipStatusStoreAndOwner() {
        for (var entry : PersistenceStoreMap.all()) {
            assertThat(entry.ownership()).as("%s ownership", entry.targetSymbol()).isNotNull();
            assertThat(entry.status()).as("%s status", entry.targetSymbol()).isNotNull();
            assertThat(entry.store()).as("%s store", entry.targetSymbol()).isNotBlank();
            assertThat(entry.ownerIssue()).as("%s owner issue", entry.targetSymbol())
                    .matches("P\\d+-\\d+");
        }
    }

    @Test
    void implementedEntriesNameConcreteStoresAndDeferredEntriesStayExplicit() {
        for (var entry : PersistenceStoreMap.all()) {
            switch (entry.status()) {
                case IMPLEMENTED -> assertThat(entry.implementedBy())
                        .as("%s must name its store classes", entry.targetSymbol())
                        .isNotEmpty();
                case COVERED -> assertThat(entry.store())
                        .as("%s must explain the covering design", entry.targetSymbol())
                        .isNotBlank();
                case DEFERRED -> assertThat(entry.ownerIssue())
                        .as("%s must name its owning parity issue", entry.targetSymbol())
                        .isNotEqualTo("P2-2");
            }
        }
        // Spot-check the implemented rows.
        assertThat(PersistenceStoreMap.entry("player_data").implementedBy())
                .contains(ProgressionRepository.class);
        assertThat(PersistenceStoreMap.entry("banks").implementedBy())
                .contains(BankRepository.class);
        assertThat(PersistenceStoreMap.entry("npc_entity_and_modules").implementedBy())
                .contains(com.storynpcs.runtime.actor.ActorStateRepository.class);
    }

    @Test
    void ownershipModelSeparatesDefinitionsFromRuntimeState() {
        for (var entry : PersistenceStoreMap.all()) {
            if (entry.ownership() == PersistenceStoreMap.Ownership.DEFINITION) {
                // Read-only content definitions never share the mutable runtime stores.
                assertThat(entry.store()).doesNotContain("SavedData");
            }
        }
        assertThat(PersistenceStoreMap.entry("player_data").ownership())
                .isEqualTo(PersistenceStoreMap.Ownership.PLAYER);
        assertThat(PersistenceStoreMap.entry("npc_entity_and_modules").ownership())
                .isEqualTo(PersistenceStoreMap.Ownership.ENTITY);
    }
}
