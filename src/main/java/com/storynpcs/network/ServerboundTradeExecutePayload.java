package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Sent from client to server when the player clicks a listing in the trade screen.
 * The server re-validates everything (proximity, availability, payment) — the index
 * is a 0-based position into the trader's listing list at execution time.
 */
public record ServerboundTradeExecutePayload(
        String npcId,
        int listingIndex,
        UUID sessionId,
        UUID requestId
) implements CustomPacketPayload {

    public ServerboundTradeExecutePayload {
        npcId = npcId != null ? npcId : "";
        sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
        requestId = requestId != null ? requestId : UUID.randomUUID();
    }

    public ServerboundTradeExecutePayload(String npcId, int listingIndex) {
        this(npcId, listingIndex, new UUID(0L, 0L), UUID.randomUUID());
    }

    public static final Type<ServerboundTradeExecutePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "trade_execute"));

    public static final StreamCodec<ByteBuf, ServerboundTradeExecutePayload> STREAM_CODEC =
            MutationProtocolCodecs.versioned(StreamCodec.composite(
                    MutationProtocolCodecs.ID_CODEC, ServerboundTradeExecutePayload::npcId,
                    ByteBufCodecs.VAR_INT, ServerboundTradeExecutePayload::listingIndex,
                    MutationProtocolCodecs.UUID_CODEC, ServerboundTradeExecutePayload::sessionId,
                    MutationProtocolCodecs.UUID_CODEC, ServerboundTradeExecutePayload::requestId,
                    ServerboundTradeExecutePayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
