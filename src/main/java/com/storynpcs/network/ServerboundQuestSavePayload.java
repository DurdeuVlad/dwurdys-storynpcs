package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent from the client quest editor to the server to validate, persist, and
 * apply an edited (or newly authored) quest definition.
 */
public record ServerboundQuestSavePayload(
        String questJson
) implements CustomPacketPayload {
    public static final int MAX_QUEST_JSON_LENGTH = 1 << 20; // 1 MiB

    public ServerboundQuestSavePayload {
        questJson = questJson != null ? questJson : "";
    }

    public static final Type<ServerboundQuestSavePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "quest_editor_save"));

    public static final StreamCodec<ByteBuf, ServerboundQuestSavePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(MAX_QUEST_JSON_LENGTH), ServerboundQuestSavePayload::questJson,
                    ServerboundQuestSavePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
