package com.techeazy.notification.domain;

import java.util.Set;

/** Lifecycle of a single recipient message. */
public enum MessageStatus {
    /** Persisted, not yet confirmed as published to Pulsar (outbox state). */
    PENDING,
    /** Published to Pulsar, waiting for a dispatcher. */
    QUEUED,
    /** Claimed by a dispatcher worker. */
    PROCESSING,
    /** Last attempt failed with a transient error; a retry is scheduled. */
    RETRYING,
    SENT,
    FAILED;

    public static final Set<MessageStatus> IN_FLIGHT = Set.of(PENDING, QUEUED, PROCESSING, RETRYING);
    public static final Set<MessageStatus> CLAIMABLE = Set.of(PENDING, QUEUED, RETRYING);

    public boolean isTerminal() {
        return this == SENT || this == FAILED;
    }
}
