package com.storynpcs.domain.rule.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.rule.RuleContext;

import java.util.UUID;

/**
 * Sends a chat or actionbar message to the interacting actor or nearby world.
 */
public class SendMessageAction implements RuleAction {

    public enum Target {
        ACTOR,
        BROADCAST
    }

    @JsonProperty
    private String message = "";

    @JsonProperty
    private boolean actionBar = false;

    @JsonProperty
    private Target target = Target.ACTOR;

    public SendMessageAction() {}

    public SendMessageAction(String message) {
        this(message, false, Target.ACTOR);
    }

    public SendMessageAction(String message, boolean actionBar, Target target) {
        this.message = message != null ? message : "";
        this.actionBar = actionBar;
        this.target = target != null ? target : Target.ACTOR;
    }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public boolean isActionBar() { return actionBar; }
    public void setActionBar(boolean actionBar) { this.actionBar = actionBar; }

    public Target getTarget() { return target; }
    public void setTarget(Target target) { this.target = target; }

    @Override
    public boolean execute(RuleContext ctx) {
        if (ctx == null || message.isBlank()) return false;

        String formatted = message
                .replace("{npc}", ctx.getNpcName())
                .replace("{actor}", ctx.getActorUuid().map(UUID::toString).orElse("Unknown"));

        UUID targetUuid = target == Target.ACTOR ? ctx.getActorUuid().orElse(null) : null;
        ctx.sendMessage(targetUuid, formatted, actionBar);
        return true;
    }
}
