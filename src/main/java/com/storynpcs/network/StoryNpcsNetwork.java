package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import com.storynpcs.service.DialogueView;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class StoryNpcsNetwork {
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(StoryNpcs.MOD_ID).versioned("1.0.0");

        registrar.playToServer(
                ServerboundDialogueChoosePayload.TYPE,
                ServerboundDialogueChoosePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        context.enqueueWork(() -> handleDialogueChoose(serverPlayer, payload));
                    }
                }
        );

        registrar.playToClient(
                ClientboundDialogueOpenPayload.TYPE,
                ClientboundDialogueOpenPayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> com.storynpcs.client.StoryNpcsClient.openDialogue(payload));
                }
        );

        registrar.playToClient(
                ClientboundDialogueClosePayload.TYPE,
                ClientboundDialogueClosePayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(com.storynpcs.client.StoryNpcsClient::closeDialogue);
                }
        );
    }

    private static void handleDialogueChoose(ServerPlayer player, ServerboundDialogueChoosePayload payload) {
        var service = StoryNpcs.getInstance().getApplicationService();
        if (service != null) {
            DialogueView view = service.chooseDialogueOption(player.getUUID(), payload.optionIndex());
            if (view.isTerminal() && view.nodeId().isEmpty()) {
                sendCloseDialogue(player);
            } else {
                sendOpenDialogue(player, view);
            }
        }
    }

    public static void sendOpenDialogue(ServerPlayer player, DialogueView view) {
        PacketDistributor.sendToPlayer(
                player,
                new ClientboundDialogueOpenPayload(
                        view.dialogueId() != null ? view.dialogueId().toString() : "",
                        view.nodeId(),
                        view.text(),
                        view.sound(),
                        view.options(),
                        view.isTerminal()
                )
        );
    }

    public static void sendCloseDialogue(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, ClientboundDialogueClosePayload.INSTANCE);
    }
}
