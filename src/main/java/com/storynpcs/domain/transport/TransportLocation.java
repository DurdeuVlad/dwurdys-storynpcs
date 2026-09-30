package com.storynpcs.domain.transport;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueCondition;

/** A fast-travel destination authored inside a {@link TransportCategory}. */
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

    public TransportLocation() {}

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
}
