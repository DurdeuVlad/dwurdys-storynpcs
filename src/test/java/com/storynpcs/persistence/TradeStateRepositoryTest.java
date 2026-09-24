package com.storynpcs.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TradeStateRepositoryTest {

    @Test
    @DisplayName("Listing uses reserve durably and survive repository reload")
    void testReserveAndReload(@TempDir Path tempDir) throws IOException {
        Path tradeDir = tempDir.resolve("trade");
        String listingId = "bread-for-wheat";
        TradeStateRepository repository = new TradeStateRepository(tradeDir);

        assertEquals(0, repository.getUses("storynpcs:merchant", listingId));
        assertTrue(repository.reserveUse("storynpcs:merchant", listingId, 0, 3));
        assertFalse(repository.reserveUse("storynpcs:merchant", listingId, 0, 3));
        assertEquals(1, repository.getUses("storynpcs:merchant", listingId));

        TradeStateRepository reloaded = new TradeStateRepository(tradeDir);
        assertEquals(1, reloaded.getUses("storynpcs:merchant", listingId));
        assertTrue(reloaded.rollbackUse("storynpcs:merchant", listingId, 1, 0));
        assertEquals(0, reloaded.getUses("storynpcs:merchant", listingId));
    }

    @Test
    @DisplayName("Listing use reservation enforces max uses and rejects invalid keys")
    void testBounds(@TempDir Path tempDir) throws IOException {
        TradeStateRepository repository = new TradeStateRepository(tempDir.resolve("trade"));

        assertTrue(repository.reserveUse("storynpcs:merchant", "listing-a", 0, 1));
        assertFalse(repository.reserveUse("storynpcs:merchant", "listing-a", 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> repository.getUses("storynpcs:merchant", "bad id"));
    }

    @Test
    @DisplayName("Semantically corrupt listing-use state fails closed")
    void testCorruptNegativeUsesFailClosed(@TempDir Path tempDir) throws IOException {
        Path tradeDir = tempDir.resolve("trade");
        Files.createDirectories(tradeDir);
        Files.writeString(tradeDir.resolve("state.json"), """
                {"schemaVersion":1,"data":{"uses":{"storynpcs:merchant#0":-1}}}
                """);

        TradeStateRepository repository = new TradeStateRepository(tradeDir);
        assertThrows(IOException.class, () -> repository.getUses("storynpcs:merchant", "listing-a"));
        assertThrows(IOException.class, () -> repository.getUses("storynpcs:merchant", "listing-a"),
                "A repository that observed invalid state must remain unavailable");
    }

    @Test
    @DisplayName("Different stable listing identities never share usage state")
    void testListingIdentityIsolation(@TempDir Path tempDir) throws IOException {
        TradeStateRepository repository = new TradeStateRepository(tempDir.resolve("trade"));
        assertTrue(repository.reserveUse("storynpcs:merchant", "bread", 0, 1));
        assertEquals(1, repository.getUses("storynpcs:merchant", "bread"));
        assertEquals(0, repository.getUses("storynpcs:merchant", "sword"));
    }
}
