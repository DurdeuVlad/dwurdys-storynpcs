package com.storynpcs.api.event;

import java.util.UUID;

/**
 * Audit record for remote/admin operations (P9-4). Every remote command op —
 * freeze, delete, reset, teleport, player-data clear — publishes one event
 * with the acting operator, the operation name, the resolved target, and the
 * outcome so administration leaves an observable trail.
 */
public record RemoteAdminAuditEvent(
        String operation,
        UUID operatorUuid,
        String target,
        String outcome
) implements StoryNpcsEvent {}
