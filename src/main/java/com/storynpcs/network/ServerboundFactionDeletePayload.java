package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Sent from the client faction editor to delete a faction definition
 * (registry + YAML file) via the application service.
 */
public record ServerboundFactionDeletePayload(
        String factionId,
        long expectedRevision,
        UUID requestId
) implements CustomPacketPayload {

    public ServerboundFactionDeletePayload {
        factionId = factionId != null ? factionId : "";
        if (expectedRevision < 0) expectedRevision = 0;
        requestId = requestId != null ? requestId : UUID.randomUUID();
    }

    public ServerboundFactionDeletePayload(String factionId) {
        this(factionId, 0L, UUID.randomUUID());
    }

    public static final Type<ServerboundFactionDeletePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "faction_editor_delete"));

    public static final StreamCodec<ByteBuf, ServerboundFactionDeletePayload> STREAM_CODEC =
            MutationProtocolCodecs.versioned(StreamCodec.composite(
                    MutationProtocolCodecs.ID_CODEC, ServerboundFactionDeletePayload::factionId,
                    ByteBufCodecs.VAR_LONG, ServerboundFactionDeletePayload::expectedRevision,
                    MutationProtocolCodecs.UUID_CODEC, ServerboundFactionDeletePayload::requestId,
                    ServerboundFactionDeletePayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
