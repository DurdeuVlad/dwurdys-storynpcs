package com.storynpcs.domain.progression;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;
import java.util.UUID;

/**
 * A durable mail message delivered to a player's mailbox (issue #71 — postman/mailbox
 * role). Mail is content, not runtime session state: it survives restart and is only
 * ever read or removed by its recipient.
 */
public class MailMessage {

    @JsonProperty(required = true)
    private UUID id;

    @JsonProperty
    private String sender = "";

    @JsonProperty
    private String subject = "";

    @JsonProperty
    private String body = "";

    @JsonProperty
    private long deliveredAtEpochMillis;

    @JsonProperty
    private boolean read;

    public MailMessage() {}

    public MailMessage(UUID id, String sender, String subject, String body, long deliveredAtEpochMillis) {
        this.id = Objects.requireNonNull(id, "id");
        this.sender = sender == null ? "" : sender;
        this.subject = subject == null ? "" : subject;
        this.body = body == null ? "" : body;
        this.deliveredAtEpochMillis = deliveredAtEpochMillis;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender == null ? "" : sender; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject == null ? "" : subject; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body == null ? "" : body; }

    public long getDeliveredAtEpochMillis() { return deliveredAtEpochMillis; }
    public void setDeliveredAtEpochMillis(long deliveredAtEpochMillis) { this.deliveredAtEpochMillis = deliveredAtEpochMillis; }

    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }

    public MailMessage copy() {
        MailMessage copy = new MailMessage(id, sender, subject, body, deliveredAtEpochMillis);
        copy.read = read;
        return copy;
    }
}
