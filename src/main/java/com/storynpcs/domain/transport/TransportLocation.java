package com.storynpcs.domain.transport;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

import java.util.Objects;

/**
 * A creator-authored fast-travel destination (issue #72 — transport locations and
 * transporter role). This is content, not runtime state: unlock status is tracked
 * per-player in {@link com.storynpcs.domain.progression.PlayerProgression}.
 *
 * <p>This class defines and validates the destination contract only. Executing a
 * safe cross-dimension teleport (loaded-chunk check, non-obstructed landing,
 * timeout/recovery) requires a live {@code ServerLevel} and is intentionally not
 * implemented here — see the P6-3 slice PR for the explicit scope boundary.
 */
public class TransportLocation {

    @JsonProperty(required = true)
    private NamespacedId id;

    @JsonProperty
    private String displayName = "";

    /** e.g. "minecraft:overworld" — validated as a well-formed namespaced ID, not resolved to a live dimension here. */
    @JsonProperty(required = true)
    private String dimension;

    @JsonProperty
    private double x;

    @JsonProperty
    private double y;

    @JsonProperty
    private double z;

    /** 0 = free/no fee gate. */
    @JsonProperty
    private int fee;

    /** When true, a player must unlock this destination (e.g. via a quest or purchase) before it appears as selectable. */
    @JsonProperty
    private boolean requiresUnlock;

    public TransportLocation() {}

    public TransportLocation(NamespacedId id, String displayName, String dimension, double x, double y, double z) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = displayName == null ? "" : displayName;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public NamespacedId getId() { return id; }
    public void setId(NamespacedId id) { this.id = id; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName == null ? "" : displayName; }

    public String getDimension() { return dimension; }
    public void setDimension(String dimension) { this.dimension = dimension; }

    public double getX() { return x; }
    public void setX(double x) { this.x = x; }

    public double getY() { return y; }
    public void setY(double y) { this.y = y; }

    public double getZ() { return z; }
    public void setZ(double z) { this.z = z; }

    public int getFee() { return fee; }
    public void setFee(int fee) { this.fee = fee; }

    public boolean isRequiresUnlock() { return requiresUnlock; }
    public void setRequiresUnlock(boolean requiresUnlock) { this.requiresUnlock = requiresUnlock; }

    /**
     * Static, server-independent safety validation: well-formed dimension ID,
     * finite non-NaN coordinates within the world border's representable range,
     * and a non-negative fee. This is necessary but NOT sufficient for a safe
     * teleport — it cannot check loaded-chunk state or landing obstruction,
     * which require a live {@code ServerLevel} at teleport time.
     */
    public java.util.List<String> validateDestinationContract() {
        java.util.List<String> errors = new java.util.ArrayList<>();
        if (dimension == null || dimension.isBlank()) {
            errors.add("dimension is required");
        } else {
            try {
                NamespacedId.of(dimension);
            } catch (Exception e) {
                errors.add("dimension '" + dimension + "' is not a well-formed namespaced ID");
            }
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            errors.add("coordinates must be finite numbers");
        } else {
            final double MAX_COORD = 29_999_984; // Minecraft's world border hard limit
            if (Math.abs(x) > MAX_COORD || Math.abs(z) > MAX_COORD) {
                errors.add("x/z must be within the world border (+/-" + MAX_COORD + ")");
            }
            if (y < -2032 || y > 2031) {
                errors.add("y must be within the buildable height range (-2032 to 2031)");
            }
        }
        if (fee < 0) {
            errors.add("fee cannot be negative");
        }
        return errors;
    }
}
