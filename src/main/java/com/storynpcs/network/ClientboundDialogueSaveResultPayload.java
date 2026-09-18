package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server's verdict on a {@link ServerboundDialogueSavePayload} — lets the editor's
 * status bar show the real outcome instead of a dishonest "saved" message.
 */
public record ClientboundDialogueSaveResultPayload(
        boolean success,
        String message
) implements CustomPacketPayload {
    public ClientboundDialogueSaveResultPayload {
        message = message != null ? message : "";
    }

    public static final Type<ClientboundDialogueSaveResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "dialogue_save_result"));

    public static final StreamCodec<ByteBuf, ClientboundDialogueSaveResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, ClientboundDialogueSaveResultPayload::success,
                    ByteBufCodecs.STRING_UTF8, ClientboundDialogueSaveResultPayload::message,
                    ClientboundDialogueSaveResultPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
