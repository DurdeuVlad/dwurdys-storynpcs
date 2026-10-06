package com.storynpcs.item;

import com.storynpcs.StoryNpcs;
import com.storynpcs.api.event.CreatorToolAuditEvent;
import net.minecraft.server.level.ServerPlayer;

/** Shared creator-tool audit hook (P8-2) — every consequential tool op publishes. */
public final class CreatorToolAudit {

    private CreatorToolAudit() {}

    public static void publish(StoryNpcs mod, ServerPlayer player, String tool, String action,
                               String target, String outcome) {
        if (mod != null && mod.getEventPublisher() != null && player != null) {
            mod.getEventPublisher().publish(new CreatorToolAuditEvent(
                    tool, action, player.getUUID(), target, outcome));
        }
    }
}
