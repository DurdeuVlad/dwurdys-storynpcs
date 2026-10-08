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
    @DisplayName("refresh payloads round-trip the echoed request id (#219)")
    void refreshEchoRoundTrips() {
        UUID requestId = UUID.randomUUID();
        var panel = new ClientboundPlayerPanelPayload(
                "mail", "{}", UUID.randomUUID(), requestId);
        ByteBuf buf = Unpooled.buffer();
        ClientboundPlayerPanelPayload.STREAM_CODEC.encode(buf, panel);
        var decodedPanel = ClientboundPlayerPanelPayload.STREAM_CODEC.decode(buf);
        assertEquals(panel, decodedPanel);
        assertEquals(requestId, decodedPanel.requestId());

        var trade = new ClientboundTradeOpenPayload(
                "storynpcs:v", "Vendor", "{}", "{}", UUID.randomUUID(), requestId);
        buf = Unpooled.buffer();
        ClientboundTradeOpenPayload.STREAM_CODEC.encode(buf, trade);
        assertEquals(trade, ClientboundTradeOpenPayload.STREAM_CODEC.decode(buf));

        var bank = new ClientboundBankOpenPayload(
                "storynpcs:b", "{}", "{}", UUID.randomUUID(), requestId);
        buf = Unpooled.buffer();
        ClientboundBankOpenPayload.STREAM_CODEC.encode(buf, bank);
        assertEquals(bank, ClientboundBankOpenPayload.STREAM_CODEC.decode(buf));
    }

    @Test
    @DisplayName("unsolicited refresh payloads carry the no-request sentinel")
    void unsolicitedRefreshCarriesSentinel() {
        var payload = new ClientboundPlayerPanelPayload("mail", "{}", UUID.randomUUID());
        assertEquals(MutationProtocolCodecs.NO_REQUEST_ID, payload.requestId());
        ByteBuf buf = Unpooled.buffer();
        ClientboundPlayerPanelPayload.STREAM_CODEC.encode(buf, payload);
        assertEquals(MutationProtocolCodecs.NO_REQUEST_ID,
                ClientboundPlayerPanelPayload.STREAM_CODEC.decode(buf).requestId());
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
    @DisplayName("ServerboundPanelActionPayload round-trips hire and craft")
    void panelActionRoundTrips() {
        var hire = new ServerboundPanelActionPayload(
                UUID.randomUUID(), UUID.randomUUID(), "follower_hire",
                ServerboundPanelActionPayload.ACTION_HIRE, UUID.randomUUID().toString());
        ByteBuf buf = Unpooled.buffer();
        ServerboundPanelActionPayload.STREAM_CODEC.encode(buf, hire);
        assertEquals(hire, ServerboundPanelActionPayload.STREAM_CODEC.decode(buf));

        var craft = new ServerboundPanelActionPayload(
                UUID.randomUUID(), UUID.randomUUID(), "carpentry",
                ServerboundPanelActionPayload.ACTION_CRAFT, "storynpcs:shield");
        buf = Unpooled.buffer();
        ServerboundPanelActionPayload.STREAM_CODEC.encode(buf, craft);
        var decoded = ServerboundPanelActionPayload.STREAM_CODEC.decode(buf);
        assertEquals("storynpcs:shield", decoded.target());
    }

    @Test
    @DisplayName("custom-GUI payloads round-trip layout + session + inputs")
    void customGuiPayloadsRoundTrip() {
        var open = new ClientboundCustomGuiOpenPayload(
                "storynpcs:menu", "{\"id\":\"storynpcs:menu\"}", UUID.randomUUID());
        ByteBuf buf = Unpooled.buffer();
        ClientboundCustomGuiOpenPayload.STREAM_CODEC.encode(buf, open);
        assertEquals(open, ClientboundCustomGuiOpenPayload.STREAM_CODEC.decode(buf));

        var action = new ServerboundCustomGuiActionPayload(
                UUID.randomUUID(), UUID.randomUUID(), "storynpcs:menu",
                "0/1", "{\"0/2\":\"text\"}");
        buf = Unpooled.buffer();
        ServerboundCustomGuiActionPayload.STREAM_CODEC.encode(buf, action);
        var decoded = ServerboundCustomGuiActionPayload.STREAM_CODEC.decode(buf);
        assertEquals("0/1", decoded.elementPath());
        assertEquals("{\"0/2\":\"text\"}", decoded.inputsJson());
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
