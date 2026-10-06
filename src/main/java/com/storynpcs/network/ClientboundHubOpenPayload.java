package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: open the authoring hub (P10-1). Carries no payload — the
 * hub is a navigation shell; every panel it routes to loads its own data via
 * the existing open payloads, so a stale or replayed hub open is harmless.
 */
public final class ClientboundHubOpenPayload implements CustomPacketPayload {

    public static final ClientboundHubOpenPayload INSTANCE = new ClientboundHubOpenPayload();

    public static final Type<ClientboundHubOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "hub_open"));

    public static final StreamCodec<ByteBuf, ClientboundHubOpenPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    private ClientboundHubOpenPayload() {}

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
