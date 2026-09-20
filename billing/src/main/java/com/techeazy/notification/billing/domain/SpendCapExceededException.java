package com.techeazy.notification.billing.domain;

/** The projected month-to-date charge of a postpaid account would exceed its monthly spend cap. */
public class SpendCapExceededException extends BillingException {

    public SpendCapExceededException(Money cap, Money projected) {
        super("Monthly spend cap " + cap.display() + " would be exceeded (projected " + projected.display() + ")");
    }
}
