package com.storynpcs.domain.command;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CommandParityCatalogTest {

    @Test
    void containsExactlyTheSeventyTargetLeavesFromTheManifest() {
        assertThat(CommandParityCatalog.all()).hasSize(CommandParityCatalog.TARGET_COMMAND_LEAF_COUNT);
        assertThat(CommandParityCatalog.TARGET_COMMAND_LEAF_COUNT).isEqualTo(70);
    }

    @Test
    void everyInventoryIdIsUniqueAndFollowsTheManifestNamingScheme() {
        Set<String> seen = new HashSet<>();
        for (CommandParityEntry entry : CommandParityCatalog.all()) {
            assertThat(entry.getInventoryId()).matches("target\\.commands\\.\\d{4}");
            assertThat(seen.add(entry.getInventoryId()))
                    .as("duplicate inventoryId: " + entry.getInventoryId())
                    .isTrue();
        }
    }

    @Test
    void everySupportedEntryNamesItsStorynpcsEquivalent() {
        for (CommandParityEntry entry : CommandParityCatalog.all()) {
            if (entry.getStatus() == CommandParityStatus.SUPPORTED) {
                assertThat(entry.getStorynpcsEquivalent()).isNotBlank();
            }
        }
    }

    @Test
    void everyNonSupportedEntryGivesARationale() {
        for (CommandParityEntry entry : CommandParityCatalog.all()) {
            if (entry.getStatus() != CommandParityStatus.SUPPORTED) {
                assertThat(entry.getRationale()).isNotBlank();
            }
        }
    }

    @Test
    void everyLeafIsTriagedAfterP93Delivery() {
        long supported = CommandParityCatalog.all().stream()
                .filter(e -> e.getStatus() == CommandParityStatus.SUPPORTED).count();
        long unverified = CommandParityCatalog.all().stream()
                .filter(e -> e.getStatus() == CommandParityStatus.UNVERIFIED).count();
        long deviation = CommandParityCatalog.all().stream()
                .filter(e -> e.getStatus() == CommandParityStatus.INTENTIONAL_DEVIATION).count();

        // P9-3 triaged all 70 leaves; P9-2 delivered the 3 script leaves →
        // 61 supported, 9 intentional deviations
        // (chunkloaders x2, font x3, scene tick-set x2, scene pause x2).
        assertThat(supported).isEqualTo(61);
        assertThat(unverified).isZero();
        assertThat(deviation).isEqualTo(9);
        assertThat(supported + unverified + deviation).isEqualTo(70);
    }

    @Test
    void everyTargetSymbolIsUniqueAndNonBlank() {
        Set<String> symbols = new HashSet<>();
        for (CommandParityEntry entry : CommandParityCatalog.all()) {
            assertThat(entry.getTargetSymbol()).isNotBlank().startsWith("/noppes/");
            assertThat(symbols.add(entry.getTargetSymbol()))
                    .as("duplicate targetSymbol: " + entry.getTargetSymbol())
                    .isTrue();
        }
    }
}
