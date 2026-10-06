package com.storynpcs.api.event;

import java.util.UUID;

/**
 * Published when a creator tool performs a consequential operation (P8-2):
 * path mutations, mounts, teleports, removals, and soulstone capture/deploy.
 * {@code target} is the tool's human-meaningful subject (definition id or
 * entity description); {@code outcome} names the result for audit trails.
 */
public record CreatorToolAuditEvent(
        String tool,
        String action,
        UUID playerUuid,
        String target,
        String outcome
) implements StoryNpcsEvent {}
