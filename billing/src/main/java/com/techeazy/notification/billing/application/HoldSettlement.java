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

package com.techeazy.notification.billing.application;

import com.techeazy.notification.billing.application.port.CreditStore;
import com.techeazy.notification.billing.application.port.CreditStore.CompletedHold;
import com.techeazy.notification.billing.application.port.Transactions;
import com.techeazy.notification.billing.domain.CreditHold;
import com.techeazy.notification.billing.domain.LedgerEntry;
import com.techeazy.notification.billing.domain.LedgerEntryType;
import com.techeazy.notification.billing.domain.Money;

import java.time.Clock;
import java.util.List;

/**
 * Closes the loop on prepaid reservations. Once every message behind a hold has finished, only the messages that were
 * actually sent are charged and the rest of the reservation goes back to the balance. Each hold settles in the same
 * transaction that returns the credit, so a crash cannot lose or duplicate money.
 */
public class HoldSettlement {

    private final CreditStore credits;
    private final Transactions transactions;
    private final Clock clock;

    public HoldSettlement(CreditStore credits, Transactions transactions, Clock clock) {
        this.credits = credits;
        this.transactions = transactions;
        this.clock = clock;
    }

    public int settleCompleted(int batchSize) {
        return transactions.inTransaction(() -> {
            List<CompletedHold> completed = credits.lockCompletedHolds(batchSize);
            completed.forEach(this::settle);
            return completed.size();
        });
    }

    private void settle(CompletedHold completed) {
        CreditHold settled = completed.hold().settle(completed.sentCount(), clock.instant());
        Money refund = settled.refund();
        if (refund.isPositive()) {
            credits.credit(settled.clientId(), refund);
            credits.appendLedger(LedgerEntry.of(settled.clientId(), LedgerEntryType.SETTLEMENT, refund,
                    settled.id().toString(), describe(settled, completed.sentCount()), clock.instant()));
        }
        credits.updateHold(settled);
    }

    private static String describe(CreditHold hold, long sent) {
        return "Returned unused reservation: " + sent + " of " + hold.messageCount() + " message(s) sent";
    }
}
