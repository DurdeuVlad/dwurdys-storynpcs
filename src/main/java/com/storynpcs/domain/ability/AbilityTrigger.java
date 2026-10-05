package com.storynpcs.domain.ability;

/**
 * When an {@link AbilityType} fires. Mirrors the target's IAbility* trigger
 * split: ATTACK fires after the NPC lands a hit, DAMAGED fires when the NPC
 * takes a hit, UPDATE fires on the combat tick while a target is active.
 */
public enum AbilityTrigger {
    ATTACK,
    DAMAGED,
    UPDATE
}
