package com.techeazy.notification.billing.domain;

/** A valid request that the current state of the account or invoice does not allow, such as paying a draft invoice. */
public class InvalidBillingStateException extends BillingException {

    public InvalidBillingStateException(String message) {
        super(message);
    }
}
