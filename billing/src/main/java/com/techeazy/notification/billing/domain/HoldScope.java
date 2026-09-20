package com.techeazy.notification.billing.domain;

public enum HoldScope {
    /** Reserves the cost of every message of an accepted request. */
    REQUEST,
    /** Reserves the cost of re-sending one failed message. */
    MESSAGE
}
