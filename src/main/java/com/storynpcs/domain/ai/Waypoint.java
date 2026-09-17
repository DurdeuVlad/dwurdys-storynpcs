package com.storynpcs.domain.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Waypoint(
        @JsonProperty("x") double x,
        @JsonProperty("y") double y,
        @JsonProperty("z") double z,
        @JsonProperty("waitTicks") int waitTicks
) {
    public Waypoint(double x, double y, double z) {
        this(x, y, z, 0);
    }
}