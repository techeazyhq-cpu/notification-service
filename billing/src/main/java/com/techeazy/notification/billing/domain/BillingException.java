package com.techeazy.notification.billing.domain;

/** Base type of every business-rule violation raised by the billing domain and its use cases. */
public abstract class BillingException extends RuntimeException {

    protected BillingException(String message) {
        super(message);
    }
}
