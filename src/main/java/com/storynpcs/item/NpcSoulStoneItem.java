package com.storynpcs.item;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.StoryNpcsAccess;
import com.storynpcs.entity.StoryNpcEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Soulstone tool (P8-2): binds a live NPC's authored definition id into the
 * stone (two-click confirm — the first click previews, the second captures
 * and despawns the live instance). Right-clicking a block with a charged
 * stone redeploys the NPC there via the entity factory. The authored YAML
 * definition is only read, never mutated.
 */
public class NpcSoulStoneItem extends Item implements CreatorToolItem {

    private static final long CONFIRM_TTL_MILLIS = 10_000;
    private static final String BOUND_NPC_KEY = "storynpcs_soulstone_npc";

    public NpcSoulStoneItem(Properties properties) {
        super(properties);
    }

    private static String boundNpcId(ItemStack stack) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        var tag = data.copyTag();
        String id = tag.getString(BOUND_NPC_KEY);
        return id == null || id.isEmpty() ? null : id;
    }

    private static void bindNpcId(ItemStack stack, String id) {
        CompoundTag tag = new CompoundTag();
        tag.putString(BOUND_NPC_KEY, id);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
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
        if (sessions.consumeToolConfirmation(serverPlayer.getUUID(), "soulstone",
                npc.getId(), now) == null) {
            sessions.armToolConfirmation(serverPlayer.getUUID(), "soulstone",
                    npc.getId(), CONFIRM_TTL_MILLIS);
            serverPlayer.sendSystemMessage(Component.literal(String.format(
                    "§e[StoryNPCs Soulstone] Click '§f%s§e' again within %d seconds to capture it into the stone (despawns the live NPC).",
                    npc.getName().getString(), CONFIRM_TTL_MILLIS / 1000)));
            return InteractionResult.SUCCESS;
        }
        String npcId = npc.getDefinitionId();
        if (npcId == null || npcId.isEmpty()) {
            serverPlayer.sendSystemMessage(Component.literal(
                    "§c[StoryNPCs Soulstone] This NPC has no authored definition — nothing to bind."));
            return InteractionResult.FAIL;
        }
        bindNpcId(stack, npcId);
        String name = npc.getName().getString();
        npc.discard();
        CreatorToolAudit.publish(mod, serverPlayer, "soulstone", "capture", npcId, "applied");
        serverPlayer.sendSystemMessage(Component.literal(String.format(
                "§a[StoryNPCs Soulstone] Captured '§f%s§a' (%s) — right-click a block to redeploy.",
                name, npcId)));
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (!(player instanceof ServerPlayer serverPlayer) || !serverPlayer.hasPermissions(2)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.PASS;
        }
        String boundId = boundNpcId(context.getItemInHand());
        var mod = StoryNpcsAccess.mod(serverPlayer);
        if (boundId == null) {
            serverPlayer.sendSystemMessage(Component.literal(
                    "§e[StoryNPCs Soulstone] Empty — click an NPC twice to capture its definition binding."));
            return InteractionResult.FAIL;
        }
        var pos = context.getClickedPos().relative(context.getClickedFace());
        NamespacedId npcId = NamespacedId.of(boundId);
        if (mod.getRegistry().getNpc(npcId).isEmpty() || !(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            CreatorToolAudit.publish(mod, serverPlayer, "soulstone", "deploy", boundId,
                    "rejected:NO_DEFINITION");
            serverPlayer.sendSystemMessage(Component.literal(String.format(
                    "§c[StoryNPCs Soulstone] Definition '%s' no longer exists — the binding is stale.",
                    boundId)));
            return InteractionResult.FAIL;
        }
        var spawned = com.storynpcs.entity.StoryNpcRegistry.STORY_NPC.get().create(serverLevel);
        if (spawned == null) {
            return InteractionResult.FAIL;
        }
        spawned.setDefinitionId(boundId);
        spawned.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                level.random.nextFloat() * 360.0f, 0.0f);
        if (!serverLevel.addFreshEntity(spawned)) {
            CreatorToolAudit.publish(mod, serverPlayer, "soulstone", "deploy", boundId,
                    "rejected:SPAWN_REJECTED");
            serverPlayer.sendSystemMessage(Component.literal(
                    "§c[StoryNPCs Soulstone] The world rejected the deploy."));
            return InteractionResult.FAIL;
        }
        CreatorToolAudit.publish(mod, serverPlayer, "soulstone", "deploy", boundId, "applied");
        serverPlayer.sendSystemMessage(Component.literal(String.format(
                "§a[StoryNPCs Soulstone] Deployed '§f%s§a' at (%d, %d, %d).",
                boundId, pos.getX(), pos.getY(), pos.getZ())));
        return InteractionResult.SUCCESS;
    }
}
