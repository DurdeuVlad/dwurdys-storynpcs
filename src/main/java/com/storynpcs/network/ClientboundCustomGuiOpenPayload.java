package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server → client: open an authored {@link com.storynpcs.creator.gui.CustomGuiLayout}
 * as a player-facing screen (issue #150, GuiCustom* parity). {@code layoutJson}
 * is the serialized authored layout — the client renders it verbatim and can
 * only commit button/input actions against {@code sessionId}, which the server
 * validates before publishing GUI action events.
 */
public record ClientboundCustomGuiOpenPayload(
        String layoutId,
        String layoutJson,
        UUID sessionId
) implements CustomPacketPayload {

    public ClientboundCustomGuiOpenPayload {
        layoutId = layoutId != null ? layoutId : "";
        layoutJson = layoutJson != null ? layoutJson : "";
        sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    public static final Type<ClientboundCustomGuiOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "custom_gui_open"));

    public static final StreamCodec<ByteBuf, ClientboundCustomGuiOpenPayload> STREAM_CODEC =
            MutationProtocolCodecs.rolePayload(StreamCodec.composite(
                    MutationProtocolCodecs.ID_CODEC, ClientboundCustomGuiOpenPayload::layoutId,
                    MutationProtocolCodecs.JSON_CODEC, ClientboundCustomGuiOpenPayload::layoutJson,
                    MutationProtocolCodecs.UUID_CODEC, ClientboundCustomGuiOpenPayload::sessionId,
                    ClientboundCustomGuiOpenPayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
