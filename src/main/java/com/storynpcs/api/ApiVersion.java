package com.storynpcs.api;

/**
 * API version negotiation (P9-1). Compatibility is explicit — a client whose
 * major version differs fails clearly rather than silently misbehaving.
 */
public record ApiVersion(int major, int minor, int patch) {

    public static final ApiVersion CURRENT = new ApiVersion(1, 1, 0);

    public sealed interface Negotiation {
        record Compatible(ApiVersion server, ApiVersion client) implements Negotiation {}
        record Incompatible(String reason) implements Negotiation {}
    }

    public static Negotiation negotiate(ApiVersion client) {
        if (client == null) {
            return new Negotiation.Incompatible("client did not declare an API version");
        }
        if (client.major() != CURRENT.major()) {
            return new Negotiation.Incompatible(
                    "API major version mismatch: server " + CURRENT + ", client " + client);
        }
        if (client.minor() > CURRENT.minor()) {
            return new Negotiation.Incompatible(
                    "client requires API " + client + " but server provides " + CURRENT);
        }
        return new Negotiation.Compatible(CURRENT, client);
    }
}
