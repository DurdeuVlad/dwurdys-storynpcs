package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record ClientboundDialogueOpenPayload(
        String dialogueId,
        String nodeId,
        String text,
        String sound,
        List<String> options,
        boolean isTerminal,
        String npcName,
        List<String> optionHints
) implements CustomPacketPayload {
    public ClientboundDialogueOpenPayload {
        dialogueId = dialogueId != null ? dialogueId : "";
        nodeId = nodeId != null ? nodeId : "";
        text = text != null ? text : "";
        sound = sound != null ? sound : "";
        options = options != null ? options.stream().map(s -> s != null ? s : "").toList() : List.of();
        npcName = npcName != null ? npcName : "";
        optionHints = optionHints != null ? optionHints.stream().map(s -> s != null ? s : "").toList() : List.of();
    }
    public static final Type<ClientboundDialogueOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "dialogue_open"));

    // composite() maxes out at 6 fields — explicit codec for the 8-field payload
    private static final StreamCodec<ByteBuf, List<String>> STRING_LIST =
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list());

    public static final StreamCodec<ByteBuf, ClientboundDialogueOpenPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        ByteBufCodecs.STRING_UTF8.encode(buf, p.dialogueId());
                        ByteBufCodecs.STRING_UTF8.encode(buf, p.nodeId());
                        ByteBufCodecs.STRING_UTF8.encode(buf, p.text());
                        ByteBufCodecs.STRING_UTF8.encode(buf, p.sound());
                        STRING_LIST.encode(buf, p.options());
                        ByteBufCodecs.BOOL.encode(buf, p.isTerminal());
                        ByteBufCodecs.STRING_UTF8.encode(buf, p.npcName());
                        STRING_LIST.encode(buf, p.optionHints());
                    },
                    buf -> new ClientboundDialogueOpenPayload(
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            STRING_LIST.decode(buf),
                            ByteBufCodecs.BOOL.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            STRING_LIST.decode(buf)
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
