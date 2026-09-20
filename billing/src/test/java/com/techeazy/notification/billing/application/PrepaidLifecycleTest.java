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

import com.techeazy.notification.billing.domain.BillingMode;
import com.techeazy.notification.billing.domain.HoldScope;
import com.techeazy.notification.billing.domain.HoldStatus;
import com.techeazy.notification.billing.domain.InsufficientCreditException;
import com.techeazy.notification.billing.domain.InvalidBillingDataException;
import com.techeazy.notification.billing.domain.InvalidBillingStateException;
import com.techeazy.notification.billing.domain.LedgerEntryType;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.billing.domain.Plan;
import com.techeazy.notification.domain.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.techeazy.notification.billing.application.BillingFixture.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrepaidLifecycleTest {

    private final BillingFixture f = new BillingFixture();
    private final UUID client = UUID.randomUUID();

    @BeforeEach
    void prepaidAccount() {
        Plan plan = f.smsPlan("0.10", 0);
        f.account(client, plan, BillingMode.PREPAID, null);
    }

    private Money usd(String amount) {
        return Money.of(amount, USD);
    }

    @Test
    void topUpAddsCreditAndIsIdempotentPerReference() {
        Money first = f.creditService.topUp(client, usd("100"), "payment-1", "Card payment");
        Money repeated = f.creditService.topUp(client, usd("100"), "payment-1", "Card payment");
        Money second = f.creditService.topUp(client, usd("25"), "payment-2", "Card payment");

        assertThat(first).isEqualTo(usd("100"));
        assertThat(repeated).isEqualTo(usd("100"));
        assertThat(second).isEqualTo(usd("125"));
        assertThat(f.credits.ledgerSize(client)).isEqualTo(2);
    }

    @Test
    void topUpRulesAreEnforced() {
        UUID postpaidClient = UUID.randomUUID();
        f.account(postpaidClient, f.smsPlan("0.1", 0), BillingMode.POSTPAID, null);

        assertThatThrownBy(() -> f.creditService.topUp(client, usd("0"), "r", "d")).isInstanceOf(InvalidBillingDataException.class);
        assertThatThrownBy(() -> f.creditService.topUp(client, Money.of("5", "EUR"), "r", "d")).isInstanceOf(InvalidBillingDataException.class);
        assertThatThrownBy(() -> f.creditService.topUp(client, usd("5"), " ", "d")).isInstanceOf(InvalidBillingDataException.class);
        assertThatThrownBy(() -> f.creditService.topUp(postpaidClient, usd("5"), "r", "d")).isInstanceOf(InvalidBillingStateException.class);
    }

    @Test
    void aNegativeAdjustmentCannotTakeMoreThanTheBalance() {
        f.creditService.topUp(client, usd("10"), "p1", "payment");

        assertThat(f.creditService.adjust(client, usd("-4"), "fix-1", "correction")).isEqualTo(usd("6"));
        assertThatThrownBy(() -> f.creditService.adjust(client, usd("-7"), "fix-2", "correction"))
                .isInstanceOf(InsufficientCreditException.class);
        assertThat(f.credits.balance(client)).isEqualTo(usd("6"));
    }

    @Test
    void settlementReturnsWhatWasNotSentAndTheLedgerAlwaysMatchesTheBalance() {
        f.creditService.topUp(client, usd("100"), "p1", "payment");
        UUID request = UUID.randomUUID();
        f.admission.admit(new Admission(client, Channel.SMS, 300, HoldScope.REQUEST, request));
        assertThat(f.credits.balance(client)).isEqualTo(usd("70"));

        f.credits.complete(request, 200);
        int settled = f.settlement.settleCompleted(50);

        assertThat(settled).isEqualTo(1);
        assertThat(f.credits.balance(client)).isEqualTo(usd("80"));
        assertThat(f.credits.holds.values()).singleElement().satisfies(hold -> {
            assertThat(hold.status()).isEqualTo(HoldStatus.SETTLED);
            assertThat(hold.settledCharge()).isEqualTo(usd("20"));
        });
        assertThat(f.credits.ledger).extracting(e -> e.type())
                .containsExactly(LedgerEntryType.TOP_UP, LedgerEntryType.HOLD, LedgerEntryType.SETTLEMENT);
        assertThat(f.credits.ledgerSum(client, USD)).isEqualTo(f.credits.balance(client));
    }

    @Test
    void holdsWhoseMessagesAreStillInFlightAreLeftAlone() {
        f.creditService.topUp(client, usd("50"), "p1", "payment");
        f.admission.admit(new Admission(client, Channel.SMS, 100, HoldScope.REQUEST, UUID.randomUUID()));

        assertThat(f.settlement.settleCompleted(50)).isZero();
        assertThat(f.credits.balance(client)).isEqualTo(usd("40"));
    }

    @Test
    void aFullySentRequestReturnsNothingAndSettlingTwiceDoesNothingMore() {
        f.creditService.topUp(client, usd("50"), "p1", "payment");
        UUID request = UUID.randomUUID();
        f.admission.admit(new Admission(client, Channel.SMS, 100, HoldScope.REQUEST, request));
        f.credits.complete(request, 100);

        assertThat(f.settlement.settleCompleted(50)).isEqualTo(1);
        assertThat(f.settlement.settleCompleted(50)).isZero();
        assertThat(f.credits.balance(client)).isEqualTo(usd("40"));
        assertThat(f.credits.ledger).extracting(e -> e.type()).doesNotContain(LedgerEntryType.SETTLEMENT);
    }

    @Test
    void aFailedRequestIsRefundedInFull() {
        f.creditService.topUp(client, usd("50"), "p1", "payment");
        UUID request = UUID.randomUUID();
        f.admission.admit(new Admission(client, Channel.SMS, 100, HoldScope.REQUEST, request));
        f.credits.complete(request, 0);

        f.settlement.settleCompleted(50);

        assertThat(f.credits.balance(client)).isEqualTo(usd("50"));
        assertThat(f.credits.ledgerSum(client, USD)).isEqualTo(usd("50"));
    }

    @Test
    void theLedgerIsPaged() {
        for (int i = 1; i <= 5; i++) {
            f.creditService.topUp(client, usd("1"), "p" + i, "payment");
        }

        LedgerPage page = f.creditService.ledger(client, 1, 2);

        assertThat(page.total()).isEqualTo(5);
        assertThat(page.items()).hasSize(2);
        assertThat(page.page()).isEqualTo(1);
    }
}
