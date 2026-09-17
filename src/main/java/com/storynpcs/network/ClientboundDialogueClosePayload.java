package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ClientboundDialogueClosePayload() implements CustomPacketPayload {
    public static final Type<ClientboundDialogueClosePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "dialogue_close"));

    public static final ClientboundDialogueClosePayload INSTANCE = new ClientboundDialogueClosePayload();

    public static final StreamCodec<ByteBuf, ClientboundDialogueClosePayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
