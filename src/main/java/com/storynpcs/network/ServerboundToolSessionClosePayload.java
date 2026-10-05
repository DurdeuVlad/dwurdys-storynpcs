package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Client → server: the player dismissed an entity-targeted tool screen
 * (issue #148). Invalidates the server-issued entity session so a closed
 * book stops authorizing further edits; the session id must match the live
 * session for the kind, so stale closes can't clobber a newer session.
 */
public record ServerboundToolSessionClosePayload(
        String kind,
        UUID sessionId
) implements CustomPacketPayload {

    public ServerboundToolSessionClosePayload {
        kind = kind != null ? kind : "";
        sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    public static final Type<ServerboundToolSessionClosePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "tool_session_close"));

    public static final StreamCodec<ByteBuf, ServerboundToolSessionClosePayload> STREAM_CODEC =
            MutationProtocolCodecs.versioned(StreamCodec.of(
                    (buf, p) -> {
                        MutationProtocolCodecs.ID_CODEC.encode(buf, p.kind());
                        MutationProtocolCodecs.UUID_CODEC.encode(buf, p.sessionId());
                    },
                    buf -> new ServerboundToolSessionClosePayload(
                            MutationProtocolCodecs.ID_CODEC.decode(buf),
                            MutationProtocolCodecs.UUID_CODEC.decode(buf)
                    )
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
