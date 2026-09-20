package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent from client to server for a bank vault action on a banker-role NPC.
 * Actions: {@code deposit_held} (uses tab), {@code withdraw} (uses tab+slot, whole stack),
 * {@code unlock_tab} (no tab/slot). Server re-validates proximity and vault rules.
 */
public record ServerboundBankActionPayload(
        String npcId,
        String action,
        int tab,
        int slot
) implements CustomPacketPayload {

    public ServerboundBankActionPayload {
        npcId = npcId != null ? npcId : "";
        action = action != null ? action : "";
    }

    public static final Type<ServerboundBankActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "bank_action"));

    public static final StreamCodec<ByteBuf, ServerboundBankActionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ServerboundBankActionPayload::npcId,
                    ByteBufCodecs.STRING_UTF8, ServerboundBankActionPayload::action,
                    ByteBufCodecs.VAR_INT, ServerboundBankActionPayload::tab,
                    ByteBufCodecs.VAR_INT, ServerboundBankActionPayload::slot,
                    ServerboundBankActionPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
