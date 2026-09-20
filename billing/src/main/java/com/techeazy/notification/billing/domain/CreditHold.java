package com.techeazy.notification.billing.domain;

import com.techeazy.notification.domain.Channel;

import java.time.Instant;
import java.util.UUID;

/**
 * Credit set aside for messages that were accepted but not yet sent. The worst case is reserved when the request is
 * accepted; once every message has finished, the hold is settled: only messages actually sent are charged and the
 * rest is returned. The unit price is captured at hold time so later plan edits cannot change what was agreed.
 */
public record CreditHold(UUID id, UUID clientId, HoldScope scope, UUID referenceId, Channel channel, long messageCount,
                         Money unitPrice, Money amount, HoldStatus status, Money settledCharge, Instant createdAt,
                         Instant settledAt) {

    public static CreditHold place(UUID clientId, HoldScope scope, UUID referenceId, Channel channel, long messageCount,
                                   Money unitPrice, Instant now) {
        return new CreditHold(UUID.randomUUID(), clientId, scope, referenceId, channel, messageCount, unitPrice,
                unitPrice.times(messageCount), HoldStatus.HELD, null, now, null);
    }

    public CreditHold settle(long sentCount, Instant now) {
        if (status == HoldStatus.SETTLED) {
            throw new InvalidBillingStateException("Hold is already settled");
        }
        Money charge = unitPrice.times(sentCount).min(amount);
        return new CreditHold(id, clientId, scope, referenceId, channel, messageCount, unitPrice, amount,
                HoldStatus.SETTLED, charge, createdAt, now);
    }

    public Money refund() {
        if (status != HoldStatus.SETTLED) {
            throw new InvalidBillingStateException("Only a settled hold has a refund");
        }
        return amount.minus(settledCharge);
    }
}
