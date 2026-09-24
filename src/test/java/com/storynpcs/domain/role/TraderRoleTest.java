package com.storynpcs.domain.role;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.api.event.TradeExecutedEvent;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.role.trader.TradeListing;
import com.storynpcs.domain.role.trader.TraderRole;
import com.storynpcs.persistence.DurableOperationJournal;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.persistence.TradeOperationIntent;
import com.storynpcs.persistence.TradeStateRepository;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TraderRoleTest {

    private DefinitionRegistry registry;
    private ProgressionRepository repo;
    private EventPublisher publisher;
    private StoryNpcsApplicationService service;
    private List<TradeExecutedEvent> tradeEvents;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        registry = new DefinitionRegistry();
        repo = new ProgressionRepository(tempDir);
        publisher = new EventPublisher();
        tradeEvents = java.util.Collections.synchronizedList(new ArrayList<>());
        publisher.register(event -> {
            if (event instanceof TradeExecutedEvent te) {
                tradeEvents.add(te);
            }
        });

        service = new StoryNpcsApplicationService(registry, repo, publisher);

        Faction guardFaction = new Faction(NamespacedId.of("storynpcs:town_guard"), "Town Guard", 0, -500, 1000);
        guardFaction.setDefaultPoints(0);
        registry.registerFaction(guardFaction);
    }

    @Test
    @DisplayName("TradeListing enforces stock limits and restocks properly")
    void testTradeStockLimits() {
        TradeListing listing = new TradeListing("minecraft:iron_sword", 1, "minecraft:emerald", 5);
        listing.setMaxUses(2);

        assertTrue(listing.isAvailable(0));
        assertTrue(listing.recordTrade());
        assertEquals(1, listing.getUses());

        assertTrue(listing.isAvailable(0));
        assertTrue(listing.recordTrade());
        assertEquals(2, listing.getUses());

        // Max uses reached
        assertFalse(listing.isAvailable(0));
        assertFalse(listing.recordTrade());

        listing.restock();
        assertEquals(0, listing.getUses());
        assertTrue(listing.isAvailable(0));
    }

    @Test
    @DisplayName("TradeListing gates trades by faction reputation score")
    void testFactionGatedTrading() {
        TradeListing listing = new TradeListing("minecraft:diamond_sword", 1, "minecraft:emerald", 20);
        listing.setRequiredFaction(NamespacedId.of("storynpcs:town_guard"));
        listing.setRequiredFactionPoints(500);

        assertFalse(listing.isAvailable(200), "Should not be available with only 200 reputation");
        assertTrue(listing.isAvailable(500), "Should be available with 500 reputation");
        assertTrue(listing.isAvailable(1000), "Should be available with 1000 reputation");
    }

    @Test
    @DisplayName("Application service executes trade and fires domain event")
    void testApplicationServiceTradeExecution() {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId npcId = NamespacedId.of("storynpcs:merchant");

        TradeListing listing = new TradeListing("minecraft:bread", 4, "minecraft:wheat", 12);
        listing.setMaxUses(5);

        boolean executed = service.executeTrade(playerUuid, npcId, listing);
        assertTrue(executed);
        assertEquals(1, listing.getUses());
        assertEquals(1, tradeEvents.size());
        assertEquals(playerUuid, tradeEvents.get(0).playerUuid());
        assertEquals(npcId, tradeEvents.get(0).npcId());
    }

    @Test
    @DisplayName("A repeated trade request replays without charging another listing use")
    void testTradeRequestReplayIsIdempotent() {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId npcId = NamespacedId.of("storynpcs:merchant");
        UUID requestId = UUID.randomUUID();
        TradeListing listing = new TradeListing("minecraft:bread", 4, "minecraft:wheat", 12);
        listing.setMaxUses(5);

        assertTrue(service.executeTrade(playerUuid, npcId, 0, listing, requestId));
        assertTrue(service.executeTrade(playerUuid, npcId, 0, listing, requestId));
        assertEquals(1, listing.getUses());
        assertEquals(1, tradeEvents.size());

        TradeListing otherListing = new TradeListing("minecraft:diamond", 1, "minecraft:coal", 1);
        assertFalse(service.executeTrade(playerUuid, NamespacedId.of("storynpcs:other"),
                0, otherListing, requestId),
                "A request ID cannot be replayed against a different trade identity");
        assertEquals(0, otherListing.getUses());
        TradeListing changedListing = new TradeListing("minecraft:bread", 5, "minecraft:wheat", 12);
        assertFalse(service.executeTrade(playerUuid, npcId, 0, changedListing, requestId),
                "A request ID cannot be replayed against a changed listing contract");
        assertEquals(0, changedListing.getUses());
    }

    @Test
    @DisplayName("Indexed live trades use durable listing state instead of definition-local uses")
    void testIndexedTradePersistsListingUses(@TempDir Path tempDir) throws Exception {
        service.setTradeStateRepository(new TradeStateRepository(tempDir.resolve("trade")));
        UUID playerUuid = UUID.randomUUID();
        NamespacedId npcId = NamespacedId.of("storynpcs:merchant");
        TradeListing listing = new TradeListing("minecraft:bread", 1, "minecraft:wheat", 1);
        listing.setMaxUses(2);

        assertTrue(service.executeTrade(playerUuid, npcId, 0, listing, UUID.randomUUID()));
        assertEquals(1, new TradeStateRepository(tempDir.resolve("trade"))
                .getUses(npcId.toString(), listing.getListingId()));
        assertEquals(1, listing.getUses());
    }

    @Test
    @DisplayName("executeTrade on a sold-out listing fails without burning a use or firing an event")
    void testExecuteTradeSoldOut() {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId npcId = NamespacedId.of("storynpcs:merchant");
        TradeListing listing = new TradeListing("minecraft:bread", 4, "minecraft:wheat", 12);
        listing.setMaxUses(1);

        assertTrue(service.executeTrade(playerUuid, npcId, listing));
        assertEquals(1, listing.getUses());
        assertEquals(1, tradeEvents.size());

        assertFalse(service.executeTrade(playerUuid, npcId, listing),
                "Sold-out listing must reject the trade");
        assertEquals(1, listing.getUses(), "Failed trade must not record a use");
        assertEquals(1, tradeEvents.size(), "Failed trade must not fire an event");
    }

    @Test
    @DisplayName("Owned trade reservations roll back only their own use")
    void testTradeReservationRollbackIsOwned() {
        TradeListing listing = new TradeListing("minecraft:bread", 1, "minecraft:wheat", 1);
        listing.setMaxUses(2);

        TradeListing.TradeReservation first = listing.reserveTrade();
        TradeListing.TradeReservation second = listing.reserveTrade();
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(2, listing.getUses());

        first.rollback();
        assertEquals(1, listing.getUses());
        second.commit();
        assertEquals(1, listing.getUses(), "Committing the concurrent reservation must not be undone");

        first.rollback();
        assertEquals(1, listing.getUses(), "A reservation can only be rolled back once");
    }

    @Test
    @DisplayName("executeTrade rejects an unmet faction requirement without recording a use")
    void testExecuteTradeFactionGate() {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId npcId = NamespacedId.of("storynpcs:merchant");
        TradeListing listing = new TradeListing("minecraft:diamond_sword", 1, "minecraft:emerald", 20);
        listing.setRequiredFaction(NamespacedId.of("storynpcs:town_guard"));
        listing.setRequiredFactionPoints(500);

        assertFalse(service.executeTrade(playerUuid, npcId, listing));
        assertEquals(0, listing.getUses());
        assertTrue(tradeEvents.isEmpty());
    }

    @Test
    @DisplayName("Concurrent executeTrade calls cannot exceed a listing's maxUses")
    void testExecuteTradeConcurrentMaxUses() throws Exception {
        NamespacedId npcId = NamespacedId.of("storynpcs:merchant");
        TradeListing listing = new TradeListing("minecraft:bread", 4, "minecraft:wheat", 12);
        listing.setMaxUses(3);

        int attempts = 16;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var ready = new java.util.concurrent.CountDownLatch(attempts);
        var done = new java.util.concurrent.CountDownLatch(attempts);
        var successes = new java.util.concurrent.atomic.AtomicInteger();
        try {
            for (int i = 0; i < attempts; i++) {
                UUID playerUuid = UUID.randomUUID();
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        if (service.executeTrade(playerUuid, npcId, listing)) {
                            successes.incrementAndGet();
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "Concurrent trades did not finish in time");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(3, successes.get(), "Exactly maxUses trades may commit");
        assertEquals(3, listing.getUses(), "Recorded uses must match committed trades");
        assertEquals(3, tradeEvents.size(), "Only committed trades may fire events");
    }

    @Test
    @DisplayName("Unindexed trades run without a journal record — there is no durable listing state to reconcile")
    void unindexedTradeWritesNoJournalRecord() throws IOException {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId npcId = NamespacedId.of("storynpcs:merchant");
        TradeListing listing = new TradeListing("minecraft:bread", 4, "minecraft:wheat", 12);
        listing.setMaxUses(5);

        assertTrue(service.executeTrade(playerUuid, npcId, listing));
        assertEquals(1, listing.getUses());
        assertEquals(1, tradeEvents.size());

        Path journalDir = repo.storageDirectory().resolve("trade-operations");
        try (var files = Files.exists(journalDir)
                ? Files.list(journalDir)
                : java.util.stream.Stream.<Path>empty()) {
            assertEquals(0, files.count(),
                    "An unindexed trade must not leave an unreconcilable journal record");
        }
    }

    @Test
    @DisplayName("Recovery aborts a prepared trade whose durable listing use count never moved")
    void preparedTradeWithUnchangedDurableUsesIsAborted(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId npcId = NamespacedId.of("storynpcs:merchant");
        service.setTradeStateRepository(new TradeStateRepository(tempDir.resolve("trade-states")));

        TradeListing listing = new TradeListing("minecraft:bread", 4, "minecraft:wheat", 12);
        listing.setMaxUses(5);
        String listingId = listing.ensureStableId();

        UUID requestId = UUID.randomUUID();
        var intent = new TradeOperationIntent(
                playerUuid, npcId.toString(), 0, listingId,
                "minecraft:bread", 4, "minecraft:wheat", 12, 5, 0, "", 0);
        var journal = new DurableOperationJournal(repo.storageDirectory().resolve("trade-operations"));
        journal.begin(requestId, "trade.execute",
                playerUuid + "|" + npcId + "|0|" + listingId
                        + "|minecraft:bread|4|minecraft:wheat|12|5||0",
                com.storynpcs.domain.role.RoleSerde.toJson(intent));

        // Crash between durable prepare and the listing reservation: usesBefore
        // still matches durable state, so the operation provably never landed.
        assertEquals(0, service.recoverTradeOperations(playerUuid));
        assertEquals(DurableOperationJournal.State.ABORTED, journal.read(requestId).state());
        assertEquals("TRADE_NOT_COMMITTED", journal.read(requestId).outcomeCode());
    }

    @Test
    @DisplayName("Recovery keeps a prepared trade pending when the durable listing use count advanced")
    void preparedTradeWithAdvancedDurableUsesStaysPending(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId npcId = NamespacedId.of("storynpcs:merchant");
        TradeStateRepository tradeStates = new TradeStateRepository(tempDir.resolve("trade-states"));
        service.setTradeStateRepository(tradeStates);

        TradeListing listing = new TradeListing("minecraft:bread", 4, "minecraft:wheat", 12);
        listing.setMaxUses(5);
        String listingId = listing.ensureStableId();

        UUID requestId = UUID.randomUUID();
        var intent = new TradeOperationIntent(
                playerUuid, npcId.toString(), 0, listingId,
                "minecraft:bread", 4, "minecraft:wheat", 12, 5, 0, "", 0);
        var journal = new DurableOperationJournal(repo.storageDirectory().resolve("trade-operations"));
        journal.begin(requestId, "trade.execute",
                playerUuid + "|" + npcId + "|0|" + listingId
                        + "|minecraft:bread|4|minecraft:wheat|12|5||0",
                com.storynpcs.domain.role.RoleSerde.toJson(intent));

        // The durable reservation landed before the crash but its outcome is
        // ambiguous — the operation must stay pending rather than guess.
        assertTrue(tradeStates.reserveUse(npcId.toString(), listingId, 0, 5));

        assertEquals(1, service.recoverTradeOperations(playerUuid));
        assertEquals(DurableOperationJournal.State.PREPARED, journal.read(requestId).state());
    }
}
