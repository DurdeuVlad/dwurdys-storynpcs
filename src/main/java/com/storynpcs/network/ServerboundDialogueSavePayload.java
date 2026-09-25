package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Sent from client to server when the admin clicks Save in the dialogue editor.
 * The server validates the graph, writes it to YAML atomically, and updates the
 * live registry — previously the editor's Save button persisted nothing.
 */
public record ServerboundDialogueSavePayload(
        String dialogueId,
        String graphJson,
        long expectedRevision,
        UUID requestId
) implements CustomPacketPayload {
    public ServerboundDialogueSavePayload {
        dialogueId = dialogueId != null ? dialogueId : "";
        graphJson = graphJson != null ? graphJson : "";
        if (expectedRevision < 0) expectedRevision = 0;
        requestId = requestId != null ? requestId : UUID.randomUUID();
    }

    public ServerboundDialogueSavePayload(String dialogueId, String graphJson) {
        this(dialogueId, graphJson, 0L, UUID.randomUUID());
    }

    public static final Type<ServerboundDialogueSavePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "dialogue_save"));

    public static final StreamCodec<ByteBuf, ServerboundDialogueSavePayload> STREAM_CODEC =
            MutationProtocolCodecs.editorPayload(StreamCodec.composite(
                    MutationProtocolCodecs.ID_CODEC, ServerboundDialogueSavePayload::dialogueId,
                    MutationProtocolCodecs.JSON_CODEC,
                            ServerboundDialogueSavePayload::graphJson,
                    ByteBufCodecs.VAR_LONG, ServerboundDialogueSavePayload::expectedRevision,
                    MutationProtocolCodecs.UUID_CODEC, ServerboundDialogueSavePayload::requestId,
                    ServerboundDialogueSavePayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
