package com.storynpcs.creator.tools;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Legal mount/passenger relationships (P8-2): no self-mount, no cycles,
 * bounded stack depth, passengers ride at most one vehicle.
 */
public final class MountPolicy {

    public static final int MAX_STACK_DEPTH = 4;

    public enum Reject { SELF, CYCLE, STACK_TOO_DEEP, PASSENGER_ALREADY_MOUNTED }

    /**
     * Can {@code passenger} ride {@code vehicle}? {@code mounts} maps
     * passenger→vehicle for the current world state.
     */
    public static Reject check(UUID passenger, UUID vehicle, Map<UUID, UUID> mounts) {
        if (passenger == null || vehicle == null || passenger.equals(vehicle)) {
            return Reject.SELF;
        }
        if (mounts.containsKey(passenger)) {
            return Reject.PASSENGER_ALREADY_MOUNTED;
        }
        // Walk the vehicle chain — passenger must not already be an ancestor (cycle).
        Set<UUID> chain = new java.util.HashSet<>();
        UUID cursor = vehicle;
        int depth = 0;
        while (cursor != null) {
            if (cursor.equals(passenger)) {
                return Reject.CYCLE;
            }
            if (!chain.add(cursor)) {
                break; // pre-existing cycle in mounts — refuse to extend it
            }
            depth++;
            cursor = mounts.get(cursor);
        }
        if (depth >= MAX_STACK_DEPTH) {
            return Reject.STACK_TOO_DEEP;
        }
        return null;
    }
}
