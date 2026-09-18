package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent from client to server when the admin clicks Save in the dialogue editor.
 * The server validates the graph, writes it to YAML atomically, and updates the
 * live registry — previously the editor's Save button persisted nothing.
 */
public record ServerboundDialogueSavePayload(
        String dialogueId,
        String graphJson
) implements CustomPacketPayload {
    public ServerboundDialogueSavePayload {
        dialogueId = dialogueId != null ? dialogueId : "";
        graphJson = graphJson != null ? graphJson : "";
    }

    public static final Type<ServerboundDialogueSavePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "dialogue_save"));

    public static final StreamCodec<ByteBuf, ServerboundDialogueSavePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ServerboundDialogueSavePayload::dialogueId,
                    ByteBufCodecs.stringUtf8(ClientboundDialogueEditorOpenPayload.MAX_GRAPH_JSON_LENGTH),
                            ServerboundDialogueSavePayload::graphJson,
                    ServerboundDialogueSavePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
