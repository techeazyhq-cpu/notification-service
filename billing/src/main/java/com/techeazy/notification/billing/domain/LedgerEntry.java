package com.techeazy.notification.billing.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of the append-only credit ledger. The signed sum of a client's entries always equals its credit balance,
 * and the reference makes each entry idempotent per client and type.
 */
public record LedgerEntry(UUID id, UUID clientId, LedgerEntryType type, Money amount, String reference,
                          String description, Instant createdAt) {

    public static LedgerEntry of(UUID clientId, LedgerEntryType type, Money amount, String reference,
                                 String description, Instant now) {
        return new LedgerEntry(UUID.randomUUID(), clientId, type, amount, reference, description, now);
    }
}
