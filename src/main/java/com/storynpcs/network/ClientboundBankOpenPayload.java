package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent from server to client to open the bank screen for a banker-role NPC.
 * Carries the {@link com.storynpcs.domain.role.banker.BankerRole} and the player's
 * {@link com.storynpcs.domain.role.banker.BankVault} as JSON.
 */
public record ClientboundBankOpenPayload(
        String npcId,
        String bankerJson,
        String vaultJson
) implements CustomPacketPayload {
    public static final int MAX_JSON_LENGTH = 1 << 20; // 1 MiB

    public ClientboundBankOpenPayload {
        npcId = npcId != null ? npcId : "";
        bankerJson = bankerJson != null ? bankerJson : "";
        vaultJson = vaultJson != null ? vaultJson : "";
    }

    public static final Type<ClientboundBankOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StoryNpcs.MOD_ID, "bank_open"));

    public static final StreamCodec<ByteBuf, ClientboundBankOpenPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ClientboundBankOpenPayload::npcId,
                    ByteBufCodecs.stringUtf8(MAX_JSON_LENGTH), ClientboundBankOpenPayload::bankerJson,
                    ByteBufCodecs.stringUtf8(MAX_JSON_LENGTH), ClientboundBankOpenPayload::vaultJson,
                    ClientboundBankOpenPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
