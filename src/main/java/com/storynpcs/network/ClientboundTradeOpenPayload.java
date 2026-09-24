package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Sent from server to client to open the trade screen for a trader-role NPC.
 * Carries the {@link com.storynpcs.domain.role.trader.TraderRole} as JSON plus a
 * factionId→score map so the client can render per-listing availability.
 */
public record ClientboundTradeOpenPayload(
        String npcId,
        String npcName,
        String traderJson,
        String factionScoresJson,
        UUID sessionId
) implements CustomPacketPayload {
    public static final int MAX_JSON_LENGTH = 1 << 20; // 1 MiB

    public ClientboundTradeOpenPayload {
        npcId = npcId != null ? npcId : "";
        npcName = npcName != null ? npcName : "";
        traderJson = traderJson != null ? traderJson : "";
        factionScoresJson = factionScoresJson != null ? factionScoresJson : "";
        sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    public ClientboundTradeOpenPayload(String npcId, String npcName, String traderJson, String factionScoresJson) {
        this(npcId, npcName, traderJson, factionScoresJson, new UUID(0L, 0L));
    }

    public static final Type<ClientboundTradeOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "trade_open"));

    public static final StreamCodec<ByteBuf, ClientboundTradeOpenPayload> STREAM_CODEC =
            MutationProtocolCodecs.rolePayload(StreamCodec.composite(
                    MutationProtocolCodecs.ID_CODEC, ClientboundTradeOpenPayload::npcId,
                    MutationProtocolCodecs.TEXT_CODEC, ClientboundTradeOpenPayload::npcName,
                    ByteBufCodecs.stringUtf8(MAX_JSON_LENGTH), ClientboundTradeOpenPayload::traderJson,
                    ByteBufCodecs.stringUtf8(MAX_JSON_LENGTH), ClientboundTradeOpenPayload::factionScoresJson,
                    MutationProtocolCodecs.UUID_CODEC, ClientboundTradeOpenPayload::sessionId,
                    ClientboundTradeOpenPayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
