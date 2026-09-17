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
        boolean isTerminal
) implements CustomPacketPayload {
    public static final Type<ClientboundDialogueOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "dialogue_open"));

    public static final StreamCodec<ByteBuf, ClientboundDialogueOpenPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ClientboundDialogueOpenPayload::dialogueId,
                    ByteBufCodecs.STRING_UTF8, ClientboundDialogueOpenPayload::nodeId,
                    ByteBufCodecs.STRING_UTF8, ClientboundDialogueOpenPayload::text,
                    ByteBufCodecs.STRING_UTF8, ClientboundDialogueOpenPayload::sound,
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ClientboundDialogueOpenPayload::options,
                    ByteBufCodecs.BOOL, ClientboundDialogueOpenPayload::isTerminal,
                    ClientboundDialogueOpenPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
