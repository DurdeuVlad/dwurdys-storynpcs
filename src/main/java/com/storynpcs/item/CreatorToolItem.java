package com.storynpcs.item;

import net.minecraft.world.item.Item;

/**
 * Marker interface for StoryNPCs creator/authoring tools (NPC wand, dialogue
 * wand, cloner, pather, mounter, teleporter, remover, soulstone, NBT book).
 *
 * <p>A {@link com.storynpcs.entity.StoryNpcEntity} returns
 * {@link net.minecraft.world.InteractionResult#PASS} from
 * {@code mobInteract} while one of these is held in the main hand, so the
 * interaction falls through to the item's own {@code interactLivingEntity}
 * — the tools, not the entity, own the right-click flow (#148, #195, #197).
 * Without the marker the entity's dialogue/trade paths would consume the
 * interact first and no tool could ever open its editor on a StoryNPC.
 */
public interface CreatorToolItem {

    /**
     * True when {@code item} is a StoryNPCs creator tool that must bypass the
     * entity's interact handling via {@code Item#interactLivingEntity}.
     */
    static boolean isCreatorTool(Item item) {
        return item instanceof CreatorToolItem;
    }
}
