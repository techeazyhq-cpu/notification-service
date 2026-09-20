package com.techeazy.notification.billing.application;

import com.techeazy.notification.billing.domain.AccountStatus;
import com.techeazy.notification.billing.domain.BillingAccount;
import com.techeazy.notification.billing.domain.BillingMode;
import com.techeazy.notification.billing.domain.BillingNotFoundException;
import com.techeazy.notification.billing.domain.ChannelRate;
import com.techeazy.notification.billing.domain.HoldScope;
import com.techeazy.notification.billing.domain.InvalidBillingDataException;
import com.techeazy.notification.billing.domain.InvalidBillingStateException;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.billing.domain.Plan;
import com.techeazy.notification.domain.Channel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static com.techeazy.notification.billing.application.BillingFixture.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountAndPlanManagementTest {

    private final BillingFixture f = new BillingFixture();
    private final UUID client = UUID.randomUUID();

    private static PlanDraft draft(String name, String currency, boolean active) {
        return new PlanDraft(name, currency, Money.zero(currency), new BigDecimal("0.05"),
                Map.of(Channel.SMS, new ChannelRate(Money.of("0.05", currency), 10)), active);
    }

    private AccountAssignment assignment(Plan plan, BillingMode mode, String cap) {
        return new AccountAssignment(client, plan.id(), mode, cap == null ? null : Money.of(cap, USD), AccountStatus.ACTIVE, "billing@acme.test");
    }

    @Test
    void plansAreCreatedListedAndFoundById() {
        Plan created = f.planCatalog.create(draft("standard", USD, true));

        assertThat(f.planCatalog.get(created.id()).name()).isEqualTo("standard");
        assertThat(f.planCatalog.list()).hasSize(1);
        assertThatThrownBy(() -> f.planCatalog.get(UUID.randomUUID())).isInstanceOf(BillingNotFoundException.class);
    }

    @Test
    void planNamesAreUnique() {
        Plan first = f.planCatalog.create(draft("standard", USD, true));
        Plan second = f.planCatalog.create(draft("premium", USD, true));

        assertThatThrownBy(() -> f.planCatalog.create(draft("standard", USD, true))).isInstanceOf(InvalidBillingStateException.class);
        assertThatThrownBy(() -> f.planCatalog.update(second.id(), draft("standard", USD, true))).isInstanceOf(InvalidBillingStateException.class);
        assertThat(f.planCatalog.update(first.id(), draft("standard", USD, false)).active()).isFalse();
    }

    @Test
    void theCurrencyOfAPlanInUseCannotChange() {
        Plan plan = f.planCatalog.create(draft("standard", USD, true));
        f.accountManagement.assign(assignment(plan, BillingMode.POSTPAID, null));

        assertThatThrownBy(() -> f.planCatalog.update(plan.id(), draft("standard", "EUR", true))).isInstanceOf(InvalidBillingStateException.class);
    }

    @Test
    void aNewAccountStartsWithZeroCreditAndCanBeReadBack() {
        Plan plan = f.planCatalog.create(draft("standard", USD, true));

        BillingAccount account = f.accountManagement.assign(assignment(plan, BillingMode.POSTPAID, "500"));

        assertThat(account.creditBalance().isZero()).isTrue();
        assertThat(account.spendCap()).contains(Money.of("500", USD));
        assertThat(f.accountManagement.get(client).billingEmail()).isEqualTo("billing@acme.test");
        assertThat(f.accountManagement.list()).hasSize(1);
        assertThatThrownBy(() -> f.accountManagement.get(UUID.randomUUID())).isInstanceOf(BillingNotFoundException.class);
    }

    @Test
    void anInactiveOrUnknownPlanCannotBeAssigned() {
        Plan inactive = f.planCatalog.create(draft("old", USD, false));

        assertThatThrownBy(() -> f.accountManagement.assign(assignment(inactive, BillingMode.POSTPAID, null))).isInstanceOf(InvalidBillingStateException.class);
        Plan ghost = new Plan(UUID.randomUUID(), "ghost", USD, Money.zero(USD), BigDecimal.ZERO, Map.of(), true);
        assertThatThrownBy(() -> f.accountManagement.assign(assignment(ghost, BillingMode.POSTPAID, null))).isInstanceOf(BillingNotFoundException.class);
    }

    @Test
    void theSpendCapMustBeInThePlanCurrencyAndOnlyForPostpaid() {
        Plan plan = f.planCatalog.create(draft("standard", USD, true));
        AccountAssignment euroCap = new AccountAssignment(client, plan.id(), BillingMode.POSTPAID, Money.of("5", "EUR"), AccountStatus.ACTIVE, null);

        assertThatThrownBy(() -> f.accountManagement.assign(euroCap)).isInstanceOf(InvalidBillingDataException.class);
        assertThatThrownBy(() -> f.accountManagement.assign(assignment(plan, BillingMode.PREPAID, "10"))).isInstanceOf(InvalidBillingDataException.class);
    }

    @Test
    void reassigningKeepsTheCreditBalanceUntouched() {
        Plan standard = f.planCatalog.create(draft("standard", USD, true));
        Plan premium = f.planCatalog.create(draft("premium", USD, true));
        f.accountManagement.assign(assignment(standard, BillingMode.PREPAID, null));
        f.creditService.topUp(client, Money.of("40", USD), "p1", "payment");

        BillingAccount moved = f.accountManagement.assign(assignment(premium, BillingMode.PREPAID, null));

        assertThat(moved.planId()).isEqualTo(premium.id());
        assertThat(moved.creditBalance()).isEqualTo(Money.of("40", USD));
    }

    @Test
    void modeCannotChangeWhileThereIsCreditOrAnOpenHold() {
        Plan plan = f.planCatalog.create(draft("standard", USD, true));
        f.accountManagement.assign(assignment(plan, BillingMode.PREPAID, null));
        f.creditService.topUp(client, Money.of("40", USD), "p1", "payment");

        assertThatThrownBy(() -> f.accountManagement.assign(assignment(plan, BillingMode.POSTPAID, null))).isInstanceOf(InvalidBillingStateException.class);

        f.accounts.setBalance(client, Money.zero(USD));
        f.admission.admit(new Admission(client, Channel.SMS, 0, HoldScope.REQUEST, UUID.randomUUID()));
        f.credits.saveHold(com.techeazy.notification.billing.domain.CreditHold.place(client, HoldScope.REQUEST, UUID.randomUUID(),
                Channel.SMS, 1, Money.of("0.05", USD), f.clock.instant()));
        assertThatThrownBy(() -> f.accountManagement.assign(assignment(plan, BillingMode.POSTPAID, null))).isInstanceOf(InvalidBillingStateException.class);
    }

    @Test
    void anAccountCanBeSuspendedAndReactivated() {
        Plan plan = f.planCatalog.create(draft("standard", USD, true));
        f.accountManagement.assign(assignment(plan, BillingMode.POSTPAID, null));

        BillingAccount suspended = f.accountManagement.assign(new AccountAssignment(client, plan.id(), BillingMode.POSTPAID, null, AccountStatus.SUSPENDED, null));

        assertThat(suspended.isSuspended()).isTrue();
        assertThat(f.accountManagement.assign(assignment(plan, BillingMode.POSTPAID, null)).isSuspended()).isFalse();
    }
}
