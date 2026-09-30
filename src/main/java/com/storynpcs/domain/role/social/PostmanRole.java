package com.storynpcs.domain.role.social;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Postman role: accepts and delivers player mail via the mailbox store. */
public class PostmanRole {

    public static final int MAX_MAILBOX_CAPACITY = 64;

    @JsonProperty
    private int mailboxCapacity = 32;

    @JsonProperty
    private double deliveryRangeBlocks = 16.0;

    /** Economy fee charged per delivered message; 0 = free. */
    @JsonProperty
    private int feePerMessage = 0;

    public PostmanRole() {}

    public int getMailboxCapacity() { return mailboxCapacity; }
    public void setMailboxCapacity(int mailboxCapacity) {
        if (mailboxCapacity < 1 || mailboxCapacity > MAX_MAILBOX_CAPACITY) {
            throw new IllegalArgumentException("mailboxCapacity must be in [1," + MAX_MAILBOX_CAPACITY + "]");
        }
        this.mailboxCapacity = mailboxCapacity;
    }

    public double getDeliveryRangeBlocks() { return deliveryRangeBlocks; }
    public void setDeliveryRangeBlocks(double deliveryRangeBlocks) {
        if (deliveryRangeBlocks <= 0 || deliveryRangeBlocks > 256) {
            throw new IllegalArgumentException("deliveryRangeBlocks must be in (0,256]");
        }
        this.deliveryRangeBlocks = deliveryRangeBlocks;
    }

    public int getFeePerMessage() { return feePerMessage; }
    public void setFeePerMessage(int feePerMessage) {
        if (feePerMessage < 0) {
            throw new IllegalArgumentException("feePerMessage must be >= 0");
        }
        this.feePerMessage = feePerMessage;
    }
}
