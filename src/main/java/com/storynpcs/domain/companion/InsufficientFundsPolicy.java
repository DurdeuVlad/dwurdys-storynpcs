package com.storynpcs.domain.companion;

/**
 * Configured behavior when a companion's wage is owed but the owner cannot
 * pay (issue #74 acceptance criterion: "insufficient funds have configured
 * behavior"). This is vocabulary only in this slice — the actual
 * economy-deduction check and per-policy execution require the durable
 * economy store (#56) and a live player inventory/currency source, and are
 * intentionally not implemented here.
 */
public enum InsufficientFundsPolicy {
    /** The owed period rolls forward; the companion keeps working, payment is attempted again next period. */
    RETRY_NEXT_PERIOD,
    /** The companion stops performing its role (job/follow/combat) until the debt clears. */
    PAUSE_SERVICES,
    /** The companion is dismissed immediately. */
    DISMISS
}
