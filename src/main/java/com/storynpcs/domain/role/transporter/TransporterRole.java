package com.storynpcs.domain.role.transporter;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Authored, static transporter-role configuration (issue #72 — transport
 * locations and transporter role). Mirrors {@code TraderRole}/{@code BankerRole}/
 * {@code HealerRole}/{@code BardRole}: content, not mutable runtime state. This
 * role only declares which {@link com.storynpcs.domain.transport.TransportLocation}
 * destinations this specific NPC offers, and an optional flat fee override applied
 * uniformly across all of them; it does not track any per-player unlock or trip
 * history — that already lives on {@code PlayerProgression} (issue #72's original
 * PR), keyed by destination, not by transporter NPC.
 */
public class TransporterRole {

    @JsonProperty
    private Set<NamespacedId> offeredDestinationIds = new LinkedHashSet<>();

    /** -1 = no override; use each destination's own base fee. */
    @JsonProperty
    private int feeOverride = -1;

    public TransporterRole() {}

    public TransporterRole(Set<NamespacedId> offeredDestinationIds, int feeOverride) {
        setOfferedDestinationIds(offeredDestinationIds);
        this.feeOverride = feeOverride;
    }

    public Set<NamespacedId> getOfferedDestinationIds() {
        return offeredDestinationIds;
    }

    public void setOfferedDestinationIds(Set<NamespacedId> offeredDestinationIds) {
        this.offeredDestinationIds = new LinkedHashSet<>();
        if (offeredDestinationIds == null) return;
        for (NamespacedId id : offeredDestinationIds) {
            if (id != null) {
                this.offeredDestinationIds.add(id);
            }
        }
    }

    public void addOfferedDestination(NamespacedId destinationId) {
        Objects.requireNonNull(destinationId, "destinationId");
        offeredDestinationIds.add(destinationId);
    }

    public void removeOfferedDestination(NamespacedId destinationId) {
        offeredDestinationIds.remove(destinationId);
    }

    public boolean offers(NamespacedId destinationId) {
        return destinationId != null && offeredDestinationIds.contains(destinationId);
    }

    public int getFeeOverride() { return feeOverride; }
    public void setFeeOverride(int feeOverride) { this.feeOverride = feeOverride; }

    public boolean hasFeeOverride() { return feeOverride >= 0; }

    /** The fee a trip through this transporter to {@code destinationBaseFee} actually costs. */
    public int effectiveFee(int destinationBaseFee) {
        return hasFeeOverride() ? feeOverride : Math.max(0, destinationBaseFee);
    }
}
