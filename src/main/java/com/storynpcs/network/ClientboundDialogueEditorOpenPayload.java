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
        String graphJson
) implements CustomPacketPayload {
    public static final int MAX_GRAPH_JSON_LENGTH = 1 << 20; // 1 MiB

    public ClientboundDialogueEditorOpenPayload {
        dialogueId = dialogueId != null ? dialogueId : "";
        graphJson = graphJson != null ? graphJson : "";
    }

    public static final Type<ClientboundDialogueEditorOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "dialogue_editor_open"));

    public static final StreamCodec<ByteBuf, ClientboundDialogueEditorOpenPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ClientboundDialogueEditorOpenPayload::dialogueId,
                    ByteBufCodecs.stringUtf8(MAX_GRAPH_JSON_LENGTH), ClientboundDialogueEditorOpenPayload::graphJson,
                    ClientboundDialogueEditorOpenPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
