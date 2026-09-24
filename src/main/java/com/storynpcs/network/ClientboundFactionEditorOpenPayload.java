package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent from server to client to open the FactionEditorScreen.
 * Carries the selected faction id (empty = browsable list mode) and the full
 * faction registry serialized as a JSON array (see {@link com.storynpcs.domain.faction.FactionSerde}).
 */
public record ClientboundFactionEditorOpenPayload(
        String factionId,
        String factionsJson,
        long revision
) implements CustomPacketPayload {
    public static final int MAX_FACTIONS_JSON_LENGTH = 4 << 20; // 4 MiB

    public ClientboundFactionEditorOpenPayload {
        factionId = factionId != null ? factionId : "";
        factionsJson = factionsJson != null ? factionsJson : "[]";
        if (revision < 0) revision = 0;
    }

    public ClientboundFactionEditorOpenPayload(String factionId, String factionsJson) {
        this(factionId, factionsJson, 0L);
    }

    public static final Type<ClientboundFactionEditorOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "faction_editor_open"));

    public static final StreamCodec<ByteBuf, ClientboundFactionEditorOpenPayload> STREAM_CODEC =
            MutationProtocolCodecs.registryPayload(StreamCodec.composite(
                    MutationProtocolCodecs.ID_CODEC, ClientboundFactionEditorOpenPayload::factionId,
                    MutationProtocolCodecs.REGISTRY_JSON_CODEC, ClientboundFactionEditorOpenPayload::factionsJson,
                    ByteBufCodecs.VAR_LONG, ClientboundFactionEditorOpenPayload::revision,
                    ClientboundFactionEditorOpenPayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
