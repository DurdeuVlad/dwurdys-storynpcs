package com.storynpcs;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.neoforge.attachment.IAttachmentHolder;

/**
 * Resolution channel for the constructed {@link StoryNpcs} instance. The mod
 * publishes itself onto every loaded {@link net.minecraft.world.level.Level}
 * (server dimensions and the client world) through the {@link
 * StoryNpcsAttachments#MOD_HANDLE} data attachment at {@code LevelEvent.Load},
 * so consumers reach it through the lifecycle objects they already hold —
 * levels, entities, servers — instead of a mutable static singleton.
 *
 * <p>Resolution is null-safe: paths that can legitimately run before a level
 * exists or after teardown (early packet dispatch, class init, unit tests)
 * receive {@code null} exactly as they would have seen an uninitialized
 * bootstrap reference.
 */
public final class StoryNpcsAccess {

    private StoryNpcsAccess() {}

    /** Nullable resolution from a level/level-accessor. */
    public static StoryNpcs mod(LevelAccessor level) {
        return level instanceof IAttachmentHolder holder
                ? holder.getExistingDataOrNull(StoryNpcsAttachments.MOD_HANDLE)
                : null;
    }

    /** Nullable resolution from an entity's level. */
    public static StoryNpcs mod(Entity entity) {
        return entity == null ? null : mod(entity.level());
    }

    /** Nullable resolution from a server's overworld level. */
    public static StoryNpcs mod(MinecraftServer server) {
        var overworld = server == null ? null : server.overworld();
        return overworld == null ? null : mod(overworld);
    }

    /** Fail-fast resolution for paths that cannot function without the mod. */
    public static StoryNpcs require(LevelAccessor level) {
        StoryNpcs mod = mod(level);
        if (mod == null) {
            throw new IllegalStateException("StoryNpcs mod instance is not attached to this level");
        }
        return mod;
    }

    /** Fail-fast resolution for paths that cannot function without the mod. */
    public static StoryNpcs require(MinecraftServer server) {
        StoryNpcs mod = mod(server);
        if (mod == null) {
            throw new IllegalStateException("StoryNpcs mod instance is not attached to this server");
        }
        return mod;
    }

    /** Fail-fast resolution for paths that cannot function without the mod. */
    public static StoryNpcs require(Entity entity) {
        StoryNpcs mod = mod(entity);
        if (mod == null) {
            throw new IllegalStateException("StoryNpcs mod instance is not attached to this entity's level");
        }
        return mod;
    }
}
