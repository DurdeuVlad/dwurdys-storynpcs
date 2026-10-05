package com.storynpcs.domain.transport;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueCondition;

/**
 * A fast-travel destination authored inside a {@link TransportCategory}.
 * This is content, not runtime state: unlock status is tracked per-player in
 * {@link com.storynpcs.domain.progression.PlayerProgression}. Executing a safe
 * cross-dimension teleport (loaded-chunk check, non-obstructed landing,
 * timeout/recovery) requires a live {@code ServerLevel} and is intentionally
 * not implemented here.
 */
public class TransportLocation {

    @JsonProperty(required = true)
    private NamespacedId id;

    @JsonProperty(required = true)
    private String name;

    @JsonProperty(required = true)
    private NamespacedId dimensionId;

    @JsonProperty
    private double x;

    @JsonProperty
    private double y;

    @JsonProperty
    private double z;

    @JsonProperty
    private float yaw;

    /** Unlock conditions evaluated server-side; empty = always unlocked when visible. */
    @JsonProperty
    private List<DialogueCondition> unlockConditions = new ArrayList<>();

    /** Economy fee per use; 0 = free. */
    @JsonProperty
    private int fee = 0;

    /** Whether the destination appears in player UIs before unlock conditions are met. */
    @JsonProperty
    private boolean visibleWhenLocked = false;

    /**
     * Cross-dimension transfer policy (P6-3): after a successful transfer the
     * arrival is deadline-verified within {@code transferTimeoutTicks}; a
     * player who never arrived in the target dimension is recovered per
     * {@code recovery}. {@code null} resolves to
     * {@link TransportEvaluator.DimensionPolicy#defaults()}.
     */
    @JsonProperty
    private TransportEvaluator.DimensionPolicy dimensionPolicy;

    public TransportLocation() {}

    public TransportLocation(NamespacedId id, String name, String dimension, double x, double y, double z) {
        this.id = java.util.Objects.requireNonNull(id, "id");
        this.name = name == null ? "" : name;
        this.dimensionId = (dimension == null || dimension.isBlank()) ? null : NamespacedId.of(dimension);
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public NamespacedId getId() { return id; }
    public void setId(NamespacedId id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public NamespacedId getDimensionId() { return dimensionId; }
    public void setDimensionId(NamespacedId dimensionId) { this.dimensionId = dimensionId; }

    public double getX() { return x; }
    public void setX(double x) { this.x = x; }
    public double getY() { return y; }
    public void setY(double y) { this.y = y; }
    public double getZ() { return z; }
    public void setZ(double z) { this.z = z; }
    public float getYaw() { return yaw; }
    public void setYaw(float yaw) { this.yaw = yaw; }

    public List<DialogueCondition> getUnlockConditions() { return List.copyOf(unlockConditions); }
    public void setUnlockConditions(List<DialogueCondition> unlockConditions) {
        this.unlockConditions = unlockConditions == null ? new ArrayList<>() : new ArrayList<>(unlockConditions);
    }

    public int getFee() { return fee; }
    public void setFee(int fee) {
        if (fee < 0) {
            throw new IllegalArgumentException("fee must be >= 0");
        }
        this.fee = fee;
    }

    public boolean isVisibleWhenLocked() { return visibleWhenLocked; }
    public void setVisibleWhenLocked(boolean visibleWhenLocked) { this.visibleWhenLocked = visibleWhenLocked; }

    /** Authored cross-dimension policy; absent means the bounded default applies. */
    public TransportEvaluator.DimensionPolicy getDimensionPolicy() {
        return dimensionPolicy != null ? dimensionPolicy : TransportEvaluator.DimensionPolicy.defaults();
    }
    public void setDimensionPolicy(TransportEvaluator.DimensionPolicy dimensionPolicy) {
        this.dimensionPolicy = dimensionPolicy;
    }

    /**
     * Static, server-independent safety validation: required id/name/dimension,
     * finite coordinates within the world border's representable range, and a
     * non-negative fee. This is necessary but NOT sufficient for a safe teleport
     * — it cannot check loaded-chunk state or landing obstruction, which require
     * a live {@code ServerLevel} at teleport time.
     */
    public List<String> validateDestinationContract() {
        List<String> errors = new ArrayList<>();
        if (id == null) {
            errors.add("id is required");
        }
        if (name == null || name.isBlank()) {
            errors.add("name is required");
        }
        if (dimensionId == null) {
            errors.add("dimension is required");
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
