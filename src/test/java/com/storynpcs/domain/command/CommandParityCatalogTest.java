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
    void tenLeavesAreCurrentlySupportedAndSixtyAreUnverified() {
        long supported = CommandParityCatalog.all().stream()
                .filter(e -> e.getStatus() == CommandParityStatus.SUPPORTED).count();
        long unverified = CommandParityCatalog.all().stream()
                .filter(e -> e.getStatus() == CommandParityStatus.UNVERIFIED).count();
        long deviation = CommandParityCatalog.all().stream()
                .filter(e -> e.getStatus() == CommandParityStatus.INTENTIONAL_DEVIATION).count();

        assertThat(supported).isEqualTo(10);
        assertThat(unverified).isEqualTo(60);
        assertThat(deviation).isEqualTo(0);
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
