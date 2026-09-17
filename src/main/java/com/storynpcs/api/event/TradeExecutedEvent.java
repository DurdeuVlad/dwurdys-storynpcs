package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.role.trader.TradeListing;

import java.util.UUID;

public record TradeExecutedEvent(
        UUID playerUuid,
        NamespacedId npcId,
        TradeListing trade
) implements StoryNpcsEvent {}