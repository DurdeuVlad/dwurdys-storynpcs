package com.storynpcs.entity;

import com.storynpcs.StoryNpcs;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class StoryNpcRegistry {

    private StoryNpcRegistry() {}

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, StoryNpcs.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<StoryNpcEntity>> STORY_NPC =
            ENTITY_TYPES.register("npc", () ->
                    EntityType.Builder.of(StoryNpcEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(10)
                            .build(StoryNpcs.MOD_ID + ":npc")
            );

    public static final DeferredHolder<EntityType<?>, EntityType<NpcProjectileEntity>> NPC_PROJECTILE =
            ENTITY_TYPES.register("npc_projectile", () ->
                    EntityType.Builder.of(NpcProjectileEntity::new, MobCategory.MISC)
                            .sized(0.5F, 0.5F)
                            .clientTrackingRange(4)
                            .updateInterval(20)
                            .build(StoryNpcs.MOD_ID + ":npc_projectile")
            );

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(StoryNpcRegistry::onEntityAttributeCreation);
    }

    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(STORY_NPC.get(), StoryNpcEntity.createAttributes().build());
    }
}