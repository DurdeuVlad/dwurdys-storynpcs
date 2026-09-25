package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Sent from server to client after processing a quest save/delete request.
 * On success carries the refreshed quest registry so the editor's list view
 * stays in sync without a manual reload.
 */
public record ClientboundQuestSaveResultPayload(
        boolean success,
        String message,
        String questsJson,
        String code,
        UUID requestId,
        long revision
) implements CustomPacketPayload {
    public static final int MAX_QUESTS_JSON_LENGTH = 4 << 20; // 4 MiB

    public ClientboundQuestSaveResultPayload {
        message = message != null ? message : "";
        questsJson = questsJson != null ? questsJson : "[]";
        code = code != null ? code : (success ? "APPLIED" : "REJECTED");
        requestId = requestId != null ? requestId : new UUID(0L, 0L);
        if (revision < 0) revision = 0;
    }

    public ClientboundQuestSaveResultPayload(boolean success, String message, String questsJson) {
        this(success, message, questsJson, success ? "APPLIED" : "REJECTED", new UUID(0L, 0L), 0L);
    }

    public static final Type<ClientboundQuestSaveResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "quest_save_result"));

    public static final StreamCodec<ByteBuf, ClientboundQuestSaveResultPayload> STREAM_CODEC =
            MutationProtocolCodecs.registryPayload(StreamCodec.composite(
                    ByteBufCodecs.BOOL, ClientboundQuestSaveResultPayload::success,
                    MutationProtocolCodecs.MESSAGE_CODEC, ClientboundQuestSaveResultPayload::message,
                    MutationProtocolCodecs.REGISTRY_JSON_CODEC, ClientboundQuestSaveResultPayload::questsJson,
                    MutationProtocolCodecs.ID_CODEC, ClientboundQuestSaveResultPayload::code,
                    MutationProtocolCodecs.UUID_CODEC, ClientboundQuestSaveResultPayload::requestId,
                    ByteBufCodecs.VAR_LONG, ClientboundQuestSaveResultPayload::revision,
                    ClientboundQuestSaveResultPayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
