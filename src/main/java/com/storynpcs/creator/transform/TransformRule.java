package com.storynpcs.creator.transform;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

/**
 * A data-driven actor transformation (P8-5). Identity policy is explicit:
 * PRESERVE keeps the actor's logical id and progression; REPLACE retires the
 * actor and spawns a fresh one from the target template.
 */
public class TransformRule {

    public enum IdentityPolicy { PRESERVE, REPLACE }

    public enum Trigger { MANUAL, ON_DEFEAT, ON_QUEST_COMPLETE, ON_TIMER }

    @JsonProperty(required = true)
    private NamespacedId id;

    @JsonProperty(required = true)
    private NamespacedId targetTemplateId;

    @JsonProperty
    private IdentityPolicy identityPolicy = IdentityPolicy.PRESERVE;

    @JsonProperty
    private Trigger trigger = Trigger.MANUAL;

    /** Which definition facets the transform overwrites; others carry over. */
    @JsonProperty
    private java.util.Set<String> replacedFacets = new java.util.LinkedHashSet<>(
            java.util.List.of("display", "stats"));

    public TransformRule() {}

    public NamespacedId getId() { return id; }
    public void setId(NamespacedId id) { this.id = id; }

    public NamespacedId getTargetTemplateId() { return targetTemplateId; }
    public void setTargetTemplateId(NamespacedId targetTemplateId) { this.targetTemplateId = targetTemplateId; }

    public IdentityPolicy getIdentityPolicy() { return identityPolicy; }
    public void setIdentityPolicy(IdentityPolicy identityPolicy) {
        this.identityPolicy = identityPolicy == null ? IdentityPolicy.PRESERVE : identityPolicy;
    }

    public Trigger getTrigger() { return trigger; }
    public void setTrigger(Trigger trigger) { this.trigger = trigger == null ? Trigger.MANUAL : trigger; }

    public java.util.Set<String> getReplacedFacets() { return java.util.Set.copyOf(replacedFacets); }
    public void setReplacedFacets(java.util.Set<String> replacedFacets) {
        this.replacedFacets = replacedFacets == null ? new java.util.LinkedHashSet<>()
                : new java.util.LinkedHashSet<>(replacedFacets);
    }
}
