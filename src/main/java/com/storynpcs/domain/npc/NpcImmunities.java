package com.storynpcs.domain.npc;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The target's six immunity toggles (issue #59 — P3-2 stats/combat): potion,
 * fall, sunlight, fire, drowning, cobweb. Each is a plain boolean — no
 * validation needed, but declared explicitly (rather than as a bitmask or
 * generic map) so the exact six-field manifest shape is enforced by the
 * compiler, not just documentation.
 */
public class NpcImmunities {

    @JsonProperty
    private boolean potion;

    @JsonProperty
    private boolean fall;

    @JsonProperty
    private boolean sunlight;

    @JsonProperty
    private boolean fire;

    @JsonProperty
    private boolean drowning;

    @JsonProperty
    private boolean cobweb;

    public NpcImmunities() {}

    public boolean isPotion() { return potion; }
    public void setPotion(boolean potion) { this.potion = potion; }

    public boolean isFall() { return fall; }
    public void setFall(boolean fall) { this.fall = fall; }

    public boolean isSunlight() { return sunlight; }
    public void setSunlight(boolean sunlight) { this.sunlight = sunlight; }

    public boolean isFire() { return fire; }
    public void setFire(boolean fire) { this.fire = fire; }

    public boolean isDrowning() { return drowning; }
    public void setDrowning(boolean drowning) { this.drowning = drowning; }

    public boolean isCobweb() { return cobweb; }
    public void setCobweb(boolean cobweb) { this.cobweb = cobweb; }
}
