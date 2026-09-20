package com.techeazy.notification.billing.domain;

public class AccountSuspendedException extends BillingException {

    public AccountSuspendedException() {
        super("Billing account is suspended");
    }
}
