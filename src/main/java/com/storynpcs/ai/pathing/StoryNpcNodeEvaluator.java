package com.storynpcs.ai.pathing;

import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Enhanced walk node evaluator for StoryNPCs with door clearance and hazard avoidance.
 */
public class StoryNpcNodeEvaluator extends WalkNodeEvaluator {

    public StoryNpcNodeEvaluator() {
        super();
        this.setCanPassDoors(true);
        this.setCanOpenDoors(true);
    }
}
