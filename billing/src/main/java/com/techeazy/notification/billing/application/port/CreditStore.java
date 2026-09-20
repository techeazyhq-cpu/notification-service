/*
 * Copyright 2026 Vasantha Kumar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Vasantha Kumar <vasantha.kumar@hotmail.com>
 */

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
