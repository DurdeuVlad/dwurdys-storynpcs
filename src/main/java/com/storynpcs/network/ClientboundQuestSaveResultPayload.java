package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent from server to client after processing a quest save/delete request.
 * On success carries the refreshed quest registry so the editor's list view
 * stays in sync without a manual reload.
 */
public record ClientboundQuestSaveResultPayload(
        boolean success,
        String message,
        String questsJson
) implements CustomPacketPayload {
    public static final int MAX_QUESTS_JSON_LENGTH = 4 << 20; // 4 MiB

    public ClientboundQuestSaveResultPayload {
        message = message != null ? message : "";
        questsJson = questsJson != null ? questsJson : "[]";
    }

    public static final Type<ClientboundQuestSaveResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "quest_save_result"));

    public static final StreamCodec<ByteBuf, ClientboundQuestSaveResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, ClientboundQuestSaveResultPayload::success,
                    ByteBufCodecs.STRING_UTF8, ClientboundQuestSaveResultPayload::message,
                    ByteBufCodecs.stringUtf8(MAX_QUESTS_JSON_LENGTH), ClientboundQuestSaveResultPayload::questsJson,
                    ClientboundQuestSaveResultPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
