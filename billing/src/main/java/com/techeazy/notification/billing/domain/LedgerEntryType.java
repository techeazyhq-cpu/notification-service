package com.techeazy.notification.billing.domain;

public enum LedgerEntryType {
    /** Credit added after a payment. Positive. */
    TOP_UP,
    /** Credit reserved for accepted messages. Negative. */
    HOLD,
    /** Unused part of a hold returned after the messages finished. Positive. */
    SETTLEMENT,
    /** Manual correction by an administrator. Positive or negative. */
    ADJUSTMENT
}
