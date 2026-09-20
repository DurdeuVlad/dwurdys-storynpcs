package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent from server to client to open the trade screen for a trader-role NPC.
 * Carries the {@link com.storynpcs.domain.role.trader.TraderRole} as JSON plus a
 * factionId→score map so the client can render per-listing availability.
 */
public record ClientboundTradeOpenPayload(
        String npcId,
        String npcName,
        String traderJson,
        String factionScoresJson
) implements CustomPacketPayload {
    public static final int MAX_JSON_LENGTH = 1 << 20; // 1 MiB

    public ClientboundTradeOpenPayload {
        npcId = npcId != null ? npcId : "";
        npcName = npcName != null ? npcName : "";
        traderJson = traderJson != null ? traderJson : "";
        factionScoresJson = factionScoresJson != null ? factionScoresJson : "";
    }

    public static final Type<ClientboundTradeOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "trade_open"));

    public static final StreamCodec<ByteBuf, ClientboundTradeOpenPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ClientboundTradeOpenPayload::npcId,
                    ByteBufCodecs.STRING_UTF8, ClientboundTradeOpenPayload::npcName,
                    ByteBufCodecs.stringUtf8(MAX_JSON_LENGTH), ClientboundTradeOpenPayload::traderJson,
                    ByteBufCodecs.stringUtf8(MAX_JSON_LENGTH), ClientboundTradeOpenPayload::factionScoresJson,
                    ClientboundTradeOpenPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
