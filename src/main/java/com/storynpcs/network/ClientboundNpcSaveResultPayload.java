package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Sent from server to client after processing a {@link ServerboundNpcSavePayload}.
 */
public record ClientboundNpcSaveResultPayload(
        boolean success,
        String message,
        String code,
        UUID requestId,
        long revision
) implements CustomPacketPayload {

    public ClientboundNpcSaveResultPayload {
        message = message != null ? message : "";
        code = code != null ? code : (success ? "APPLIED" : "REJECTED");
        requestId = requestId != null ? requestId : new UUID(0L, 0L);
        if (revision < 0) revision = 0;
    }

    public ClientboundNpcSaveResultPayload(boolean success, String message) {
        this(success, message, success ? "APPLIED" : "REJECTED", new UUID(0L, 0L), 0L);
    }

    public static final Type<ClientboundNpcSaveResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "npc_save_result"));

    public static final StreamCodec<ByteBuf, ClientboundNpcSaveResultPayload> STREAM_CODEC =
            MutationProtocolCodecs.versioned(StreamCodec.composite(
                    ByteBufCodecs.BOOL, ClientboundNpcSaveResultPayload::success,
                    MutationProtocolCodecs.MESSAGE_CODEC, ClientboundNpcSaveResultPayload::message,
                    MutationProtocolCodecs.ID_CODEC, ClientboundNpcSaveResultPayload::code,
                    MutationProtocolCodecs.UUID_CODEC, ClientboundNpcSaveResultPayload::requestId,
                    ByteBufCodecs.VAR_LONG, ClientboundNpcSaveResultPayload::revision,
                    ClientboundNpcSaveResultPayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
