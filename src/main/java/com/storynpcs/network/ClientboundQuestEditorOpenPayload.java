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
        String questsJson,
        long revision
) implements CustomPacketPayload {
    public static final int MAX_QUESTS_JSON_LENGTH = 4 << 20; // 4 MiB — whole registry

    public ClientboundQuestEditorOpenPayload {
        questId = questId != null ? questId : "";
        questsJson = questsJson != null ? questsJson : "[]";
        if (revision < 0) revision = 0;
    }

    public ClientboundQuestEditorOpenPayload(String questId, String questsJson) {
        this(questId, questsJson, 0L);
    }

    public static final Type<ClientboundQuestEditorOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "quest_editor_open"));

    public static final StreamCodec<ByteBuf, ClientboundQuestEditorOpenPayload> STREAM_CODEC =
            MutationProtocolCodecs.registryPayload(StreamCodec.composite(
                    MutationProtocolCodecs.ID_CODEC, ClientboundQuestEditorOpenPayload::questId,
                    MutationProtocolCodecs.REGISTRY_JSON_CODEC, ClientboundQuestEditorOpenPayload::questsJson,
                    ByteBufCodecs.VAR_LONG, ClientboundQuestEditorOpenPayload::revision,
                    ClientboundQuestEditorOpenPayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
