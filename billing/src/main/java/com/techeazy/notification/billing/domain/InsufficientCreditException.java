package com.techeazy.notification.billing.domain;

/** A prepaid account cannot cover the estimated cost of a request. Nothing was accepted or charged. */
public class InsufficientCreditException extends BillingException {

    private final Money balance;
    private final Money required;

    public InsufficientCreditException(Money balance, Money required) {
        super("Insufficient credit: balance " + balance.display() + ", required " + required.display());
        this.balance = balance;
        this.required = required;
    }

    public Money balance() {
        return balance;
    }

    public Money required() {
        return required;
    }
}
