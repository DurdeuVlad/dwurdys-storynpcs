package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Client → server transport-destination selection (issue #150). The selection
 * is authorized by the panel {@code sessionId} + idempotent {@code requestId};
 * the destination is re-evaluated server-side through
 * {@code StoryNpcsApplicationService#requestTransport} — unlock state, fees,
 * and safety checks are never trusted from the client.
 */
public record ServerboundTransportSelectPayload(
        UUID sessionId,
        UUID requestId,
        String locationId
) implements CustomPacketPayload {

    public ServerboundTransportSelectPayload {
        sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
        requestId = requestId != null ? requestId : UUID.randomUUID();
        locationId = locationId != null ? locationId : "";
    }

    public static final Type<ServerboundTransportSelectPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "transport_select"));

    public static final StreamCodec<ByteBuf, ServerboundTransportSelectPayload> STREAM_CODEC =
            MutationProtocolCodecs.rolePayload(StreamCodec.composite(
                    MutationProtocolCodecs.UUID_CODEC, ServerboundTransportSelectPayload::sessionId,
                    MutationProtocolCodecs.UUID_CODEC, ServerboundTransportSelectPayload::requestId,
                    MutationProtocolCodecs.ID_CODEC, ServerboundTransportSelectPayload::locationId,
                    ServerboundTransportSelectPayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
