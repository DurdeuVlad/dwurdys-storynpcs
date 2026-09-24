package com.storynpcs.domain.role.follower;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages dynamic formation slot allocation for groups of followers belonging to a leader.
 */
public final class FollowerGroup {

    private final Map<UUID, List<UUID>> leaderFollowers = new ConcurrentHashMap<>();

    /**
     * Obtains the assigned formation slot index for a follower.
     * If the follower is already registered, returns its current index.
     * If newly registered, appends it to the end of the group.
     *
     * @param leaderUuid   The UUID of the leader / player
     * @param followerUuid The unique ID of the follower entity
     * @return 0-indexed slot index
     */
    public int getOrAssignSlot(UUID leaderUuid, UUID followerUuid) {
        if (leaderUuid == null || followerUuid == null) {
            return 0;
        }

        List<UUID> followers = leaderFollowers.computeIfAbsent(leaderUuid, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (followers) {
            int index = followers.indexOf(followerUuid);
            if (index >= 0) {
                return index;
            }
            followers.add(followerUuid);
            return followers.size() - 1;
        }
    }

    /**
     * Unregisters a follower from the leader's formation group.
     *
     * @param leaderUuid   The UUID of the leader
     * @param followerUuid The unique ID of the follower entity
     */
    public void unregister(UUID leaderUuid, UUID followerUuid) {
        if (leaderUuid == null || followerUuid == null) {
            return;
        }

        List<UUID> followers = leaderFollowers.get(leaderUuid);
        if (followers != null) {
            synchronized (followers) {
                followers.remove(followerUuid);
                if (followers.isEmpty()) {
                    leaderFollowers.remove(leaderUuid);
                }
            }
        }
    }

    /**
     * Returns the total number of registered followers for a leader.
     */
    public int getFollowerCount(UUID leaderUuid) {
        if (leaderUuid == null) return 0;
        List<UUID> followers = leaderFollowers.get(leaderUuid);
        if (followers == null) return 0;
        synchronized (followers) {
            return followers.size();
        }
    }

    /**
     * Clears registered followers for a specific leader (e.g. on player logout).
     */
    public void clearLeader(UUID leaderUuid) {
        if (leaderUuid != null) {
            leaderFollowers.remove(leaderUuid);
        }
    }

    /**
     * Clears all registered follower groups (used during server shutdown or test cleanup).
     */
    public void clearAll() {
        leaderFollowers.clear();
    }
}
