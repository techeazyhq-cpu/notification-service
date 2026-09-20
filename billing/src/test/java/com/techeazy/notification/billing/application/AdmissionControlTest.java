package com.techeazy.notification.billing.application;

import com.techeazy.notification.billing.domain.AccountStatus;
import com.techeazy.notification.billing.domain.AccountSuspendedException;
import com.techeazy.notification.billing.domain.BillingAccount;
import com.techeazy.notification.billing.domain.BillingMode;
import com.techeazy.notification.billing.domain.HoldScope;
import com.techeazy.notification.billing.domain.InsufficientCreditException;
import com.techeazy.notification.billing.domain.InvalidBillingStateException;
import com.techeazy.notification.billing.domain.LedgerEntryType;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.billing.domain.Plan;
import com.techeazy.notification.billing.domain.SpendCapExceededException;
import com.techeazy.notification.domain.Channel;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.techeazy.notification.billing.application.BillingFixture.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmissionControlTest {

    private final BillingFixture f = new BillingFixture();
    private final UUID client = UUID.randomUUID();

    private Admission sms(long messages) {
        return new Admission(client, Channel.SMS, messages, HoldScope.REQUEST, UUID.randomUUID());
    }

    private void prepaidWithBalance(String unitPrice, String balance) {
        Plan plan = f.smsPlan(unitPrice, 0);
        f.account(client, plan, BillingMode.PREPAID, null);
        f.accounts.setBalance(client, Money.of(balance, USD));
    }

    @Test
    void aClientWithoutABillingAccountIsNotBilled() {
        f.admission.admit(sms(1_000_000));

        assertThat(f.credits.holds).isEmpty();
    }

    @Test
    void aSuspendedAccountIsRefused() {
        Plan plan = f.smsPlan("0.05", 0);
        f.accounts.save(new BillingAccount(client, plan.id(), BillingMode.POSTPAID, null, Money.zero(USD), AccountStatus.SUSPENDED, null));

        assertThatThrownBy(() -> f.admission.admit(sms(1))).isInstanceOf(AccountSuspendedException.class);
    }

    @Test
    void prepaidReservesTheWorstCaseCostAndRecordsItInTheLedger() {
        prepaidWithBalance("0.05", "100");

        f.admission.admit(sms(200));

        assertThat(f.credits.balance(client)).isEqualTo(Money.of("90", USD));
        assertThat(f.credits.holds.values()).singleElement().satisfies(hold -> {
            assertThat(hold.amount()).isEqualTo(Money.of("10", USD));
            assertThat(hold.unitPrice()).isEqualTo(Money.of("0.05", USD));
        });
        assertThat(f.credits.ledger).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isEqualTo(LedgerEntryType.HOLD);
            assertThat(entry.amount()).isEqualTo(Money.of("-10", USD));
        });
    }

    @Test
    void prepaidIsRefusedWithoutChangingAnythingWhenTheBalanceIsTooLow() {
        prepaidWithBalance("0.05", "5");

        assertThatThrownBy(() -> f.admission.admit(sms(200)))
                .isInstanceOfSatisfying(InsufficientCreditException.class, e -> {
                    assertThat(e.balance()).isEqualTo(Money.of("5", USD));
                    assertThat(e.required()).isEqualTo(Money.of("10", USD));
                });
        assertThat(f.credits.balance(client)).isEqualTo(Money.of("5", USD));
        assertThat(f.credits.holds).isEmpty();
        assertThat(f.credits.ledger).isEmpty();
    }

    @Test
    void anUnpricedChannelNeedsNoReservation() {
        prepaidWithBalance("0.05", "0");

        f.admission.admit(new Admission(client, Channel.EMAIL, 500, HoldScope.REQUEST, UUID.randomUUID()));

        assertThat(f.credits.holds).isEmpty();
    }

    @Test
    void aMessageIsNotReservedTwiceWhileThePreviousChargeIsStillSettling() {
        prepaidWithBalance("1", "10");
        UUID message = UUID.randomUUID();
        Admission retry = new Admission(client, Channel.SMS, 1, HoldScope.MESSAGE, message);

        f.admission.admit(retry);

        assertThatThrownBy(() -> f.admission.admit(retry)).isInstanceOf(InvalidBillingStateException.class);
        assertThat(f.credits.balance(client)).isEqualTo(Money.of("9", USD));
    }

    @Test
    void postpaidWithoutACapIsNeverBlocked() {
        f.account(client, f.smsPlan("1", 0), BillingMode.POSTPAID, null);

        f.admission.admit(sms(5_000_000));
    }

    @Test
    void postpaidIsRefusedWhenThisRequestWouldPushTheMonthPastTheCap() {
        f.account(client, f.smsPlan("1", 0), BillingMode.POSTPAID, "100");
        f.usage.sent(client, Channel.SMS, "2026-09-03T10:00:00Z", 60);

        assertThatThrownBy(() -> f.admission.admit(sms(41)))
                .isInstanceOfSatisfying(SpendCapExceededException.class, e -> assertThat(e.getMessage()).contains("100.00 USD", "101.00 USD"));
        f.admission.admit(sms(40));
    }

    @Test
    void theCapCountsOnlyThisMonthsUsageAndRespectsTheFreeAllowance() {
        f.account(client, f.smsPlan("1", 50), BillingMode.POSTPAID, "10");
        f.usage.sent(client, Channel.SMS, "2026-08-31T23:00:00Z", 10_000);
        f.usage.sent(client, Channel.SMS, "2026-09-02T10:00:00Z", 30);

        f.admission.admit(sms(30));

        assertThatThrownBy(() -> f.admission.admit(sms(31))).isInstanceOf(SpendCapExceededException.class);
    }
}
