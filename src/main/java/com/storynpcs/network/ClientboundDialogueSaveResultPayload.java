package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server's verdict on a {@link ServerboundDialogueSavePayload} — lets the editor's
 * status bar show the real outcome instead of a dishonest "saved" message.
 */
public record ClientboundDialogueSaveResultPayload(
        boolean success,
        String message,
        String code,
        UUID requestId,
        long revision
) implements CustomPacketPayload {
    public ClientboundDialogueSaveResultPayload {
        message = message != null ? message : "";
        code = code != null ? code : (success ? "APPLIED" : "REJECTED");
        requestId = requestId != null ? requestId : new UUID(0L, 0L);
        if (revision < 0) revision = 0;
    }

    public ClientboundDialogueSaveResultPayload(boolean success, String message) {
        this(success, message, success ? "APPLIED" : "REJECTED", new UUID(0L, 0L), 0L);
    }

    public static final Type<ClientboundDialogueSaveResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "dialogue_save_result"));

    public static final StreamCodec<ByteBuf, ClientboundDialogueSaveResultPayload> STREAM_CODEC =
            MutationProtocolCodecs.versioned(StreamCodec.composite(
                    ByteBufCodecs.BOOL, ClientboundDialogueSaveResultPayload::success,
                    MutationProtocolCodecs.MESSAGE_CODEC, ClientboundDialogueSaveResultPayload::message,
                    MutationProtocolCodecs.ID_CODEC, ClientboundDialogueSaveResultPayload::code,
                    MutationProtocolCodecs.UUID_CODEC, ClientboundDialogueSaveResultPayload::requestId,
                    ByteBufCodecs.VAR_LONG, ClientboundDialogueSaveResultPayload::revision,
                    ClientboundDialogueSaveResultPayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
