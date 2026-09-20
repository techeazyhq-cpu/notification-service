package com.techeazy.notification.billing.application.port;

import com.techeazy.notification.billing.domain.CreditHold;
import com.techeazy.notification.billing.domain.HoldScope;
import com.techeazy.notification.billing.domain.LedgerEntry;
import com.techeazy.notification.billing.domain.Money;

import java.util.List;
import java.util.UUID;

/** Credit balance, holds and the ledger of prepaid accounts. Balance operations are atomic and never go below zero. */
public interface CreditStore {

    /** A hold whose messages have all finished, with how many of them were sent. */
    record CompletedHold(CreditHold hold, long sentCount) {}

    /** Subtracts the amount only if the balance covers it, in a single atomic step. Returns whether it did. */
    boolean debitIfSufficient(UUID clientId, Money amount);

    void credit(UUID clientId, Money amount);

    Money balance(UUID clientId);

    /** Appends the entry unless the client already has one of the same type and reference. Returns whether it was added. */
    boolean appendLedger(LedgerEntry entry);

    List<LedgerEntry> ledger(UUID clientId, int limit, int offset);

    long ledgerSize(UUID clientId);

    void saveHold(CreditHold hold);

    void updateHold(CreditHold hold);

    boolean hasHeldFor(HoldScope scope, UUID referenceId);

    boolean hasOpenHolds(UUID clientId);

    /**
     * Locks and returns held holds whose messages have all reached a final status. Rows are skipped if another worker
     * has them, so several settlement workers can run at once. The lock lasts until the surrounding transaction ends.
     */
    List<CompletedHold> lockCompletedHolds(int limit);
}
