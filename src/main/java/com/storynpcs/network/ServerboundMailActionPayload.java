package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Client → server mailbox action (issue #150). {@code action} is one of
 * {@code mark_read}, {@code delete}, {@code send}. For mark_read/delete,
 * {@code target} is the mail message id; for send it is the recipient player
 * name, and {@code subject}/{@code body} carry the content. The action is
 * authorized by the panel {@code sessionId} + idempotent {@code requestId},
 * never by trusting the payload.
 */
public record ServerboundMailActionPayload(
        UUID sessionId,
        UUID requestId,
        String action,
        String target,
        String subject,
        String body
) implements CustomPacketPayload {

    public static final String ACTION_MARK_READ = "mark_read";
    public static final String ACTION_DELETE = "delete";
    public static final String ACTION_SEND = "send";

    public ServerboundMailActionPayload {
        sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
        requestId = requestId != null ? requestId : UUID.randomUUID();
        action = action != null ? action : "";
        target = target != null ? target : "";
        subject = subject != null ? subject : "";
        body = body != null ? body : "";
    }

    /** Convenience for mark_read/delete actions (no content fields). */
    public ServerboundMailActionPayload(UUID sessionId, String action, String target) {
        this(sessionId, UUID.randomUUID(), action, target, "", "");
    }

    public static final Type<ServerboundMailActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "mail_action"));

    public static final StreamCodec<ByteBuf, ServerboundMailActionPayload> STREAM_CODEC =
            MutationProtocolCodecs.rolePayload(StreamCodec.composite(
                    MutationProtocolCodecs.UUID_CODEC, ServerboundMailActionPayload::sessionId,
                    MutationProtocolCodecs.UUID_CODEC, ServerboundMailActionPayload::requestId,
                    MutationProtocolCodecs.ID_CODEC, ServerboundMailActionPayload::action,
                    MutationProtocolCodecs.ID_CODEC, ServerboundMailActionPayload::target,
                    MutationProtocolCodecs.TEXT_CODEC, ServerboundMailActionPayload::subject,
                    MutationProtocolCodecs.MESSAGE_CODEC, ServerboundMailActionPayload::body,
                    ServerboundMailActionPayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
