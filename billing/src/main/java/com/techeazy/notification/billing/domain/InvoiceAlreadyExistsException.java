package com.techeazy.notification.billing.domain;

/** An invoice for the same client and period already exists. Raised by the store when two generations race. */
public class InvoiceAlreadyExistsException extends BillingException {

    public InvoiceAlreadyExistsException() {
        super("An invoice for this client and period already exists");
    }
}
