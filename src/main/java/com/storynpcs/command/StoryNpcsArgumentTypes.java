package com.storynpcs.command;

import com.storynpcs.StoryNpcs;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Custom brigadier argument types. Every type used in the command tree must be
 * registered here so the server can serialize the tree to clients on join —
 * unregistered types kick joining players with "Invalid player data".
 */
public final class StoryNpcsArgumentTypes {

    private StoryNpcsArgumentTypes() {}

    public static final DeferredRegister<ArgumentTypeInfo<?, ?>> ARGUMENT_TYPES =
            DeferredRegister.create(Registries.COMMAND_ARGUMENT_TYPE, StoryNpcs.MOD_ID);

    private static final SingletonArgumentInfo<StoryNpcsCommands.TargetArgument> QUEST_TARGET_INFO =
            SingletonArgumentInfo.contextFree(StoryNpcsCommands.TargetArgument::target);

    public static final DeferredHolder<ArgumentTypeInfo<?, ?>, SingletonArgumentInfo<StoryNpcsCommands.TargetArgument>> QUEST_TARGET =
            ARGUMENT_TYPES.register("quest_target", () -> QUEST_TARGET_INFO);

    public static void register(IEventBus modEventBus) {
        ARGUMENT_TYPES.register(modEventBus);
        // Registry entry alone is not enough — the serializer looks the info up
        // in ArgumentTypeInfos' static byClass map, so link the class explicitly.
        ArgumentTypeInfos.registerByClass(StoryNpcsCommands.TargetArgument.class, QUEST_TARGET_INFO);
    }
}
