package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record ServerboundDialogueChoosePayload(int optionIndex, UUID sessionId, UUID requestId) implements CustomPacketPayload {
    public ServerboundDialogueChoosePayload {
        sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
        requestId = requestId != null ? requestId : UUID.randomUUID();
    }

    /** Compatibility constructor for old callers; server validation requires a real session token. */
    public ServerboundDialogueChoosePayload(int optionIndex) {
        this(optionIndex, new UUID(0L, 0L), UUID.randomUUID());
    }

    public static final Type<ServerboundDialogueChoosePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "dialogue_choose"));

    public static final StreamCodec<ByteBuf, ServerboundDialogueChoosePayload> STREAM_CODEC =
            MutationProtocolCodecs.versioned(StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    ServerboundDialogueChoosePayload::optionIndex,
                    MutationProtocolCodecs.UUID_CODEC,
                    ServerboundDialogueChoosePayload::sessionId,
                    MutationProtocolCodecs.UUID_CODEC,
                    ServerboundDialogueChoosePayload::requestId,
                    ServerboundDialogueChoosePayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
