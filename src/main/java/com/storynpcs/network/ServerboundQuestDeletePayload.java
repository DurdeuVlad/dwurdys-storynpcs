package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Sent from the client quest editor to delete a quest definition
 * (registry + YAML file) via the application service.
 */
public record ServerboundQuestDeletePayload(
        String questId,
        long expectedRevision,
        UUID requestId
) implements CustomPacketPayload {

    public ServerboundQuestDeletePayload {
        questId = questId != null ? questId : "";
        if (expectedRevision < 0) expectedRevision = 0;
        requestId = requestId != null ? requestId : UUID.randomUUID();
    }

    public ServerboundQuestDeletePayload(String questId) {
        this(questId, 0L, UUID.randomUUID());
    }

    public static final Type<ServerboundQuestDeletePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "quest_editor_delete"));

    public static final StreamCodec<ByteBuf, ServerboundQuestDeletePayload> STREAM_CODEC =
            MutationProtocolCodecs.versioned(StreamCodec.composite(
                    MutationProtocolCodecs.ID_CODEC, ServerboundQuestDeletePayload::questId,
                    ByteBufCodecs.VAR_LONG, ServerboundQuestDeletePayload::expectedRevision,
                    MutationProtocolCodecs.UUID_CODEC, ServerboundQuestDeletePayload::requestId,
                    ServerboundQuestDeletePayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
