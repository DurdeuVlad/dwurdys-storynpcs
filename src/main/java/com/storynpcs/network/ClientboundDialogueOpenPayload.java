package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

public record ClientboundDialogueOpenPayload(
        String dialogueId,
        String nodeId,
        String text,
        String sound,
        List<String> options,
        boolean isTerminal,
        String npcName,
        List<String> optionHints,
        UUID sessionId
) implements CustomPacketPayload {
    public ClientboundDialogueOpenPayload {
        dialogueId = dialogueId != null ? dialogueId : "";
        nodeId = nodeId != null ? nodeId : "";
        text = text != null ? text : "";
        sound = sound != null ? sound : "";
        options = options != null ? options.stream().map(s -> s != null ? s : "").toList() : List.of();
        npcName = npcName != null ? npcName : "";
        optionHints = optionHints != null ? optionHints.stream().map(s -> s != null ? s : "").toList() : List.of();
        sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    public ClientboundDialogueOpenPayload(String dialogueId, String nodeId, String text, String sound,
                                          List<String> options, boolean isTerminal, String npcName,
                                          List<String> optionHints) {
        this(dialogueId, nodeId, text, sound, options, isTerminal, npcName, optionHints,
                new UUID(0L, 0L));
    }
    public static final Type<ClientboundDialogueOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "dialogue_open"));

    // composite() maxes out at 6 fields — explicit codec for the 8-field payload
    private static final StreamCodec<ByteBuf, List<String>> STRING_LIST = MutationProtocolCodecs.STRING_LIST_CODEC;

    public static final StreamCodec<ByteBuf, ClientboundDialogueOpenPayload> STREAM_CODEC =
            MutationProtocolCodecs.versioned(StreamCodec.of(
                    (buf, p) -> {
                        MutationProtocolCodecs.ID_CODEC.encode(buf, p.dialogueId());
                        MutationProtocolCodecs.ID_CODEC.encode(buf, p.nodeId());
                        MutationProtocolCodecs.TEXT_CODEC.encode(buf, p.text());
                        MutationProtocolCodecs.ID_CODEC.encode(buf, p.sound());
                        STRING_LIST.encode(buf, p.options());
                        ByteBufCodecs.BOOL.encode(buf, p.isTerminal());
                        MutationProtocolCodecs.TEXT_CODEC.encode(buf, p.npcName());
                        STRING_LIST.encode(buf, p.optionHints());
                        MutationProtocolCodecs.UUID_CODEC.encode(buf, p.sessionId());
                    },
                    buf -> new ClientboundDialogueOpenPayload(
                            MutationProtocolCodecs.ID_CODEC.decode(buf),
                            MutationProtocolCodecs.ID_CODEC.decode(buf),
                            MutationProtocolCodecs.TEXT_CODEC.decode(buf),
                            MutationProtocolCodecs.ID_CODEC.decode(buf),
                            STRING_LIST.decode(buf),
                            ByteBufCodecs.BOOL.decode(buf),
                            MutationProtocolCodecs.TEXT_CODEC.decode(buf),
                            STRING_LIST.decode(buf),
                            MutationProtocolCodecs.UUID_CODEC.decode(buf)
                    )
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
