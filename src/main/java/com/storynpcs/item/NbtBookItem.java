package com.storynpcs.item;

import com.storynpcs.StoryNpcs;
import com.storynpcs.StoryNpcsAccess;
import com.storynpcs.domain.support.NbtBookService;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * NBT Book — inspects a target entity's saved NBT (issue #148, target
 * {@code ItemNbtBook}). The viewer opens for any holder; edits are
 * server-side gated to operator level 2 AND a small allowlist of ephemeral
 * entity-state keys ({@code CustomName}, {@code NoGravity},
 * {@code Invulnerable}, {@code Silent}, {@code Glowing}) applied through
 * typed entity setters.
 *
 * <p>Deviation from the target: arbitrary NBT writes are intentionally not
 * supported — definition-backed NPC data is only mutable through canonical
 * operations (ADR-006), so everything outside the allowlist is read-only.
 */
public class NbtBookItem extends Item {

    public static final String SESSION_KIND = "nbt_book";

    public NbtBookItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("item.storynpcs.nbt_book.tooltip.1").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.translatable("item.storynpcs.nbt_book.tooltip.2").withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.FAIL;
        }
        StoryNpcs mod = StoryNpcsAccess.mod(serverPlayer);
        if (mod == null) {
            return InteractionResult.FAIL;
        }
        com.storynpcs.network.StoryNpcsNetwork.sendNbtBookOpen(serverPlayer, target);
        return InteractionResult.SUCCESS;
    }
}
