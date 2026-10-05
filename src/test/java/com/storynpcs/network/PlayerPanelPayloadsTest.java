package com.storynpcs.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Codec contract for the player-panel payloads (issue #150): round-trips must
 * be lossless, and the versioned/bounded framing must reject trailing bytes
 * and oversized content.
 */
class PlayerPanelPayloadsTest {

    @Test
    @DisplayName("ClientboundPlayerPanelPayload round-trips panel + view + session")
    void panelOpenRoundTrips() {
        var payload = new ClientboundPlayerPanelPayload(
                "quest_log", "{\"quests\":[]}", UUID.randomUUID());
        ByteBuf buf = Unpooled.buffer();
        ClientboundPlayerPanelPayload.STREAM_CODEC.encode(buf, payload);
        var decoded = ClientboundPlayerPanelPayload.STREAM_CODEC.decode(buf);
        assertEquals(payload, decoded);
        assertEquals(ClientboundPlayerPanelPayload.TYPE, decoded.type());
    }

    @Test
    @DisplayName("panel payload rejects unknown panel-agnostic garbage framing")
    void panelRejectsTrailingBytes() {
        ByteBuf buf = Unpooled.buffer();
        ClientboundPlayerPanelPayload.STREAM_CODEC.encode(buf,
                new ClientboundPlayerPanelPayload("mail", "{}", UUID.randomUUID()));
        buf.writeByte(0x2A);
        assertThrows(IllegalArgumentException.class,
                () -> ClientboundPlayerPanelPayload.STREAM_CODEC.decode(buf));
    }

    @Test
    @DisplayName("ServerboundMailActionPayload round-trips all actions")
    void mailActionRoundTrips() {
        var markRead = new ServerboundMailActionPayload(
                UUID.randomUUID(), UUID.randomUUID(),
                ServerboundMailActionPayload.ACTION_MARK_READ,
                UUID.randomUUID().toString(), "", "");
        ByteBuf buf = Unpooled.buffer();
        ServerboundMailActionPayload.STREAM_CODEC.encode(buf, markRead);
        assertEquals(markRead, ServerboundMailActionPayload.STREAM_CODEC.decode(buf));

        var send = new ServerboundMailActionPayload(
                UUID.randomUUID(), UUID.randomUUID(),
                ServerboundMailActionPayload.ACTION_SEND, "Vlad", "Hello", "Body text");
        buf = Unpooled.buffer();
        ServerboundMailActionPayload.STREAM_CODEC.encode(buf, send);
        var decoded = ServerboundMailActionPayload.STREAM_CODEC.decode(buf);
        assertEquals("Vlad", decoded.target());
        assertEquals("Hello", decoded.subject());
        assertEquals("Body text", decoded.body());
    }

    @Test
    @DisplayName("ServerboundTransportSelectPayload round-trips")
    void transportSelectRoundTrips() {
        var payload = new ServerboundTransportSelectPayload(
                UUID.randomUUID(), UUID.randomUUID(), "storynpcs:harbor");
        ByteBuf buf = Unpooled.buffer();
        ServerboundTransportSelectPayload.STREAM_CODEC.encode(buf, payload);
        var decoded = ServerboundTransportSelectPayload.STREAM_CODEC.decode(buf);
        assertEquals(payload, decoded);
        assertEquals("storynpcs:harbor", decoded.locationId());
    }

    @Test
    @DisplayName("mail payload bounds reject oversized content")
    void mailPayloadBoundsEnforced() {
        // MESSAGE_CODEC bound is finite — an oversized body must fail encoding,
        // not silently truncate into a state the server would half-trust.
        var payload = new ServerboundMailActionPayload(
                UUID.randomUUID(), UUID.randomUUID(),
                ServerboundMailActionPayload.ACTION_SEND, "x", "s",
                "x".repeat(MutationProtocolCodecs.MAX_MESSAGE_CHARS + 10));
        ByteBuf buf = Unpooled.buffer();
        assertThrows(Exception.class,
                () -> ServerboundMailActionPayload.STREAM_CODEC.encode(buf, payload));
    }
}
