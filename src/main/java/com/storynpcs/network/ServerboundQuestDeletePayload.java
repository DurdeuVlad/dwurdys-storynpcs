package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent from the client quest editor to delete a quest definition
 * (registry + YAML file) via the application service.
 */
public record ServerboundQuestDeletePayload(
        String questId
) implements CustomPacketPayload {

    public ServerboundQuestDeletePayload {
        questId = questId != null ? questId : "";
    }

    public static final Type<ServerboundQuestDeletePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "quest_editor_delete"));

    public static final StreamCodec<ByteBuf, ServerboundQuestDeletePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ServerboundQuestDeletePayload::questId,
                    ServerboundQuestDeletePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
