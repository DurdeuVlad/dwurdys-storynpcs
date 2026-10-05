package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server → client: opens the NBT book viewer for an entity (issue #148).
 * {@code entriesJson} carries the flattened {@link com.storynpcs.domain.support.NbtBookService.NbtEntry}
 * list; {@code canEdit} mirrors the server's permission check so the client
 * can hide the edit affordance entirely.
 */
public record ClientboundNbtBookOpenPayload(
        int entityId,
        String displayName,
        String entriesJson,
        boolean canEdit,
        UUID sessionId
) implements CustomPacketPayload {

    public ClientboundNbtBookOpenPayload {
        displayName = displayName != null ? displayName : "";
        entriesJson = entriesJson != null ? entriesJson : "[]";
        sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    public static final Type<ClientboundNbtBookOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "nbt_book_open"));

    public static final StreamCodec<ByteBuf, ClientboundNbtBookOpenPayload> STREAM_CODEC =
            MutationProtocolCodecs.editorPayload(StreamCodec.of(
                    (buf, p) -> {
                        ByteBufCodecs.VAR_INT.encode(buf, p.entityId());
                        MutationProtocolCodecs.TEXT_CODEC.encode(buf, p.displayName());
                        MutationProtocolCodecs.JSON_CODEC.encode(buf, p.entriesJson());
                        ByteBufCodecs.BOOL.encode(buf, p.canEdit());
                        MutationProtocolCodecs.UUID_CODEC.encode(buf, p.sessionId());
                    },
                    buf -> new ClientboundNbtBookOpenPayload(
                            ByteBufCodecs.VAR_INT.decode(buf),
                            MutationProtocolCodecs.TEXT_CODEC.decode(buf),
                            MutationProtocolCodecs.JSON_CODEC.decode(buf),
                            ByteBufCodecs.BOOL.decode(buf),
                            MutationProtocolCodecs.UUID_CODEC.decode(buf)
                    )
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
