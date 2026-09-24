package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Sent from client editor to server to validate, persist, and apply an edited NPC definition.
 */
public record ServerboundNpcSavePayload(
        String npcId,
        String npcJson,
        long expectedRevision,
        UUID requestId
) implements CustomPacketPayload {
    public static final int MAX_NPC_JSON_LENGTH = 1 << 20; // 1 MiB

    public ServerboundNpcSavePayload {
        npcId = npcId != null ? npcId : "";
        npcJson = npcJson != null ? npcJson : "";
        if (expectedRevision < 0) expectedRevision = 0;
        requestId = requestId != null ? requestId : UUID.randomUUID();
    }

    public ServerboundNpcSavePayload(String npcId, String npcJson) {
        this(npcId, npcJson, 0L, UUID.randomUUID());
    }

    public static final Type<ServerboundNpcSavePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "npc_editor_save"));

    public static final StreamCodec<ByteBuf, ServerboundNpcSavePayload> STREAM_CODEC =
            MutationProtocolCodecs.editorPayload(StreamCodec.composite(
                    MutationProtocolCodecs.ID_CODEC, ServerboundNpcSavePayload::npcId,
                    MutationProtocolCodecs.JSON_CODEC, ServerboundNpcSavePayload::npcJson,
                    ByteBufCodecs.VAR_LONG, ServerboundNpcSavePayload::expectedRevision,
                    MutationProtocolCodecs.UUID_CODEC, ServerboundNpcSavePayload::requestId,
                    ServerboundNpcSavePayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
