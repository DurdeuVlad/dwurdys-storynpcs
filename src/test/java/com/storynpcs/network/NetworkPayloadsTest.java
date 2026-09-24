package com.storynpcs.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class NetworkPayloadsTest {

    @Test
    @DisplayName("ServerboundDialogueChoosePayload encode and decode matches")
    void testServerboundDialogueChoosePayloadCodec() {
        ServerboundDialogueChoosePayload payload = new ServerboundDialogueChoosePayload(
                3, UUID.randomUUID(), UUID.randomUUID());
        assertEquals(3, payload.optionIndex());
        assertEquals(ServerboundDialogueChoosePayload.TYPE, payload.type());

        ByteBuf buf = Unpooled.buffer();
        ServerboundDialogueChoosePayload.STREAM_CODEC.encode(buf, payload);

        ServerboundDialogueChoosePayload decoded = ServerboundDialogueChoosePayload.STREAM_CODEC.decode(buf);
        assertEquals(3, decoded.optionIndex());
        assertEquals(payload, decoded);
    }

    @Test
    @DisplayName("payload codec rejects an unknown schema version")
    void rejectsUnknownPayloadVersion() {
        ByteBuf buf = Unpooled.buffer();
        net.minecraft.network.codec.ByteBufCodecs.VAR_INT.encode(buf, 99);
        assertThrows(IllegalArgumentException.class,
                () -> ServerboundDialogueChoosePayload.STREAM_CODEC.decode(buf));
    }

    @Test
    @DisplayName("payload codec rejects trailing frame bytes")
    void rejectsTrailingFrameBytes() {
        ByteBuf buf = Unpooled.buffer();
        ServerboundDialogueChoosePayload.STREAM_CODEC.encode(
                buf, new ServerboundDialogueChoosePayload(1, UUID.randomUUID(), UUID.randomUUID()));
        buf.writeByte(0x7F);
        assertThrows(IllegalArgumentException.class,
                () -> ServerboundDialogueChoosePayload.STREAM_CODEC.decode(buf));
        assertEquals(0, buf.readerIndex(), "failed decodes must rewind the frame");
    }

    @Test
    @DisplayName("payload codec rejects an oversized editor document")
    void rejectsOversizedEditorDocument() {
        String oversized = "x".repeat(MutationProtocolCodecs.MAX_EDITOR_DOCUMENT_BYTES + 1);
        ServerboundDialogueSavePayload payload = new ServerboundDialogueSavePayload(
                "storynpcs:oversized", oversized, 0L, UUID.randomUUID());
        ByteBuf buf = Unpooled.buffer();
        assertThrows(RuntimeException.class,
                () -> ServerboundDialogueSavePayload.STREAM_CODEC.encode(buf, payload));
        assertEquals(0, buf.writerIndex(), "failed encodes must not leave a partial frame");
    }

    @Test
    @DisplayName("dialogue list codec rejects more than the bounded option count")
    void rejectsOversizedDialogueOptionList() {
        List<String> options = IntStream.range(0, MutationProtocolCodecs.MAX_LIST_ITEMS + 1)
                .mapToObj(i -> "option-" + i).toList();
        ClientboundDialogueOpenPayload payload = new ClientboundDialogueOpenPayload(
                "storynpcs:test", "start", "text", "", options, false, "NPC", List.of(), UUID.randomUUID());
        ByteBuf buf = Unpooled.buffer();
        assertThrows(RuntimeException.class,
                () -> ClientboundDialogueOpenPayload.STREAM_CODEC.encode(buf, payload));
        assertEquals(0, buf.writerIndex(), "failed encodes must not leave a partial frame");
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
                "storynpcs:captain_dialogue", "{\"id\":\"storynpcs:captain_dialogue\",\"nodes\":{}}", 7L);

        ByteBuf buf = Unpooled.buffer();
        ClientboundDialogueEditorOpenPayload.STREAM_CODEC.encode(buf, payload);
        ClientboundDialogueEditorOpenPayload decoded = ClientboundDialogueEditorOpenPayload.STREAM_CODEC.decode(buf);

        assertEquals(payload.dialogueId(), decoded.dialogueId());
        assertEquals(payload.graphJson(), decoded.graphJson());
        assertEquals(7L, decoded.revision());
        assertEquals(payload, decoded);
        assertEquals(ClientboundDialogueEditorOpenPayload.TYPE, payload.type());
    }

    @Test
    @DisplayName("ServerboundDialogueSavePayload encode and decode matches")
    void testServerboundDialogueSavePayloadCodec() {
        UUID requestId = UUID.randomUUID();
        ServerboundDialogueSavePayload payload = new ServerboundDialogueSavePayload(
                "storynpcs:captain_dialogue", "{\"id\":\"storynpcs:captain_dialogue\"}", 7L, requestId);

        ByteBuf buf = Unpooled.buffer();
        ServerboundDialogueSavePayload.STREAM_CODEC.encode(buf, payload);
        ServerboundDialogueSavePayload decoded = ServerboundDialogueSavePayload.STREAM_CODEC.decode(buf);

        assertEquals(payload, decoded);
        assertEquals(7L, decoded.expectedRevision());
        assertEquals(requestId, decoded.requestId());
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

    @Test
    @DisplayName("ClientboundNpcEditorOpenPayload encode and decode matches")
    void testClientboundNpcEditorOpenPayloadCodec() {
        ClientboundNpcEditorOpenPayload payload = new ClientboundNpcEditorOpenPayload(
                "storynpcs:guard_captain", "{\"id\":\"storynpcs:guard_captain\"}", 4L);

        ByteBuf buf = Unpooled.buffer();
        ClientboundNpcEditorOpenPayload.STREAM_CODEC.encode(buf, payload);
        ClientboundNpcEditorOpenPayload decoded = ClientboundNpcEditorOpenPayload.STREAM_CODEC.decode(buf);

        assertEquals(payload.npcId(), decoded.npcId());
        assertEquals(payload.npcJson(), decoded.npcJson());
        assertEquals(4L, decoded.revision());
        assertEquals(payload, decoded);
        assertEquals(ClientboundNpcEditorOpenPayload.TYPE, payload.type());
    }

    @Test
    @DisplayName("ServerboundNpcSavePayload encode and decode matches")
    void testServerboundNpcSavePayloadCodec() {
        UUID requestId = UUID.randomUUID();
        ServerboundNpcSavePayload payload = new ServerboundNpcSavePayload(
                "storynpcs:guard_captain", "{\"id\":\"storynpcs:guard_captain\"}", 4L, requestId);

        ByteBuf buf = Unpooled.buffer();
        ServerboundNpcSavePayload.STREAM_CODEC.encode(buf, payload);
        ServerboundNpcSavePayload decoded = ServerboundNpcSavePayload.STREAM_CODEC.decode(buf);

        assertEquals(payload, decoded);
        assertEquals(4L, decoded.expectedRevision());
        assertEquals(requestId, decoded.requestId());
        assertEquals(ServerboundNpcSavePayload.TYPE, payload.type());
    }

    @Test
    @DisplayName("ClientboundNpcSaveResultPayload encode and decode matches")
    void testClientboundNpcSaveResultPayloadCodec() {
        ClientboundNpcSaveResultPayload payload =
                new ClientboundNpcSaveResultPayload(true, "NPC saved.");

        ByteBuf buf = Unpooled.buffer();
        ClientboundNpcSaveResultPayload.STREAM_CODEC.encode(buf, payload);
        ClientboundNpcSaveResultPayload decoded = ClientboundNpcSaveResultPayload.STREAM_CODEC.decode(buf);

        assertEquals(payload, decoded);
        assertEquals(ClientboundNpcSaveResultPayload.TYPE, payload.type());
    }

    @Test
    @DisplayName("Quest editor open payload carries the per-definition revision map")
    void testQuestEditorOpenPayloadRevisionMapCodec() {
        String revisions = "{\"storynpcs:bounty_goblins\":3,\"storynpcs:m3test\":7}";
        ClientboundQuestEditorOpenPayload payload = new ClientboundQuestEditorOpenPayload(
                "storynpcs:m3test", "[]", 7L, revisions);

        ByteBuf buf = Unpooled.buffer();
        ClientboundQuestEditorOpenPayload.STREAM_CODEC.encode(buf, payload);
        ClientboundQuestEditorOpenPayload decoded =
                ClientboundQuestEditorOpenPayload.STREAM_CODEC.decode(buf);

        assertEquals(payload, decoded);
        assertEquals(7L, decoded.revision());
        assertEquals(revisions, decoded.revisionsJson());
        assertEquals(ClientboundQuestEditorOpenPayload.TYPE, payload.type());
    }

    @Test
    @DisplayName("Quest editor open payload defaults revisionsJson for legacy constructors")
    void testQuestEditorOpenPayloadLegacyConstructor() {
        ClientboundQuestEditorOpenPayload payload =
                new ClientboundQuestEditorOpenPayload("storynpcs:m3test", "[]", 7L);
        assertEquals("{}", payload.revisionsJson());
        assertEquals("{}", new ClientboundQuestEditorOpenPayload("id", "[]").revisionsJson());
    }

    @Test
    @DisplayName("Faction editor open payload carries the per-definition revision map")
    void testFactionEditorOpenPayloadRevisionMapCodec() {
        String revisions = "{\"storynpcs:town_guard\":11,\"storynpcs:river_pirates\":2}";
        ClientboundFactionEditorOpenPayload payload = new ClientboundFactionEditorOpenPayload(
                "storynpcs:town_guard", "[]", 11L, revisions);

        ByteBuf buf = Unpooled.buffer();
        ClientboundFactionEditorOpenPayload.STREAM_CODEC.encode(buf, payload);
        ClientboundFactionEditorOpenPayload decoded =
                ClientboundFactionEditorOpenPayload.STREAM_CODEC.decode(buf);

        assertEquals(payload, decoded);
        assertEquals(11L, decoded.revision());
        assertEquals(revisions, decoded.revisionsJson());
        assertEquals(ClientboundFactionEditorOpenPayload.TYPE, payload.type());
    }

    @Test
    @DisplayName("Faction editor open payload defaults revisionsJson for legacy constructors")
    void testFactionEditorOpenPayloadLegacyConstructor() {
        ClientboundFactionEditorOpenPayload payload =
                new ClientboundFactionEditorOpenPayload("storynpcs:town_guard", "[]", 11L);
        assertEquals("{}", payload.revisionsJson());
        assertEquals("{}", new ClientboundFactionEditorOpenPayload("id", "[]").revisionsJson());
    }
}
