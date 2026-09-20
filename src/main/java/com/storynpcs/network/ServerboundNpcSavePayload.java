package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent from client editor to server to validate, persist, and apply an edited NPC definition.
 */
public record ServerboundNpcSavePayload(
        String npcId,
        String npcJson
) implements CustomPacketPayload {
    public static final int MAX_NPC_JSON_LENGTH = 1 << 20; // 1 MiB

    public ServerboundNpcSavePayload {
        npcId = npcId != null ? npcId : "";
        npcJson = npcJson != null ? npcJson : "";
    }

    public static final Type<ServerboundNpcSavePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "npc_editor_save"));

    public static final StreamCodec<ByteBuf, ServerboundNpcSavePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ServerboundNpcSavePayload::npcId,
                    ByteBufCodecs.stringUtf8(MAX_NPC_JSON_LENGTH), ServerboundNpcSavePayload::npcJson,
                    ServerboundNpcSavePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
