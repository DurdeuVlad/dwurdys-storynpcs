package com.storynpcs.domain.role.follower;

/**
 * Relative displacement vector in a leader's local coordinate system.
 *
 * @param lateral      Lateral displacement (+ is right, - is left)
 * @param longitudinal Longitudinal displacement (+ is in front, - is behind)
 * @param vertical     Vertical height delta (+ is above, - is below)
 */
public record FormationOffset(double lateral, double longitudinal, double vertical) {

    public static FormationOffset of(double lateral, double longitudinal) {
        return new FormationOffset(lateral, longitudinal, 0.0);
    }
}
