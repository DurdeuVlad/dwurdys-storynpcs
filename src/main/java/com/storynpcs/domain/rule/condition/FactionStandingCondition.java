package com.storynpcs.domain.rule.condition;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.rule.RuleContext;

/**
 * Checks faction standing criteria (e.g. requires target to be hostile).
 */
public class FactionStandingCondition implements RuleCondition {

    public enum Standing {
        HOSTILE,
        NEUTRAL,
        FRIENDLY
    }

    @JsonProperty
    private NamespacedId factionId;

    @JsonProperty
    private Standing expectedStanding = Standing.HOSTILE;

    public FactionStandingCondition() {}

    public FactionStandingCondition(NamespacedId factionId, Standing expectedStanding) {
        this.factionId = factionId;
        this.expectedStanding = expectedStanding != null ? expectedStanding : Standing.HOSTILE;
    }

    public NamespacedId getFactionId() { return factionId; }
    public void setFactionId(NamespacedId factionId) { this.factionId = factionId; }

    public Standing getExpectedStanding() { return expectedStanding; }
    public void setExpectedStanding(Standing expectedStanding) { this.expectedStanding = expectedStanding; }

    @Override
    public boolean evaluate(RuleContext ctx) {
        if (ctx == null) return false;
        // Check metadata if standing is supplied
        return ctx.getMetadata("Standing", Standing.class)
                .map(s -> s == expectedStanding)
                .orElse(true);
    }
}
