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

    @JsonProperty
    private TacticalStance tacticalStance = TacticalStance.GUARD;

    @JsonProperty
    private int strikeTolerance = 1; // 1 strike tolerated before retaliating

    @JsonProperty
    private int toleranceWindowTicks = 100; // 5 seconds window for strike decay

    @JsonProperty
    private int witnessRadius = 16; // Range to detect player assaults

    @JsonProperty
    private boolean defendAllies = true;

    @JsonProperty
    private int aggroDurationTicks = 400; // 20 seconds before calm down

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

    public TacticalStance getTacticalStance() { return tacticalStance; }
    public void setTacticalStance(TacticalStance tacticalStance) {
        this.tacticalStance = tacticalStance != null ? tacticalStance : TacticalStance.GUARD;
    }

    public int getStrikeTolerance() { return strikeTolerance; }
    public void setStrikeTolerance(int strikeTolerance) { this.strikeTolerance = Math.max(0, strikeTolerance); }

    public int getToleranceWindowTicks() { return toleranceWindowTicks; }
    public void setToleranceWindowTicks(int toleranceWindowTicks) { this.toleranceWindowTicks = Math.max(20, toleranceWindowTicks); }

    public int getWitnessRadius() { return witnessRadius; }
    public void setWitnessRadius(int witnessRadius) { this.witnessRadius = Math.max(0, witnessRadius); }

    public boolean isDefendAllies() { return defendAllies; }
    public void setDefendAllies(boolean defendAllies) { this.defendAllies = defendAllies; }

    public int getAggroDurationTicks() { return aggroDurationTicks; }
    public void setAggroDurationTicks(int aggroDurationTicks) { this.aggroDurationTicks = Math.max(40, aggroDurationTicks); }
}