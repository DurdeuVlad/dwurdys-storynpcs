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

    /** Resting pose rendered while idle; six target-compatible animation stances. */
    @JsonProperty
    private AnimationStance animationStance = AnimationStance.NORMAL;

    /** Whether the NPC leaps at its target to close distance. */
    @JsonProperty
    private boolean leapAtTarget = false;

    /** Whether the NPC engages members of {@link #targetFactionIds} without provocation. */
    @JsonProperty
    private boolean attackOnSight = false;

    /** Factions targeted when {@link #attackOnSight} is enabled; capped at 64 ids. */
    @JsonProperty
    private java.util.Set<com.storynpcs.domain.common.NamespacedId> targetFactionIds = new java.util.LinkedHashSet<>();

    @JsonProperty
    private TargetPriority targetPriority = TargetPriority.NEAREST;

    @JsonProperty
    private TacticalBehavior tacticalBehavior = TacticalBehavior.NONE;

    /** Radius for tactical behaviors (stalk/circle/ambush engagement distance). */
    @JsonProperty
    private int tacticalRadius = 8;

    /** Bounded scan radius for ally-defense; ally protection never scans wider. */
    @JsonProperty
    private int allyDefenseRadius = 16;

    /** Target "door bust" vocabulary: breaks closed doors instead of opening them. */
    @JsonProperty
    private boolean doorBust = false;

    /** Target "find shade" vocabulary: pathing avoids sun-exposed blocks. */
    @JsonProperty
    private boolean seekShade = false;

    /** Target "move indoors" vocabulary: seeks a roofed position when idle. */
    @JsonProperty
    private boolean shelterIndoors = false;

    /** Target "watch closest" vocabulary: tracks the nearest player while idle. */
    @JsonProperty
    private boolean watchClosest = true;

    /** Target "sprint-to-target" vocabulary: sprints while closing on a target. */
    @JsonProperty
    private boolean sprintToTarget = false;

    /** Target "panic" vocabulary: runs randomly when hurt instead of retaliating. */
    @JsonProperty
    private boolean panicOnHurt = false;

    /** Target "avoid" vocabulary: flees selector-matched entities rather than engaging. */
    @JsonProperty
    private boolean avoidTargets = false;

    /** Target "defend owner" vocabulary: retaliates against attackers of its owner. */
    @JsonProperty
    private boolean defendOwner = false;

    /**
     * Target "transform" vocabulary: on lethal defeat the projection rebinds to
     * this definition at full health instead of dying. Null disables.
     */
    @JsonProperty
    private com.storynpcs.domain.common.NamespacedId defeatTransformId = null;

    /** Six resting-pose stances matching the target animation vocabulary. */
    public enum AnimationStance {
        NORMAL,
        SITTING,
        LYING,
        SNEAKING,
        DANCING,
        AIMING
    }

    /** How the NPC chooses between simultaneous threats. */
    public enum TargetPriority {
        NEAREST,
        WEAKEST,
        STRONGEST,
        FIRST_THREAT
    }

    /** Data-driven tactical pattern; NONE keeps default melee behavior. */
    public enum TacticalBehavior {
        NONE,
        RETREAT,
        STALK,
        AMBUSH,
        CIRCLE,
        HIT_AND_RUN
    }

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
    public void setWitnessRadius(int witnessRadius) {
        this.witnessRadius = Math.max(0, Math.min(64, witnessRadius));
    }

    public boolean isDefendAllies() { return defendAllies; }
    public void setDefendAllies(boolean defendAllies) { this.defendAllies = defendAllies; }

    public int getAggroDurationTicks() { return aggroDurationTicks; }
    public void setAggroDurationTicks(int aggroDurationTicks) { this.aggroDurationTicks = Math.max(40, aggroDurationTicks); }

    public AnimationStance getAnimationStance() { return animationStance; }
    public void setAnimationStance(AnimationStance animationStance) {
        this.animationStance = animationStance != null ? animationStance : AnimationStance.NORMAL;
    }

    public boolean isLeapAtTarget() { return leapAtTarget; }
    public void setLeapAtTarget(boolean leapAtTarget) { this.leapAtTarget = leapAtTarget; }

    public boolean isAttackOnSight() { return attackOnSight; }
    public void setAttackOnSight(boolean attackOnSight) { this.attackOnSight = attackOnSight; }

    public java.util.Set<com.storynpcs.domain.common.NamespacedId> getTargetFactionIds() {
        return java.util.Collections.unmodifiableSet(targetFactionIds);
    }
    public void setTargetFactionIds(java.util.Set<com.storynpcs.domain.common.NamespacedId> ids) {
        this.targetFactionIds = new java.util.LinkedHashSet<>();
        if (ids == null) return;
        if (ids.size() > 64) {
            throw new IllegalArgumentException("targetFactionIds cannot exceed 64 entries");
        }
        for (com.storynpcs.domain.common.NamespacedId id : ids) {
            if (id == null) throw new IllegalArgumentException("targetFactionIds cannot contain null");
            this.targetFactionIds.add(id);
        }
    }

    public TargetPriority getTargetPriority() { return targetPriority; }
    public void setTargetPriority(TargetPriority targetPriority) {
        this.targetPriority = targetPriority != null ? targetPriority : TargetPriority.NEAREST;
    }

    public TacticalBehavior getTacticalBehavior() { return tacticalBehavior; }
    public void setTacticalBehavior(TacticalBehavior tacticalBehavior) {
        this.tacticalBehavior = tacticalBehavior != null ? tacticalBehavior : TacticalBehavior.NONE;
    }

    public int getTacticalRadius() { return tacticalRadius; }
    public void setTacticalRadius(int tacticalRadius) {
        this.tacticalRadius = Math.max(1, Math.min(64, tacticalRadius));
    }

    public int getAllyDefenseRadius() { return allyDefenseRadius; }
    public void setAllyDefenseRadius(int allyDefenseRadius) {
        this.allyDefenseRadius = Math.max(0, Math.min(64, allyDefenseRadius));
    }

    public boolean isDoorBust() { return doorBust; }
    public void setDoorBust(boolean doorBust) { this.doorBust = doorBust; }

    public boolean isSeekShade() { return seekShade; }
    public void setSeekShade(boolean seekShade) { this.seekShade = seekShade; }

    public boolean isShelterIndoors() { return shelterIndoors; }
    public void setShelterIndoors(boolean shelterIndoors) { this.shelterIndoors = shelterIndoors; }

    public boolean isWatchClosest() { return watchClosest; }
    public void setWatchClosest(boolean watchClosest) { this.watchClosest = watchClosest; }

    public boolean isSprintToTarget() { return sprintToTarget; }
    public void setSprintToTarget(boolean sprintToTarget) { this.sprintToTarget = sprintToTarget; }

    public boolean isPanicOnHurt() { return panicOnHurt; }
    public void setPanicOnHurt(boolean panicOnHurt) { this.panicOnHurt = panicOnHurt; }

    public boolean isAvoidTargets() { return avoidTargets; }
    public void setAvoidTargets(boolean avoidTargets) { this.avoidTargets = avoidTargets; }

    public boolean isDefendOwner() { return defendOwner; }
    public void setDefendOwner(boolean defendOwner) { this.defendOwner = defendOwner; }

    public com.storynpcs.domain.common.NamespacedId getDefeatTransformId() { return defeatTransformId; }
    public void setDefeatTransformId(com.storynpcs.domain.common.NamespacedId id) {
        this.defeatTransformId = id;
    }
}