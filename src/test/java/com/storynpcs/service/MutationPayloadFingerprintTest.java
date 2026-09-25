package com.storynpcs.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MutationPayloadFingerprintTest {
    @Test
    void canonicalJsonIgnoresObjectKeyOrderButPreservesArrayOrderAndOperation() {
        String firstOrder = "{\"z\":1,\"a\":{\"y\":2,\"x\":3},\"nodes\":[\"start\",\"end\"]}";
        String secondOrder = "{\"nodes\":[\"start\",\"end\"],\"a\":{\"x\":3,\"y\":2},\"z\":1}";
        String reorderedArray = "{\"a\":{\"y\":2,\"x\":3},\"z\":1,\"nodes\":[\"end\",\"start\"]}";

        assertThat(MutationPayloadFingerprint.ofJson("npc.replace", firstOrder))
                .isEqualTo(MutationPayloadFingerprint.ofJson("npc.replace", secondOrder));
        assertThat(MutationPayloadFingerprint.ofJson("npc.replace", firstOrder))
                .isNotEqualTo(MutationPayloadFingerprint.ofJson("npc.replace", reorderedArray));
        assertThat(MutationPayloadFingerprint.ofJson("dialogue.replace", firstOrder))
                .isNotEqualTo(MutationPayloadFingerprint.ofJson("npc.replace", firstOrder));
    }

    @Test
    void malformedUtf16DoesNotCollideWithTheReplacementCharacter() {
        String loneHighSurrogate = new String(new char[]{(char) 0xD800});

        assertThat(MutationPayloadFingerprint.of("npc.replace", loneHighSurrogate))
                .isNotEqualTo(MutationPayloadFingerprint.of("npc.replace", "?"));
        assertThat(MutationPayloadFingerprint.ofJson("npc.replace", "{\"name\":\"\\uD800\"}"))
                .isNotEqualTo(MutationPayloadFingerprint.ofJson("npc.replace", "{\"name\":\"?\"}"));
    }
}
