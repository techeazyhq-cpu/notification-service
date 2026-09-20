package com.techeazy.notification.billing.domain;

public enum BillingMode {
    /** Usage is metered and invoiced after the month ends. */
    POSTPAID,
    /** Usage is paid from a credit balance that is reserved when a request is accepted. */
    PREPAID
}
