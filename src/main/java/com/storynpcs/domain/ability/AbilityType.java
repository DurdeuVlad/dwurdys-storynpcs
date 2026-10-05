package com.storynpcs.domain.ability;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The six target combat abilities (manifest: noppes.npcs.ability.*).
 * Each type has a fixed set of triggers it may legally use; validation
 * rejects combinations the target runtime cannot express.
 */
public enum AbilityType {
    /** On DAMAGED: incoming damage is multiplied by damageMultiplier. */
    BLOCK(EnumSet.of(AbilityTrigger.DAMAGED)),
    /** Pulls the current target (or the attacker on DAMAGED) toward the NPC. */
    PULL(EnumSet.of(AbilityTrigger.UPDATE, AbilityTrigger.DAMAGED)),
    /** Pushes the current target (or the attacker) away from the NPC. */
    PUSH(EnumSet.of(AbilityTrigger.UPDATE, AbilityTrigger.DAMAGED)),
    /** On ATTACK: launches the victim upward and adds bonus damage. */
    SMASH(EnumSet.of(AbilityTrigger.ATTACK)),
    /** Applies slowness to the current target (or the attacker). */
    SNARE(EnumSet.of(AbilityTrigger.UPDATE, AbilityTrigger.DAMAGED)),
    /** UPDATE: blink toward the target. DAMAGED: blink away from the attacker. */
    TELEPORT(EnumSet.of(AbilityTrigger.UPDATE, AbilityTrigger.DAMAGED));

    private final Set<AbilityTrigger> allowedTriggers;

    AbilityType(Set<AbilityTrigger> allowedTriggers) {
        this.allowedTriggers = Collections.unmodifiableSet(allowedTriggers);
    }

    public Set<AbilityTrigger> allowedTriggers() {
        return allowedTriggers;
    }

    public boolean allows(AbilityTrigger trigger) {
        return allowedTriggers.contains(trigger);
    }
}
