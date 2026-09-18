package com.storynpcs.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NetworkPayloadsTest {

    @Test
    @DisplayName("ServerboundDialogueChoosePayload encode and decode matches")
    void testServerboundDialogueChoosePayloadCodec() {
        ServerboundDialogueChoosePayload payload = new ServerboundDialogueChoosePayload(3);
        assertEquals(3, payload.optionIndex());
        assertEquals(ServerboundDialogueChoosePayload.TYPE, payload.type());

        ByteBuf buf = Unpooled.buffer();
        ServerboundDialogueChoosePayload.STREAM_CODEC.encode(buf, payload);

        ServerboundDialogueChoosePayload decoded = ServerboundDialogueChoosePayload.STREAM_CODEC.decode(buf);
        assertEquals(3, decoded.optionIndex());
        assertEquals(payload, decoded);
    }

    @Test
    @DisplayName("ClientboundDialogueOpenPayload encode and decode matches")
    void testClientboundDialogueOpenPayloadCodec() {
        ClientboundDialogueOpenPayload payload = new ClientboundDialogueOpenPayload(
                "storynpcs:guard_talk",
                "node_1",
                "Halt! Who goes there?",
                "minecraft:entity.villager.ambient",
                List.of("I am a traveler.", "None of your business!"),
                false,
                "Guard Captain",
                List.of("Quest: Patrol Duty", "Reputation: Kingdom -10")
        );

        assertEquals("storynpcs:guard_talk", payload.dialogueId());
        assertEquals("node_1", payload.nodeId());
        assertEquals("Halt! Who goes there?", payload.text());
        assertEquals("minecraft:entity.villager.ambient", payload.sound());
        assertEquals(2, payload.options().size());
        assertFalse(payload.isTerminal());
        assertEquals("Guard Captain", payload.npcName());
        assertEquals(List.of("Quest: Patrol Duty", "Reputation: Kingdom -10"), payload.optionHints());
        assertEquals(ClientboundDialogueOpenPayload.TYPE, payload.type());

        ByteBuf buf = Unpooled.buffer();
        ClientboundDialogueOpenPayload.STREAM_CODEC.encode(buf, payload);

        ClientboundDialogueOpenPayload decoded = ClientboundDialogueOpenPayload.STREAM_CODEC.decode(buf);
        assertEquals(payload.dialogueId(), decoded.dialogueId());
        assertEquals(payload.nodeId(), decoded.nodeId());
        assertEquals(payload.text(), decoded.text());
        assertEquals(payload.sound(), decoded.sound());
        assertEquals(payload.options(), decoded.options());
        assertEquals(payload.isTerminal(), decoded.isTerminal());
        assertEquals(payload.npcName(), decoded.npcName());
        assertEquals(payload.optionHints(), decoded.optionHints());
        assertEquals(payload, decoded);
    }

    @Test
    @DisplayName("ClientboundDialogueOpenPayload sanitizes nulls to empty values")
    void testClientboundDialogueOpenPayloadNullSanitization() {
        ClientboundDialogueOpenPayload payload = new ClientboundDialogueOpenPayload(
                null, null, null, null, null, true, null, null);

        assertEquals("", payload.dialogueId());
        assertEquals("", payload.npcName());
        assertEquals(List.of(), payload.options());
        assertEquals(List.of(), payload.optionHints());
    }

    @Test
    @DisplayName("ClientboundDialogueClosePayload encode and decode matches")
    void testClientboundDialogueClosePayloadCodec() {
        ClientboundDialogueClosePayload payload = ClientboundDialogueClosePayload.INSTANCE;
        assertEquals(ClientboundDialogueClosePayload.TYPE, payload.type());

        ByteBuf buf = Unpooled.buffer();
        ClientboundDialogueClosePayload.STREAM_CODEC.encode(buf, payload);

        ClientboundDialogueClosePayload decoded = ClientboundDialogueClosePayload.STREAM_CODEC.decode(buf);
        assertSame(ClientboundDialogueClosePayload.INSTANCE, decoded);
    }

    @Test
    @DisplayName("ClientboundDialogueEditorOpenPayload carries graph JSON through codec")
    void testClientboundDialogueEditorOpenPayloadCodec() {
        ClientboundDialogueEditorOpenPayload payload = new ClientboundDialogueEditorOpenPayload(
                "storynpcs:captain_dialogue", "{\"id\":\"storynpcs:captain_dialogue\",\"nodes\":{}}");

        ByteBuf buf = Unpooled.buffer();
        ClientboundDialogueEditorOpenPayload.STREAM_CODEC.encode(buf, payload);
        ClientboundDialogueEditorOpenPayload decoded = ClientboundDialogueEditorOpenPayload.STREAM_CODEC.decode(buf);

        assertEquals(payload.dialogueId(), decoded.dialogueId());
        assertEquals(payload.graphJson(), decoded.graphJson());
        assertEquals(payload, decoded);
        assertEquals(ClientboundDialogueEditorOpenPayload.TYPE, payload.type());
    }

    @Test
    @DisplayName("ServerboundDialogueSavePayload encode and decode matches")
    void testServerboundDialogueSavePayloadCodec() {
        ServerboundDialogueSavePayload payload = new ServerboundDialogueSavePayload(
                "storynpcs:captain_dialogue", "{\"id\":\"storynpcs:captain_dialogue\"}");

        ByteBuf buf = Unpooled.buffer();
        ServerboundDialogueSavePayload.STREAM_CODEC.encode(buf, payload);
        ServerboundDialogueSavePayload decoded = ServerboundDialogueSavePayload.STREAM_CODEC.decode(buf);

        assertEquals(payload, decoded);
        assertEquals(ServerboundDialogueSavePayload.TYPE, payload.type());
    }

    @Test
    @DisplayName("ClientboundDialogueSaveResultPayload encode and decode matches")
    void testClientboundDialogueSaveResultPayloadCodec() {
        ClientboundDialogueSaveResultPayload payload =
                new ClientboundDialogueSaveResultPayload(true, "Saved.");

        ByteBuf buf = Unpooled.buffer();
        ClientboundDialogueSaveResultPayload.STREAM_CODEC.encode(buf, payload);
        ClientboundDialogueSaveResultPayload decoded = ClientboundDialogueSaveResultPayload.STREAM_CODEC.decode(buf);

        assertEquals(payload, decoded);
        assertEquals(ClientboundDialogueSaveResultPayload.TYPE, payload.type());
    }
}