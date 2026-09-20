package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent from the client faction editor to delete a faction definition
 * (registry + YAML file) via the application service.
 */
public record ServerboundFactionDeletePayload(
        String factionId
) implements CustomPacketPayload {

    public ServerboundFactionDeletePayload {
        factionId = factionId != null ? factionId : "";
    }

    public static final Type<ServerboundFactionDeletePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "faction_editor_delete"));

    public static final StreamCodec<ByteBuf, ServerboundFactionDeletePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ServerboundFactionDeletePayload::factionId,
                    ServerboundFactionDeletePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
