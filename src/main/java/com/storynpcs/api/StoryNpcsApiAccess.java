package com.storynpcs.api;

import java.util.Optional;

import com.storynpcs.StoryNpcs;
import com.storynpcs.StoryNpcsAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;

/** Supported resolver for the read-only StoryNPCs extension API. */
public final class StoryNpcsApiAccess {

    private StoryNpcsApiAccess() {}

    public static Optional<StoryNpcsApi> api(LevelAccessor level) {
        return Optional.ofNullable(StoryNpcsAccess.mod(level)).map(StoryNpcs::getApi);
    }

    public static Optional<StoryNpcsApi> api(Entity entity) {
        return entity == null ? Optional.empty() : api(entity.level());
    }

    public static Optional<StoryNpcsApi> api(MinecraftServer server) {
        var overworld = server == null ? null : server.overworld();
        return overworld == null ? Optional.empty() : api(overworld);
    }
}
