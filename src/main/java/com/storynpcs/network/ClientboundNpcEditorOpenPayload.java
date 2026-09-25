package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent from server to client to open the NpcEditorScreen.
 * Carries the full NPC definition as JSON (see {@link com.storynpcs.domain.npc.NpcDefinitionSerde}).
 */
public record ClientboundNpcEditorOpenPayload(
        String npcId,
        String npcJson,
        long revision
) implements CustomPacketPayload {
    public static final int MAX_NPC_JSON_LENGTH = 1 << 20; // 1 MiB

    public ClientboundNpcEditorOpenPayload {
        npcId = npcId != null ? npcId : "";
        npcJson = npcJson != null ? npcJson : "";
        if (revision < 0) revision = 0;
    }

    public ClientboundNpcEditorOpenPayload(String npcId, String npcJson) {
        this(npcId, npcJson, 0L);
    }

    public static final Type<ClientboundNpcEditorOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "npc_editor_open"));

    public static final StreamCodec<ByteBuf, ClientboundNpcEditorOpenPayload> STREAM_CODEC =
            MutationProtocolCodecs.editorPayload(StreamCodec.composite(
                    MutationProtocolCodecs.ID_CODEC, ClientboundNpcEditorOpenPayload::npcId,
                    MutationProtocolCodecs.JSON_CODEC, ClientboundNpcEditorOpenPayload::npcJson,
                    ByteBufCodecs.VAR_LONG, ClientboundNpcEditorOpenPayload::revision,
                    ClientboundNpcEditorOpenPayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
