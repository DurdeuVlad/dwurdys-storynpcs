package com.storynpcs.domain.role.follower;

/**
 * Tactical formation patterns for followers escorting a leader.
 */
public enum FormationType {
    /**
     * Single-file column behind the leader.
     */
    COLUMN,

    /**
     * V-shaped arrowhead flanking behind to the left and right.
     */
    WEDGE,

    /**
     * Line abreast / side-by-side flanking the leader left and right.
     */
    ROW,

    /**
     * Radial 360-degree perimeter ring surrounding the leader.
     */
    CIRCLE,

    /**
     * Diagonal echelon stepped back to the left-rear.
     */
    ECHELON_LEFT,

    /**
     * Diagonal echelon stepped back to the right-rear.
     */
    ECHELON_RIGHT;

    public static FormationType fromString(String name) {
        if (name == null) return WEDGE;
        try {
            return FormationType.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return WEDGE;
        }
    }
}
