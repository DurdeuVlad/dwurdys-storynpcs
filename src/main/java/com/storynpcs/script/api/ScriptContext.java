package com.storynpcs.script.api;

/**
 * The immutable dispatch context a hook invocation sees (P9-2). Exposed to
 * scripts as the {@code context} global alongside {@code storynpcs}. Every
 * field is a plain string read-only view — live entities, enums, and service
 * internals never cross into the sandbox (a non-API class on this record
 * would be unreachable anyway under the class shutter).
 *
 * @param hook       the hook being dispatched (js name, e.g. "interact")
 * @param npcId      the NPC the script is attached to (may be null for
 *                   unattached/global hooks such as timers)
 * @param playerUuid the player subject for player-scoped hooks (interact,
 *                   dialog, quest); null when the hook has no player
 * @param dialogueId the dialogue in play for {@code dialog} hooks
 * @param questId    the quest in play for {@code quest} hooks
 * @param timerName  the fired timer's name for {@code timer} hooks
 * @param levelKey   the dimension/level key the event fired in
 * @param args       operator-supplied argument string for {@code script trigger}
 *                   (target's space-split args, carried as one bounded string)
 */
public record ScriptContext(
        String hook,
        String npcId,
        String playerUuid,
        String dialogueId,
        String questId,
        String timerName,
        String levelKey,
        String args) {

    public String hookName() { return hook; }
}
