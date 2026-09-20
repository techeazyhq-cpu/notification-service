package com.techeazy.notification.billing.domain;

/** Price of one message on a channel, and how many messages per month are included at no charge. */
public record ChannelRate(Money unitPrice, long freeAllowance) {

    public ChannelRate {
        if (unitPrice.isNegative()) {
            throw new InvalidBillingDataException("Unit price cannot be negative");
        }
        if (freeAllowance < 0) {
            throw new InvalidBillingDataException("Free allowance cannot be negative");
        }
    }

    public static ChannelRate free(String currency) {
        return new ChannelRate(Money.zero(currency), 0);
    }
}
