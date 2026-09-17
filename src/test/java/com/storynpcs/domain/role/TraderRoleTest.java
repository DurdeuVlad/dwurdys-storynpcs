package com.storynpcs.domain.role;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.api.event.TradeExecutedEvent;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.role.trader.TradeListing;
import com.storynpcs.domain.role.trader.TraderRole;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        tradeEvents = new ArrayList<>();
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
}