package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent from server to client to open the QuestEditorScreen.
 * Carries the selected quest id (empty = browsable list mode) and the full
 * quest registry serialized as a JSON array (see {@link com.storynpcs.domain.quest.QuestSerde}).
 */
public record ClientboundQuestEditorOpenPayload(
        String questId,
        String questsJson
) implements CustomPacketPayload {
    public static final int MAX_QUESTS_JSON_LENGTH = 4 << 20; // 4 MiB — whole registry

    public ClientboundQuestEditorOpenPayload {
        questId = questId != null ? questId : "";
        questsJson = questsJson != null ? questsJson : "[]";
    }

    public static final Type<ClientboundQuestEditorOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "quest_editor_open"));

    public static final StreamCodec<ByteBuf, ClientboundQuestEditorOpenPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ClientboundQuestEditorOpenPayload::questId,
                    ByteBufCodecs.stringUtf8(MAX_QUESTS_JSON_LENGTH), ClientboundQuestEditorOpenPayload::questsJson,
                    ClientboundQuestEditorOpenPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
