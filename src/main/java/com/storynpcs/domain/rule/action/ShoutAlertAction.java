package com.storynpcs.domain.rule.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.rule.RuleContext;

/**
 * Shouts an alert in a radius to notify nearby guards and allies of combat or trespass.
 */
public class ShoutAlertAction implements RuleAction {

    @JsonProperty
    private double radius = 16.0;

    @JsonProperty
    private String alertMessage = "Guards, to arms!";

    public ShoutAlertAction() {}

    public ShoutAlertAction(double radius, String alertMessage) {
        this.radius = radius;
        this.alertMessage = alertMessage != null ? alertMessage : "";
    }

    public double getRadius() { return radius; }
    public void setRadius(double radius) { this.radius = radius; }

    public String getAlertMessage() { return alertMessage; }
    public void setAlertMessage(String alertMessage) { this.alertMessage = alertMessage; }

    @Override
    public boolean execute(RuleContext ctx) {
        if (ctx == null) return false;
        ctx.shoutAlert(radius, alertMessage);
        return true;
    }
}
