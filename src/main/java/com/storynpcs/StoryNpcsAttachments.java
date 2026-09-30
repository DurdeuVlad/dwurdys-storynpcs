package com.storynpcs;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * NeoForge data-attachment registrations for the mod's world-scoped handles.
 * The {@link #MOD_HANDLE} attachment carries the constructed {@link StoryNpcs}
 * instance on each loaded {@link net.minecraft.world.level.Level} — installed
 * by the mod at {@link net.neoforged.neoforge.event.level.LevelEvent.Load},
 * resolved by {@link StoryNpcsAccess}. It is a lookup handle only: it is
 * transient (never serialized) and owned by the level's lifecycle, so no
 * mutable static singleton is required to reach the mod instance.
 */
public final class StoryNpcsAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, StoryNpcs.MOD_ID);

    /**
     * Lookup-only attachment holding the mod instance. The default supplier
     * throws because the handle is set explicitly at level load — callers must
     * use {@link net.neoforged.neoforge.attachment.IAttachmentHolder#getExistingDataOrNull}
     * rather than {@code getData}, which would materialize the default.
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<StoryNpcs>> MOD_HANDLE =
            ATTACHMENT_TYPES.register("mod_handle", () ->
                    AttachmentType.<StoryNpcs>builder(() -> {
                        throw new IllegalStateException(
                                "StoryNpcs mod handle is installed by the level-load lifecycle");
                    }).build());

    private StoryNpcsAttachments() {}

    public static void register(IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }
}
