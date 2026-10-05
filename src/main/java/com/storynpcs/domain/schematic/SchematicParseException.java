package com.storynpcs.domain.schematic;

/**
 * A schematic container that failed to parse or validate. The message always
 * carries the source name plus the concrete reason, per the issue #149
 * "malformed/oversized fails with diagnostics" contract.
 */
public final class SchematicParseException extends Exception {
    public SchematicParseException(String source, String reason) {
        super(source + ": " + reason);
    }

    public SchematicParseException(String source, String reason, Throwable cause) {
        super(source + ": " + reason, cause);
    }
}
