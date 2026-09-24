package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent from server to client to open the DialogueEditorScreen.
 * Carries the full graph as JSON (see {@link com.storynpcs.domain.dialogue.DialogueGraphSerde})
 * so the editor opens populated even on dedicated servers where the client has no registry.
 */
public record ClientboundDialogueEditorOpenPayload(
        String dialogueId,
        String graphJson,
        long revision
) implements CustomPacketPayload {
    public static final int MAX_GRAPH_JSON_LENGTH = 1 << 20; // 1 MiB

    public ClientboundDialogueEditorOpenPayload {
        dialogueId = dialogueId != null ? dialogueId : "";
        graphJson = graphJson != null ? graphJson : "";
        if (revision < 0) revision = 0;
    }

    public ClientboundDialogueEditorOpenPayload(String dialogueId, String graphJson) {
        this(dialogueId, graphJson, 0L);
    }

    public static final Type<ClientboundDialogueEditorOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "dialogue_editor_open"));

    public static final StreamCodec<ByteBuf, ClientboundDialogueEditorOpenPayload> STREAM_CODEC =
            MutationProtocolCodecs.editorPayload(StreamCodec.composite(
                    MutationProtocolCodecs.ID_CODEC, ClientboundDialogueEditorOpenPayload::dialogueId,
                    MutationProtocolCodecs.JSON_CODEC, ClientboundDialogueEditorOpenPayload::graphJson,
                    ByteBufCodecs.VAR_LONG, ClientboundDialogueEditorOpenPayload::revision,
                    ClientboundDialogueEditorOpenPayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
