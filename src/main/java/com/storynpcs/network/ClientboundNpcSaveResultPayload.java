package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent from server to client after processing a {@link ServerboundNpcSavePayload}.
 */
public record ClientboundNpcSaveResultPayload(
        boolean success,
        String message
) implements CustomPacketPayload {

    public ClientboundNpcSaveResultPayload {
        message = message != null ? message : "";
    }

    public static final Type<ClientboundNpcSaveResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "npc_save_result"));

    public static final StreamCodec<ByteBuf, ClientboundNpcSaveResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, ClientboundNpcSaveResultPayload::success,
                    ByteBufCodecs.STRING_UTF8, ClientboundNpcSaveResultPayload::message,
                    ClientboundNpcSaveResultPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
