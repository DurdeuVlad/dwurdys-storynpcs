package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Client → server authored-custom-GUI action (issue #150, target GuiCustom
 * button/text callbacks). {@code elementPath} is the flattened element index
 * of the button that was pressed; {@code inputsJson} carries the collected
 * input-element values. Authorized by the panel {@code sessionId} + idempotent
 * {@code requestId}; the server publishes a
 * {@link com.storynpcs.api.event.gui.CustomGuiActionEvent} only after session
 * validation succeeds.
 */
public record ServerboundCustomGuiActionPayload(
        UUID sessionId,
        UUID requestId,
        String layoutId,
        String elementPath,
        String inputsJson
) implements CustomPacketPayload {

    public ServerboundCustomGuiActionPayload {
        sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
        requestId = requestId != null ? requestId : UUID.randomUUID();
        layoutId = layoutId != null ? layoutId : "";
        elementPath = elementPath != null ? elementPath : "";
        inputsJson = inputsJson != null ? inputsJson : "";
    }

    public static final Type<ServerboundCustomGuiActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "custom_gui_action"));

    public static final StreamCodec<ByteBuf, ServerboundCustomGuiActionPayload> STREAM_CODEC =
            MutationProtocolCodecs.rolePayload(StreamCodec.composite(
                    MutationProtocolCodecs.UUID_CODEC, ServerboundCustomGuiActionPayload::sessionId,
                    MutationProtocolCodecs.UUID_CODEC, ServerboundCustomGuiActionPayload::requestId,
                    MutationProtocolCodecs.ID_CODEC, ServerboundCustomGuiActionPayload::layoutId,
                    MutationProtocolCodecs.ID_CODEC, ServerboundCustomGuiActionPayload::elementPath,
                    MutationProtocolCodecs.JSON_CODEC, ServerboundCustomGuiActionPayload::inputsJson,
                    ServerboundCustomGuiActionPayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
