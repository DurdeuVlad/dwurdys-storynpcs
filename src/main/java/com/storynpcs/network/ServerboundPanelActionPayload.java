package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Client → server panel commit (issue #150). Covers the bounded set of
 * player-panel writes that are not mail: {@code panel} is one of
 * {@link com.storynpcs.service.PlayerPanelViews#PANEL_IDS}, {@code action}
 * the panel's verb (e.g. {@code hire}, {@code craft}), {@code target} the
 * subject id (entity UUID for hire, recipe id for craft). Every commit is
 * authorized by the panel {@code sessionId} + idempotent {@code requestId};
 * the server re-validates everything against live state.
 */
public record ServerboundPanelActionPayload(
        UUID sessionId,
        UUID requestId,
        String panel,
        String action,
        String target
) implements CustomPacketPayload {

    public static final String ACTION_HIRE = "hire";
    public static final String ACTION_CRAFT = "craft";

    public ServerboundPanelActionPayload {
        sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
        requestId = requestId != null ? requestId : UUID.randomUUID();
        panel = panel != null ? panel : "";
        action = action != null ? action : "";
        target = target != null ? target : "";
    }

    public ServerboundPanelActionPayload(UUID sessionId, String panel,
                                         String action, String target) {
        this(sessionId, UUID.randomUUID(), panel, action, target);
    }

    public static final Type<ServerboundPanelActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "panel_action"));

    public static final StreamCodec<ByteBuf, ServerboundPanelActionPayload> STREAM_CODEC =
            MutationProtocolCodecs.rolePayload(StreamCodec.composite(
                    MutationProtocolCodecs.UUID_CODEC, ServerboundPanelActionPayload::sessionId,
                    MutationProtocolCodecs.UUID_CODEC, ServerboundPanelActionPayload::requestId,
                    MutationProtocolCodecs.ID_CODEC, ServerboundPanelActionPayload::panel,
                    MutationProtocolCodecs.ID_CODEC, ServerboundPanelActionPayload::action,
                    MutationProtocolCodecs.ID_CODEC, ServerboundPanelActionPayload::target,
                    ServerboundPanelActionPayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
