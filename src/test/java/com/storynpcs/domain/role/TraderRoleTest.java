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
    @DisplayName("Prepared trade intent retains the secondary payment contract")
    void preparedTradeIntentRetainsSecondaryPayment() throws IOException {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId npcId = NamespacedId.of("storynpcs:merchant");
        UUID requestId = UUID.randomUUID();
        TradeListing listing = new TradeListing("minecraft:bread", 1, "minecraft:wheat", 2);
        listing.setMaxUses(3);
        listing.setSecondaryPriceItemId("minecraft:coal");
        listing.setSecondaryPriceCount(1);

        assertTrue(service.executeTrade(playerUuid, npcId, 0, listing, requestId));

        var journal = new DurableOperationJournal(repo.storageDirectory().resolve("trade-operations"));
        var record = journal.read(requestId);
        var intent = com.storynpcs.domain.role.RoleSerde
                .tradeOperationIntentFromJson(record.preparedIntent()).orElseThrow();
        assertEquals("minecraft:coal", intent.secondaryPriceItemId());
        assertEquals(1, intent.secondaryPriceCount());
    }

    @Test
    @DisplayName("Legacy prepared trade intents default to a single payment input")
    void legacyPreparedTradeIntentDefaultsSecondaryPayment() {
        String legacyIntent = """
                {"playerUuid":"00000000-0000-0000-0000-000000000001",
                 "npcId":"storynpcs:merchant","listingIndex":0,"listingId":"legacy-abc",
                 "offerItemId":"minecraft:bread","offerCount":1,
                 "priceItemId":"minecraft:wheat","priceCount":2,
                 "maxUses":3,"usesBefore":0,"requiredFactionId":"","requiredFactionPoints":0}
                """;

        var restored = com.storynpcs.domain.role.RoleSerde
                .tradeOperationIntentFromJson(legacyIntent).orElseThrow();

        assertEquals("", restored.secondaryPriceItemId());
        assertEquals(0, restored.secondaryPriceCount());
    }

    @Test
    @DisplayName("Committed legacy two-input trade requests replay after listing identity migration")
    void committedLegacyTwoInputTradeReplayRemainsIdempotent() throws IOException {
        NamespacedId npcId = NamespacedId.of("storynpcs:merchant");
        var journal = new DurableOperationJournal(repo.storageDirectory().resolve("trade-operations"));

        for (boolean authoredListingId : List.of(false, true)) {
            UUID playerUuid = UUID.randomUUID();
            UUID requestId = UUID.randomUUID();
            TradeListing legacyListing = new TradeListing("minecraft:bread", 1, "minecraft:wheat", 2);
            legacyListing.setMaxUses(3);
            if (authoredListingId) legacyListing.setListingId("merchant-listing");
            String legacyListingId = legacyListing.ensureStableId();

            TradeListing currentListing = new TradeListing("minecraft:bread", 1, "minecraft:wheat", 2);
            currentListing.setMaxUses(3);
            currentListing.setSecondaryPriceItemId("minecraft:coal");
            currentListing.setSecondaryPriceCount(1);
            currentListing.setListingId(legacyListingId);

            String legacySubject = playerUuid + "|" + npcId + "|0|" + legacyListingId
                    + "|minecraft:bread|1|minecraft:wheat|2|3||0";
            var legacyIntent = new TradeOperationIntent(
                    playerUuid, npcId.toString(), 0, legacyListingId,
                    "minecraft:bread", 1, "minecraft:wheat", 2,
                    3, 0, "", 0);
            journal.begin(requestId, "trade.execute", legacySubject,
                    com.storynpcs.domain.role.RoleSerde.toJson(legacyIntent));
            journal.commit(requestId, "APPLIED", "1");

            assertTrue(service.executeTrade(playerUuid, npcId, 0, currentListing, requestId));

            if (authoredListingId) {
                assertEquals(legacyListingId, currentListing.getListingId());
            } else {
                assertNotEquals(legacyListingId, currentListing.getListingId());
            }
            assertEquals(0, currentListing.getUses());
            assertEquals(DurableOperationJournal.State.COMMITTED, journal.read(requestId).state());
        }

        assertTrue(tradeEvents.isEmpty());
    }

    @Test
    @DisplayName("Prepared legacy two-input trade requests reconcile by their old listing identity")
    void preparedLegacyTwoInputTradeReplayReconcilesByLegacyListingId(@TempDir Path tempDir)
            throws IOException {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId npcId = NamespacedId.of("storynpcs:merchant");
        UUID requestId = UUID.randomUUID();
        service.setTradeStateRepository(new TradeStateRepository(tempDir.resolve("trade-states")));

        TradeListing legacyListing = new TradeListing("minecraft:bread", 1, "minecraft:wheat", 2);
        legacyListing.setMaxUses(3);
        String legacyListingId = legacyListing.ensureStableId();
        TradeListing currentListing = new TradeListing("minecraft:bread", 1, "minecraft:wheat", 2);
        currentListing.setMaxUses(3);
        currentListing.setSecondaryPriceItemId("minecraft:coal");
        currentListing.setSecondaryPriceCount(1);
        currentListing.setListingId(legacyListingId);

        String legacySubject = playerUuid + "|" + npcId + "|0|" + legacyListingId
                + "|minecraft:bread|1|minecraft:wheat|2|3||0";
        var legacyIntent = new TradeOperationIntent(
                playerUuid, npcId.toString(), 0, legacyListingId,
                "minecraft:bread", 1, "minecraft:wheat", 2,
                3, 0, "", 0);
        var journal = new DurableOperationJournal(repo.storageDirectory().resolve("trade-operations"));
        journal.begin(requestId, "trade.execute", legacySubject,
                com.storynpcs.domain.role.RoleSerde.toJson(legacyIntent));

        assertFalse(service.executeTrade(playerUuid, npcId, 0, currentListing, requestId));

        assertEquals(DurableOperationJournal.State.ABORTED, journal.read(requestId).state());
        assertEquals("TRADE_NOT_COMMITTED", journal.read(requestId).outcomeCode());
        assertEquals(0, currentListing.getUses());
        assertTrue(tradeEvents.isEmpty());
    }

    @Test
    @DisplayName("Legacy single-input listing identity remains stable")
    void legacySingleInputListingIdentityRemainsStable() {
        TradeListing listing = new TradeListing("minecraft:bread", 4, "minecraft:wheat", 12);
        listing.setMaxUses(5);

        assertEquals("legacy-89225e309de8ec9a", listing.ensureStableId());
    }

    @Test
    @DisplayName("Legacy listing identity includes the second payment input")
    void legacyListingIdentityIncludesSecondaryPrice() {
        TradeListing coalOffer = new TradeListing("minecraft:bread", 1, "minecraft:wheat", 2);
        coalOffer.setMaxUses(3);
        coalOffer.setSecondaryPriceItemId("minecraft:coal");
        coalOffer.setSecondaryPriceCount(1);

        TradeListing ironOffer = new TradeListing("minecraft:bread", 1, "minecraft:wheat", 2);
        ironOffer.setMaxUses(3);
        ironOffer.setSecondaryPriceItemId("minecraft:iron_ingot");
        ironOffer.setSecondaryPriceCount(1);

        assertNotEquals(coalOffer.ensureStableId(), ironOffer.ensureStableId());
    }

    @Test
    @DisplayName("Secondary price identity changes retain legacy listing limits")
    void secondaryPriceIdentityMigrationPreservesDurableUseLimit(@TempDir Path tempDir) throws IOException {
        TradeStateRepository tradeState = new TradeStateRepository(tempDir.resolve("trade"));
        service.setTradeStateRepository(tradeState);
        UUID playerUuid = UUID.randomUUID();
        NamespacedId npcId = NamespacedId.of("storynpcs:merchant");

        TradeListing legacy = new TradeListing("minecraft:bread", 1, "minecraft:wheat", 2);
        legacy.setMaxUses(1);
        String legacyListingId = legacy.ensureStableId();
        assertTrue(tradeState.reserveUse(npcId.toString(), legacyListingId, 0, 1));

        TradeListing current = new TradeListing("minecraft:bread", 1, "minecraft:wheat", 2);
        current.setMaxUses(1);
        current.setSecondaryPriceItemId("minecraft:coal");
        current.setSecondaryPriceCount(1);

        assertFalse(service.executeTrade(playerUuid, npcId, 0, current, UUID.randomUUID()));

        String currentListingId = current.ensureStableId();
        assertNotEquals(legacyListingId, currentListingId);
        assertEquals(1, tradeState.getUses(npcId.toString(), legacyListingId));
        assertEquals(1, tradeState.getUses(npcId.toString(), currentListingId));
        assertEquals(1, current.getUses());
        assertEquals(0, tradeEvents.size());
    }

    @Test
    @DisplayName("Persisted legacy listing IDs are rebased without losing their durable use count")
    void persistedLegacyListingIdRetainsDurableUseCount(@TempDir Path tempDir) throws IOException {
        TradeStateRepository tradeState = new TradeStateRepository(tempDir.resolve("trade"));
        service.setTradeStateRepository(tradeState);
        UUID playerUuid = UUID.randomUUID();
        NamespacedId npcId = NamespacedId.of("storynpcs:merchant_persisted_legacy");

        TradeListing legacy = new TradeListing("minecraft:bread", 1, "minecraft:wheat", 2);
        legacy.setMaxUses(1);
        String legacyListingId = legacy.ensureStableId();
        assertTrue(tradeState.reserveUse(npcId.toString(), legacyListingId, 0, 1));

        TradeListing current = new TradeListing("minecraft:bread", 1, "minecraft:wheat", 2);
        current.setMaxUses(1);
        current.setSecondaryPriceItemId("minecraft:iron_ingot");
        current.setSecondaryPriceCount(1);
        current.setListingId(legacyListingId);

        assertFalse(service.executeTrade(playerUuid, npcId, 0, current, UUID.randomUUID()));

        assertNotEquals(legacyListingId, current.getListingId());
        assertEquals(1, tradeState.getUses(npcId.toString(), current.getListingId()));
        assertEquals(1, tradeState.getUses(npcId.toString(), legacyListingId));
        assertEquals(0, tradeEvents.size());
    }

    @Test
    @DisplayName("Prepared trade intent captures durable rather than definition-local uses")
    void preparedIntentCapturesDurableUseCount(@TempDir Path tempDir) throws IOException {
        TradeStateRepository tradeState = new TradeStateRepository(tempDir.resolve("trade"));
        service.setTradeStateRepository(tradeState);
        UUID playerUuid = UUID.randomUUID();
        NamespacedId npcId = NamespacedId.of("storynpcs:merchant_durable_uses");
        TradeListing listing = new TradeListing("minecraft:bread", 1, "minecraft:wheat", 2);
        listing.setMaxUses(4);
        String listingId = listing.ensureStableId();
        assertTrue(tradeState.reserveUse(npcId.toString(), listingId, 0, 4));
        listing.setUses(0);
        UUID requestId = UUID.randomUUID();

        assertTrue(service.executeTrade(playerUuid, npcId, 0, listing, requestId));

        var journal = new DurableOperationJournal(repo.storageDirectory().resolve("trade-operations"));
        var record = journal.read(requestId);
        var intent = com.storynpcs.domain.role.RoleSerde
                .tradeOperationIntentFromJson(record.preparedIntent()).orElseThrow();
        assertEquals(1, intent.usesBefore());
        assertEquals(2, tradeState.getUses(npcId.toString(), listingId));
    }

    @Test
    @DisplayName("A trade request ID cannot replay with a changed second payment input")
    void tradeReplayWithChangedSecondaryPriceIsRejected() {
        UUID playerUuid = UUID.randomUUID();
        NamespacedId npcId = NamespacedId.of("storynpcs:merchant");
        UUID requestId = UUID.randomUUID();

        TradeListing first = new TradeListing("minecraft:bread", 1, "minecraft:wheat", 2);
        first.setListingId("merchant-listing");
        first.setMaxUses(3);
        first.setSecondaryPriceItemId("minecraft:coal");
        first.setSecondaryPriceCount(1);
        assertTrue(service.executeTrade(playerUuid, npcId, 0, first, requestId));

        TradeListing changed = new TradeListing("minecraft:bread", 1, "minecraft:wheat", 2);
        changed.setListingId("merchant-listing");
        changed.setMaxUses(3);
        changed.setSecondaryPriceItemId("minecraft:iron_ingot");
        changed.setSecondaryPriceCount(1);
        assertFalse(service.executeTrade(playerUuid, npcId, 0, changed, requestId));
        assertEquals(0, changed.getUses());
        assertEquals(1, tradeEvents.size());
    }

    private static TradeListing listingPausedAfterDurableSnapshot(
            java.util.concurrent.CyclicBarrier intentBarrier,
            java.util.concurrent.atomic.AtomicInteger failedRendezvous) {
        return new TradeListing("minecraft:bread", 1, "minecraft:wheat", 1) {
            private int offerItemIdReads;

            @Override
            public String getOfferItemId() {
                if (++offerItemIdReads == 2) {
                    try {
                        intentBarrier.await(2, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (java.util.concurrent.TimeoutException
                            | java.util.concurrent.BrokenBarrierException failure) {
                        failedRendezvous.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("trade test interrupted", interrupted);
                    }
                }
                return super.getOfferItemId();
            }
        };
    }

    @Test
    @DisplayName("Concurrent indexed trades do not reject remaining durable uses")
    void concurrentIndexedTradesDoNotRejectRemainingDurableUses(@TempDir Path tempDir) throws Exception {
        NamespacedId npcId = NamespacedId.of("storynpcs:merchant_indexed_race");
        TradeStateRepository tradeState = new TradeStateRepository(tempDir.resolve("trade"));
        service.setTradeStateRepository(tradeState);
        var intentBarrier = new java.util.concurrent.CyclicBarrier(2);
        var failedRendezvous = new java.util.concurrent.atomic.AtomicInteger();
        TradeListing firstListing = listingPausedAfterDurableSnapshot(intentBarrier, failedRendezvous);
        TradeListing secondListing = listingPausedAfterDurableSnapshot(intentBarrier, failedRendezvous);
        firstListing.setListingId("shared-listing");
        secondListing.setListingId("shared-listing");
        firstListing.setMaxUses(2);
        secondListing.setMaxUses(2);
        List<UUID> requestIds = List.of(
                UUID.fromString("00000000-0000-0000-0000-000000000000"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertEquals(0, requestIds.get(0).hashCode());
        assertEquals(1, requestIds.get(1).hashCode());

        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var outcomes = new ArrayList<java.util.concurrent.Future<Boolean>>();
        try {
            for (int index = 0; index < requestIds.size(); index++) {
                TradeListing listing = List.of(firstListing, secondListing).get(index);
                UUID playerUuid = UUID.randomUUID();
                UUID requestId = requestIds.get(index);
                outcomes.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("trade concurrency test did not start");
                    }
                    return service.executeTrade(playerUuid, npcId, 0, listing, requestId);
                }));
            }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            int successes = 0;
            for (var outcome : outcomes) {
                if (outcome.get(10, java.util.concurrent.TimeUnit.SECONDS)) successes++;
            }
            assertEquals(2, successes);
            assertEquals(2, failedRendezvous.get());
        } finally {
            start.countDown();
            pool.shutdownNow();
        }

        assertEquals(2, tradeState.getUses(npcId.toString(), "shared-listing"));
        assertEquals(2, tradeEvents.size());
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
    @DisplayName("Unindexed trades still write a replay-protection journal record")
    void unindexedTradeWritesJournalRecord() throws IOException {
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
            assertEquals(1, files.filter(path -> path.getFileName().toString().endsWith(".json")).count(),
                    "An unindexed trade must still be bound to a durable request record");
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

    @Test
    @DisplayName("Replaying a prepared trade request reconciles it instead of staying pending")
    void preparedTradeReplayIsReconciled(@TempDir Path tempDir) throws IOException {
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

        // The retry drives reconciliation itself: usesBefore still matches
        // durable state, so the record aborts as provably never committed.
        assertFalse(service.executeTrade(playerUuid, npcId, 0, listing, requestId));
        assertEquals(DurableOperationJournal.State.ABORTED, journal.read(requestId).state());
        assertEquals("TRADE_NOT_COMMITTED", journal.read(requestId).outcomeCode());
        assertEquals(0, listing.getUses(), "The aborted retry must not record a use");
        assertTrue(tradeEvents.isEmpty(), "The aborted retry must not fire an event");
    }

    @Test
    @DisplayName("Replaying a prepared trade whose durable uses advanced stays pending")
    void preparedTradeReplayWithAdvancedUsesStaysPending(@TempDir Path tempDir) throws IOException {
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

        // The durable reservation landed before the crash — the retry must
        // fail closed and leave the record pending for manual recovery.
        assertTrue(tradeStates.reserveUse(npcId.toString(), listingId, 0, 5));

        assertFalse(service.executeTrade(playerUuid, npcId, 0, listing, requestId));
        assertEquals(DurableOperationJournal.State.PREPARED, journal.read(requestId).state(),
                "A diverged reservation is never guessed");
    }
}
