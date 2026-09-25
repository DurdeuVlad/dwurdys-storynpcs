package com.storynpcs.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Stable digest for the canonical input bound to an in-process mutation request ID. */
final class MutationPayloadFingerprint {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private MutationPayloadFingerprint() {}

    static String of(String operation, String canonicalPayload) {
        Objects.requireNonNull(operation, "operation");
        return digest(digest -> {
            update(digest, operation);
            update(digest, canonicalPayload);
        });
    }

    static String ofJson(String operation, String json) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(json, "json");
        try {
            JsonNode parsed = JSON.readTree(json);
            if (parsed == null) throw new IllegalArgumentException("JSON payload is empty");
            return digest(digest -> {
                update(digest, operation);
                updateNode(digest, parsed);
            });
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Mutation payload is not valid JSON", exception);
        }
    }

    private static String digest(java.util.function.Consumer<MessageDigest> input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            input.accept(digest);
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void updateNode(MessageDigest digest, JsonNode node) {
        if (node.isObject()) {
            digest.update((byte) 'o');
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            node.fields().forEachRemaining(fields::add);
            fields.sort(Map.Entry.comparingByKey());
            updateLength(digest, fields.size());
            for (Map.Entry<String, JsonNode> field : fields) {
                update(digest, field.getKey());
                updateNode(digest, field.getValue());
            }
        } else if (node.isArray()) {
            digest.update((byte) 'a');
            updateLength(digest, node.size());
            node.forEach(value -> updateNode(digest, value));
        } else if (node.isTextual()) {
            digest.update((byte) 's');
            update(digest, node.textValue());
        } else if (node.isNumber()) {
            digest.update((byte) 'n');
            update(digest, node.numberType().name());
            update(digest, node.asText());
        } else if (node.isBoolean()) {
            digest.update((byte) (node.booleanValue() ? 't' : 'f'));
        } else if (node.isNull()) {
            digest.update((byte) '0');
        } else {
            throw new IllegalArgumentException("Unsupported JSON payload node: " + node.getNodeType());
        }
    }

    private static void update(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        // Preserve Java UTF-16 code units; UTF-8 convenience encoders replace lone surrogates and can alias payloads.
        ByteBuffer bytes = ByteBuffer.allocate(Math.multiplyExact(value.length(), Character.BYTES));
        for (int index = 0; index < value.length(); index++) bytes.putChar(value.charAt(index));
        updateLength(digest, bytes.position());
        digest.update(bytes.array());
    }

    private static void updateLength(MessageDigest digest, int length) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(length).array());
    }
}
