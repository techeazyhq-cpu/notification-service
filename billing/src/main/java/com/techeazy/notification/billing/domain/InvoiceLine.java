package com.techeazy.notification.billing.domain;

import com.techeazy.notification.domain.Channel;

/**
 * One charge on an invoice. For usage lines the quantity is the billable messages after the free allowance;
 * the channel is empty for the platform fee.
 */
public record InvoiceLine(InvoiceLineKind kind, Channel channel, String description, long quantity,
                          Money unitPrice, Money amount) {

    public InvoiceLine {
        if (quantity < 0) {
            throw new InvalidBillingDataException("Quantity cannot be negative");
        }
    }
}
