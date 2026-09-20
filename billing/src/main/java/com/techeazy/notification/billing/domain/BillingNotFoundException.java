package com.techeazy.notification.billing.domain;

public class BillingNotFoundException extends BillingException {

    public BillingNotFoundException(String what) {
        super(what + " not found");
    }
}
