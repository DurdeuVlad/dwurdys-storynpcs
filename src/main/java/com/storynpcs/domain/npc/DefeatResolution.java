package com.storynpcs.domain.npc;

/**
 * Pure defeat-mode resolution (issue #59 / P3-2): maps the authored
 * {@link NpcStats.Defeat} contract to the concrete decision the entity
 * applies when fatal damage resolves. Headless so the semantics are
 * JUnit-pinned; the entity layer only executes the returned plan.
 */
public final class DefeatResolution {

    /**
     * @param performsDeath       whether the entity resolves as a real death (removal path)
     * @param hiddenTicks         HIDE: ticks until reappearance; {@code -1} = stay hidden
     *                            (authored respawn time <= 0)
     * @param healthAfterFraction FLEE: fraction of max health restored on disengage
     * @param returnsHome         FLEE: entity navigates back to its start position
     */
    public record Decision(NpcStats.Defeat.Mode mode, boolean performsDeath,
                           int hiddenTicks, float healthAfterFraction, boolean returnsHome) {}

    public static Decision resolve(NpcStats stats) {
        NpcStats.Defeat defeat = stats != null ? stats.getDefeat() : null;
        NpcStats.Defeat.Mode mode = defeat != null ? defeat.getMode() : NpcStats.Defeat.Mode.DIE;
        return switch (mode) {
            case DIE -> new Decision(mode, true, 0, 0.0f, false);
            case HIDE -> {
                // A non-positive authored timer means "stay hidden": the
                // projection persists invisible/invulnerable until chunk
                // unload or explicit removal — matching the target's
                // respawn=NONE + hide-body semantics.
                int seconds = stats.getRespawnTimeSeconds();
                int ticks = seconds <= 0 ? -1 : seconds * 20;
                yield new Decision(mode, false, ticks, 0.0f, false);
            }
            case FLEE -> {
                // Survive at the authored flee threshold (minimum 1% so the
                // entity stays well clear of the dying path it just escaped).
                int pct = Math.max(1, defeat.getFleeHealthPercent());
                yield new Decision(mode, false, 0, pct / 100.0f, true);
            }
        };
    }
}
