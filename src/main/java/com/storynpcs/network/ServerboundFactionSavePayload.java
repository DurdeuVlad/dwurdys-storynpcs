package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Sent from the client faction editor to the server to validate, persist, and
 * apply an edited (or newly authored) faction definition.
 */
public record ServerboundFactionSavePayload(
        String factionJson,
        long expectedRevision,
        UUID requestId
) implements CustomPacketPayload {
    public static final int MAX_FACTION_JSON_LENGTH = 1 << 20; // 1 MiB

    public ServerboundFactionSavePayload {
        factionJson = factionJson != null ? factionJson : "";
        if (expectedRevision < 0) expectedRevision = 0;
        requestId = requestId != null ? requestId : UUID.randomUUID();
    }

    public ServerboundFactionSavePayload(String factionJson) {
        this(factionJson, 0L, UUID.randomUUID());
    }

    public static final Type<ServerboundFactionSavePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "faction_editor_save"));

    public static final StreamCodec<ByteBuf, ServerboundFactionSavePayload> STREAM_CODEC =
            MutationProtocolCodecs.editorPayload(StreamCodec.composite(
                    MutationProtocolCodecs.JSON_CODEC, ServerboundFactionSavePayload::factionJson,
                    ByteBufCodecs.VAR_LONG, ServerboundFactionSavePayload::expectedRevision,
                    MutationProtocolCodecs.UUID_CODEC, ServerboundFactionSavePayload::requestId,
                    ServerboundFactionSavePayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
