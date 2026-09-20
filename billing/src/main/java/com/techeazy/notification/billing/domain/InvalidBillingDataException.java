package com.techeazy.notification.billing.domain;

/** Input that can never be valid, whatever the current state: a negative price, a malformed month, an unknown currency. */
public class InvalidBillingDataException extends BillingException {

    public InvalidBillingDataException(String message) {
        super(message);
    }
}
