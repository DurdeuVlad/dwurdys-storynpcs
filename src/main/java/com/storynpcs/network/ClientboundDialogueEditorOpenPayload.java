package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * VULN-49 fix: Sent from server to client to open the DialogueEditorScreen for a given dialogue ID.
 * Previously /storynpcs dialogue edit sent a chat message but never dispatched this packet.
 */
public record ClientboundDialogueEditorOpenPayload(
        String dialogueId
) implements CustomPacketPayload {
    public ClientboundDialogueEditorOpenPayload {
        dialogueId = dialogueId != null ? dialogueId : "";
    }

    public static final Type<ClientboundDialogueEditorOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "dialogue_editor_open"));

    public static final StreamCodec<ByteBuf, ClientboundDialogueEditorOpenPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ClientboundDialogueEditorOpenPayload::dialogueId,
                    ClientboundDialogueEditorOpenPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
