package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server → client: open (or refresh) a player-facing panel (issue #150).
 * {@code panel} is one of {@link com.storynpcs.service.PlayerPanelViews#PANEL_IDS};
 * {@code viewJson} is the serialized view model. {@code sessionId} is the
 * server-issued token — commits against a stale session fail closed.
 */
public record ClientboundPlayerPanelPayload(
        String panel,
        String viewJson,
        UUID sessionId
) implements CustomPacketPayload {

    public ClientboundPlayerPanelPayload {
        panel = panel != null ? panel : "";
        viewJson = viewJson != null ? viewJson : "";
        sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    public static final Type<ClientboundPlayerPanelPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "player_panel"));

    public static final StreamCodec<ByteBuf, ClientboundPlayerPanelPayload> STREAM_CODEC =
            MutationProtocolCodecs.rolePayload(StreamCodec.composite(
                    MutationProtocolCodecs.ID_CODEC, ClientboundPlayerPanelPayload::panel,
                    MutationProtocolCodecs.JSON_CODEC, ClientboundPlayerPanelPayload::viewJson,
                    MutationProtocolCodecs.UUID_CODEC, ClientboundPlayerPanelPayload::sessionId,
                    ClientboundPlayerPanelPayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
