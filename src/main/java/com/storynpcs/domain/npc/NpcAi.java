package com.storynpcs.domain.npc;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.ai.WaypointPath;

public class NpcAi {
    public enum MovementType {
        STANDING,
        WANDERING,
        PATHING
    }

    @JsonProperty
    private MovementType movementType = MovementType.STANDING;

    @JsonProperty
    private int walkingRange = 10;

    @JsonProperty
    private boolean doorInteract = true;

    @JsonProperty
    private boolean avoidWater = false;

    @JsonProperty
    private boolean returnToStart = true;

    @JsonProperty
    private WaypointPath waypointPath = new WaypointPath();

    public NpcAi() {}

    public MovementType getMovementType() { return movementType; }
    public void setMovementType(MovementType movementType) { this.movementType = movementType; }

    public int getWalkingRange() { return walkingRange; }
    public void setWalkingRange(int walkingRange) { this.walkingRange = walkingRange; }

    public boolean isDoorInteract() { return doorInteract; }
    public void setDoorInteract(boolean doorInteract) { this.doorInteract = doorInteract; }

    public boolean isAvoidWater() { return avoidWater; }
    public void setAvoidWater(boolean avoidWater) { this.avoidWater = avoidWater; }

    public boolean isReturnToStart() { return returnToStart; }
    public void setReturnToStart(boolean returnToStart) { this.returnToStart = returnToStart; }

    public WaypointPath getWaypointPath() { return waypointPath; }
    public void setWaypointPath(WaypointPath waypointPath) {
        this.waypointPath = waypointPath != null ? waypointPath : new WaypointPath();
    }
}