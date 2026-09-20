package com.techeazy.notification.billing.domain;

import java.time.Instant;
import java.util.UUID;

/** A payment received against an invoice, recorded by an administrator. The reference makes recording idempotent. */
public record Payment(UUID id, Money amount, String method, String reference, Instant receivedAt) {

    public Payment {
        if (!amount.isPositive()) {
            throw new InvalidBillingDataException("Payment amount must be positive");
        }
        if (method == null || method.isBlank() || reference == null || reference.isBlank()) {
            throw new InvalidBillingDataException("Payment method and reference are required");
        }
    }
}
