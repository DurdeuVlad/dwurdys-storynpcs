package com.storynpcs.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.storynpcs.domain.ai.TacticalManeuver.Decision;
import com.storynpcs.domain.ai.TacticalManeuver.Move;
import com.storynpcs.domain.npc.NpcAi.TacticalBehavior;

/**
 * Per-tick maneuver decisions for the authored tactical behaviors — the pure
 * policy {@code NpcMeleeAttackGoal} consults before navigating/striking.
 */
class TacticalManeuverTest {

    private static final int RADIUS = 8;
    private static final double RADIUS_SQ = 64.0;
    private static final double REACH_SQ = 4.0;

    @Test
    void noneAlwaysEngages() {
        for (double health : new double[]{0.05, 0.5, 1.0}) {
            Decision d = TacticalManeuver.decide(TacticalBehavior.NONE, health,
                    RADIUS_SQ, REACH_SQ, RADIUS, false, false, false);
            assertThat(d.move()).isEqualTo(Move.ENGAGE);
            assertThat(d.attackAllowed()).isTrue();
        }
        // Null behavior degrades to the default melee path.
        assertThat(TacticalManeuver.decide(null, 1.0, 1.0, REACH_SQ, RADIUS,
                false, false, false).move()).isEqualTo(Move.ENGAGE);
    }

    @Test
    void retreatLatchesBelowTriggerAndHoldsUntilResume() {
        // Below the 0.30 trigger: latch on.
        Decision low = TacticalManeuver.decide(TacticalBehavior.RETREAT, 0.29,
                0.0, REACH_SQ, RADIUS, false, false, false);
        assertThat(low.move()).isEqualTo(Move.RETREAT);
        assertThat(low.attackAllowed()).isFalse();
        assertThat(low.retreatLatched()).isTrue();

        // Between trigger and resume: only a latched retreat persists —
        // hysteresis prevents oscillation at the boundary.
        Decision latched = TacticalManeuver.decide(TacticalBehavior.RETREAT, 0.45,
                0.0, REACH_SQ, RADIUS, true, false, false);
        assertThat(latched.move()).isEqualTo(Move.RETREAT);
        Decision unlatched = TacticalManeuver.decide(TacticalBehavior.RETREAT, 0.45,
                0.0, REACH_SQ, RADIUS, false, false, false);
        assertThat(unlatched.move()).isEqualTo(Move.ENGAGE);

        // Above the 0.50 resume threshold: the latch releases.
        Decision recovered = TacticalManeuver.decide(TacticalBehavior.RETREAT, 0.55,
                0.0, REACH_SQ, RADIUS, true, false, false);
        assertThat(recovered.move()).isEqualTo(Move.ENGAGE);
        assertThat(recovered.retreatLatched()).isFalse();
    }

    @Test
    void hitAndRunBacksOffOnlyWhileWindowOpen() {
        Decision window = TacticalManeuver.decide(TacticalBehavior.HIT_AND_RUN, 1.0,
                0.0, REACH_SQ, RADIUS, false, true, false);
        assertThat(window.move()).isEqualTo(Move.RETREAT);
        assertThat(window.attackAllowed()).isFalse();

        Decision closed = TacticalManeuver.decide(TacticalBehavior.HIT_AND_RUN, 1.0,
                0.0, REACH_SQ, RADIUS, false, false, false);
        assertThat(closed.move()).isEqualTo(Move.ENGAGE);
        assertThat(closed.attackAllowed()).isTrue();
    }

    @Test
    void stalkApproachesHoldsThenCommitsInsideReach() {
        // Far outside the radius: close on the target without committing.
        Decision far = TacticalManeuver.decide(TacticalBehavior.STALK, 1.0,
                RADIUS_SQ + 1.0, REACH_SQ, RADIUS, false, false, true);
        assertThat(far.move()).isEqualTo(Move.APPROACH_TO_RADIUS);
        assertThat(far.attackAllowed()).isFalse();

        // Inside the radius but outside reach: hold position until the window ends.
        Decision mid = TacticalManeuver.decide(TacticalBehavior.STALK, 1.0,
                REACH_SQ + 1.0, REACH_SQ, RADIUS, false, false, true);
        assertThat(mid.move()).isEqualTo(Move.HOLD);
        assertThat(mid.attackAllowed()).isFalse();

        // Within reach during the stalk window: commit to the strike.
        Decision close = TacticalManeuver.decide(TacticalBehavior.STALK, 1.0,
                REACH_SQ - 0.5, REACH_SQ, RADIUS, false, false, true);
        assertThat(close.move()).isEqualTo(Move.ENGAGE);

        // Window expired: revert to plain melee.
        assertThat(TacticalManeuver.decide(TacticalBehavior.STALK, 1.0,
                RADIUS_SQ + 1.0, REACH_SQ, RADIUS, false, false, false).move())
                .isEqualTo(Move.ENGAGE);
    }

    @Test
    void ambushNeverChasesBeyondItsRadius() {
        Decision outside = TacticalManeuver.decide(TacticalBehavior.AMBUSH, 1.0,
                RADIUS_SQ + 1.0, REACH_SQ, RADIUS, false, false, false);
        assertThat(outside.move()).isEqualTo(Move.HOLD);
        assertThat(outside.attackAllowed()).isFalse();

        Decision inside = TacticalManeuver.decide(TacticalBehavior.AMBUSH, 1.0,
                RADIUS_SQ - 1.0, REACH_SQ, RADIUS, false, false, false);
        assertThat(inside.move()).isEqualTo(Move.ENGAGE);
    }

    @Test
    void circleOrbitsInsideRadiusOutsideReach() {
        Decision orbit = TacticalManeuver.decide(TacticalBehavior.CIRCLE, 1.0,
                REACH_SQ + 4.0, REACH_SQ, RADIUS, false, false, false);
        assertThat(orbit.move()).isEqualTo(Move.ORBIT);
        assertThat(orbit.attackAllowed()).isTrue();

        // Inside reach: strike rather than orbit; outside radius: close in.
        assertThat(TacticalManeuver.decide(TacticalBehavior.CIRCLE, 1.0,
                REACH_SQ - 0.5, REACH_SQ, RADIUS, false, false, false).move())
                .isEqualTo(Move.ENGAGE);
        assertThat(TacticalManeuver.decide(TacticalBehavior.CIRCLE, 1.0,
                RADIUS_SQ + 4.0, REACH_SQ, RADIUS, false, false, false).move())
                .isEqualTo(Move.ENGAGE);
    }

    @Test
    void windowHelpersStayInsideAuthoredBounds() {
        assertThat(TacticalManeuver.hitAndRunBackoffTicks(1)).isEqualTo(10);
        assertThat(TacticalManeuver.hitAndRunBackoffTicks(64)).isEqualTo(128);
        assertThat(TacticalManeuver.stalkWindowTicks(1)).isEqualTo(60);
        assertThat(TacticalManeuver.stalkWindowTicks(64)).isEqualTo(400);
        assertThat(TacticalManeuver.orbitSwapTicks()).isEqualTo(40);
    }
}
