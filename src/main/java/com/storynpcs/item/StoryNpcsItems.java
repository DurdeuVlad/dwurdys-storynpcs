package com.storynpcs.item;

import com.storynpcs.StoryNpcs;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class StoryNpcsItems {

    private StoryNpcsItems() {}

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, StoryNpcs.MOD_ID);

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, StoryNpcs.MOD_ID);

    public static final DeferredHolder<Item, NpcWandItem> NPC_WAND =
            ITEMS.register("npc_wand", () -> new NpcWandItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public static final DeferredHolder<Item, NpcClonerItem> NPC_CLONER =
            ITEMS.register("npc_cloner", () -> new NpcClonerItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));

    public static final DeferredHolder<Item, NpcPathItem> NPC_PATH =
            ITEMS.register("npc_path", () -> new NpcPathItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    public static final DeferredHolder<Item, NpcMounterItem> NPC_MOUNTER =
            ITEMS.register("npc_mounter", () -> new NpcMounterItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    public static final DeferredHolder<Item, NpcDialogueWandItem> NPC_DIALOGUE_WAND =
            ITEMS.register("npc_dialogue_wand", () -> new NpcDialogueWandItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> STORYNPCS_TAB =
            CREATIVE_MODE_TABS.register("storynpcs_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.storynpcs"))
                    .icon(() -> new ItemStack(NPC_WAND.get()))
                    .displayItems((params, output) -> {
                        output.accept(NPC_WAND.get());
                        output.accept(NPC_CLONER.get());
                        output.accept(NPC_PATH.get());
                        output.accept(NPC_MOUNTER.get());
                        output.accept(NPC_DIALOGUE_WAND.get());
                    })
                    .build());

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
    }
}
