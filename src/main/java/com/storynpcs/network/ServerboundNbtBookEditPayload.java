package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Client → server: one allowlisted NBT-book edit (issue #148). Authorized by
 * the server-issued entity session issued in
 * {@link ClientboundNbtBookOpenPayload}, then re-checked against the player's
 * permission level and the write allowlist — the payload alone grants nothing.
 */
public record ServerboundNbtBookEditPayload(
        UUID sessionId,
        int entityId,
        String path,
        String value
) implements CustomPacketPayload {

    public ServerboundNbtBookEditPayload {
        sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
        path = path != null ? path : "";
        value = value != null ? value : "";
    }

    public static final Type<ServerboundNbtBookEditPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "nbt_book_edit"));

    public static final StreamCodec<ByteBuf, ServerboundNbtBookEditPayload> STREAM_CODEC =
            MutationProtocolCodecs.versioned(StreamCodec.of(
                    (buf, p) -> {
                        MutationProtocolCodecs.UUID_CODEC.encode(buf, p.sessionId());
                        ByteBufCodecs.VAR_INT.encode(buf, p.entityId());
                        MutationProtocolCodecs.TEXT_CODEC.encode(buf, p.path());
                        MutationProtocolCodecs.TEXT_CODEC.encode(buf, p.value());
                    },
                    buf -> new ServerboundNbtBookEditPayload(
                            MutationProtocolCodecs.UUID_CODEC.decode(buf),
                            ByteBufCodecs.VAR_INT.decode(buf),
                            MutationProtocolCodecs.TEXT_CODEC.decode(buf),
                            MutationProtocolCodecs.TEXT_CODEC.decode(buf)
                    )
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
