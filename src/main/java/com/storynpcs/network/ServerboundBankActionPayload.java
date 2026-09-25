package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Sent from client to server for a bank vault action on a banker-role NPC.
 * Actions: {@code deposit_held} (uses tab), {@code withdraw} (uses tab+slot, whole stack),
 * {@code unlock_tab} (no tab/slot). Server re-validates proximity and vault rules.
 */
public record ServerboundBankActionPayload(
        String npcId,
        String action,
        int tab,
        int slot,
        UUID sessionId,
        UUID requestId
) implements CustomPacketPayload {

    public ServerboundBankActionPayload {
        npcId = npcId != null ? npcId : "";
        action = action != null ? action : "";
        sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
        requestId = requestId != null ? requestId : UUID.randomUUID();
    }

    public ServerboundBankActionPayload(String npcId, String action, int tab, int slot) {
        this(npcId, action, tab, slot, new UUID(0L, 0L), UUID.randomUUID());
    }

    public static final Type<ServerboundBankActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "bank_action"));

    public static final StreamCodec<ByteBuf, ServerboundBankActionPayload> STREAM_CODEC =
            MutationProtocolCodecs.versioned(StreamCodec.composite(
                    MutationProtocolCodecs.ID_CODEC, ServerboundBankActionPayload::npcId,
                    MutationProtocolCodecs.ID_CODEC, ServerboundBankActionPayload::action,
                    ByteBufCodecs.VAR_INT, ServerboundBankActionPayload::tab,
                    ByteBufCodecs.VAR_INT, ServerboundBankActionPayload::slot,
                    MutationProtocolCodecs.UUID_CODEC, ServerboundBankActionPayload::sessionId,
                    MutationProtocolCodecs.UUID_CODEC, ServerboundBankActionPayload::requestId,
                    ServerboundBankActionPayload::new
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
