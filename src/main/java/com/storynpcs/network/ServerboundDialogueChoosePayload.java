package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Player dialogue choice. The choice is authorized by an opaque single-use
 * server-issued token (P5-2) — never by option index. {@code choiceToken} is
 * the string form of the token UUID issued in {@link ClientboundDialogueOpenPayload}.
 */
public record ServerboundDialogueChoosePayload(UUID sessionId, UUID requestId, String choiceToken)
        implements CustomPacketPayload {
    public ServerboundDialogueChoosePayload {
        sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
        requestId = requestId != null ? requestId : UUID.randomUUID();
        choiceToken = choiceToken != null ? choiceToken : "";
    }

    /** Compatibility constructor for callers without a session binding. */
    public ServerboundDialogueChoosePayload(String choiceToken) {
        this(new UUID(0L, 0L), UUID.randomUUID(), choiceToken);
    }

    public static final Type<ServerboundDialogueChoosePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "dialogue_choose"));

    public static final StreamCodec<ByteBuf, ServerboundDialogueChoosePayload> STREAM_CODEC =
            MutationProtocolCodecs.versioned(StreamCodec.of(
                    (buf, p) -> {
                        MutationProtocolCodecs.UUID_CODEC.encode(buf, p.sessionId());
                        MutationProtocolCodecs.UUID_CODEC.encode(buf, p.requestId());
                        MutationProtocolCodecs.TEXT_CODEC.encode(buf, p.choiceToken());
                    },
                    buf -> new ServerboundDialogueChoosePayload(
                            MutationProtocolCodecs.UUID_CODEC.decode(buf),
                            MutationProtocolCodecs.UUID_CODEC.decode(buf),
                            MutationProtocolCodecs.TEXT_CODEC.decode(buf)
                    )
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
