package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Sent from server to client after processing a faction save/delete request.
 * On success carries the refreshed faction registry so the editor's list view
 * stays in sync without a manual reload.
 */
public record ClientboundFactionSaveResultPayload(
        boolean success,
        String message,
        String factionsJson,
        String code,
        UUID requestId,
        long revision
) implements CustomPacketPayload {
    public static final int MAX_FACTIONS_JSON_LENGTH = 4 << 20; // 4 MiB

    public ClientboundFactionSaveResultPayload {
        message = message != null ? message : "";
        factionsJson = factionsJson != null ? factionsJson : "[]";
        code = code != null ? code : (success ? "APPLIED" : "REJECTED");
        requestId = requestId != null ? requestId : new UUID(0L, 0L);
        if (revision < 0) revision = 0;
    }

    public ClientboundFactionSaveResultPayload(boolean success, String message, String factionsJson) {
        this(success, message, factionsJson, success ? "APPLIED" : "REJECTED", new UUID(0L, 0L), 0L);
    }

    public static final Type<ClientboundFactionSaveResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "faction_save_result"));

    public static final StreamCodec<ByteBuf, ClientboundFactionSaveResultPayload> STREAM_CODEC =
            MutationProtocolCodecs.registryPayload(StreamCodec.composite(
                    ByteBufCodecs.BOOL, ClientboundFactionSaveResultPayload::success,
                    MutationProtocolCodecs.MESSAGE_CODEC, ClientboundFactionSaveResultPayload::message,
                    MutationProtocolCodecs.REGISTRY_JSON_CODEC, ClientboundFactionSaveResultPayload::factionsJson,
                    MutationProtocolCodecs.ID_CODEC, ClientboundFactionSaveResultPayload::code,
                    MutationProtocolCodecs.UUID_CODEC, ClientboundFactionSaveResultPayload::requestId,
                    ByteBufCodecs.VAR_LONG, ClientboundFactionSaveResultPayload::revision,
                    ClientboundFactionSaveResultPayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
