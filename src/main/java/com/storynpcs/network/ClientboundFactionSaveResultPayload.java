package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent from server to client after processing a faction save/delete request.
 * On success carries the refreshed faction registry so the editor's list view
 * stays in sync without a manual reload.
 */
public record ClientboundFactionSaveResultPayload(
        boolean success,
        String message,
        String factionsJson
) implements CustomPacketPayload {
    public static final int MAX_FACTIONS_JSON_LENGTH = 4 << 20; // 4 MiB

    public ClientboundFactionSaveResultPayload {
        message = message != null ? message : "";
        factionsJson = factionsJson != null ? factionsJson : "[]";
    }

    public static final Type<ClientboundFactionSaveResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "faction_save_result"));

    public static final StreamCodec<ByteBuf, ClientboundFactionSaveResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, ClientboundFactionSaveResultPayload::success,
                    ByteBufCodecs.STRING_UTF8, ClientboundFactionSaveResultPayload::message,
                    ByteBufCodecs.stringUtf8(MAX_FACTIONS_JSON_LENGTH), ClientboundFactionSaveResultPayload::factionsJson,
                    ClientboundFactionSaveResultPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
