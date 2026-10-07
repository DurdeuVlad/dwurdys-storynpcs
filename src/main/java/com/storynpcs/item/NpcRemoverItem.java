package com.storynpcs.item;

import com.storynpcs.entity.StoryNpcEntity;
import com.storynpcs.StoryNpcsAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Remover tool (P8-2): despawns a live NPC entity. Destructive, so it is a
 * two-click operation — the first click arms a bounded confirmation and the
 * second click (within the TTL) executes. The authored YAML definition is
 * untouched; only the live entity instance is removed.
 */
public class NpcRemoverItem extends Item implements CreatorToolItem {

    private static final long CONFIRM_TTL_MILLIS = 10_000;

    public NpcRemoverItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                  LivingEntity target, InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer) || !serverPlayer.hasPermissions(2)) {
            return InteractionResult.PASS;
        }
        if (!(target instanceof StoryNpcEntity npc) || !npc.isAlive()) {
            return InteractionResult.PASS;
        }
        var mod = StoryNpcsAccess.mod(serverPlayer);
        var sessions = mod.getRuntimeSessions(serverPlayer.getServer());
        long now = System.currentTimeMillis();
        if (sessions.consumeToolConfirmation(serverPlayer.getUUID(), "remover",
                npc.getId(), now) != null) {
            String name = npc.getName().getString();
            npc.discard();
            CreatorToolAudit.publish(mod, serverPlayer, "remover", "despawn", name, "applied");
            serverPlayer.sendSystemMessage(Component.literal(String.format(
                    "§e[StoryNPCs Remover] Despawned '§f%s§e' (definition remains in content).", name)));
            return InteractionResult.SUCCESS;
        }
        sessions.armToolConfirmation(serverPlayer.getUUID(), "remover",
                npc.getId(), CONFIRM_TTL_MILLIS);
        serverPlayer.sendSystemMessage(Component.literal(String.format(
                "§e[StoryNPCs Remover] Click '§f%s§e' again within %d seconds to despawn it. The definition is preserved.",
                npc.getName().getString(), CONFIRM_TTL_MILLIS / 1000)));
        return InteractionResult.SUCCESS;
    }
}
