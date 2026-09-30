package com.storynpcs.domain.quest;

/**
 * What to do when a reward cannot be delivered to the player inventory.
 * MAIL is the default and the only loss-free policy — silent dropping must
 * never be the default recovery policy.
 */
public enum RewardOverflowPolicy {
    /** Persist undeliverable rewards as quest mail — recoverable, default. */
    MAIL,
    /** Fail the delivery leg explicitly; the completion stays recoverable. */
    FAIL,
    /** Spawn the item into the world — only when the author opts in explicitly. */
    DROP_IN_WORLD
}
