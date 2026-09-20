package com.techeazy.notification.billing.application;

import com.techeazy.notification.billing.application.port.AccountRepository;
import com.techeazy.notification.billing.application.port.CreditStore;
import com.techeazy.notification.billing.application.port.PlanRepository;
import com.techeazy.notification.billing.application.port.UsageReader;
import com.techeazy.notification.billing.domain.AccountSuspendedException;
import com.techeazy.notification.billing.domain.BillingAccount;
import com.techeazy.notification.billing.domain.BillingNotFoundException;
import com.techeazy.notification.billing.domain.BillingPeriod;
import com.techeazy.notification.billing.domain.CreditHold;
import com.techeazy.notification.billing.domain.HoldScope;
import com.techeazy.notification.billing.domain.InsufficientCreditException;
import com.techeazy.notification.billing.domain.InvalidBillingStateException;
import com.techeazy.notification.billing.domain.InvoiceCalculator;
import com.techeazy.notification.billing.domain.InvoiceTotals;
import com.techeazy.notification.billing.domain.LedgerEntry;
import com.techeazy.notification.billing.domain.LedgerEntryType;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.billing.domain.Plan;
import com.techeazy.notification.billing.domain.SpendCapExceededException;
import com.techeazy.notification.domain.Channel;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;

/**
 * Decides whether a client may send, at the moment a request is accepted. A client without a billing account is not
 * billed and always passes.
 *
 * <ul>
 *   <li>Suspended accounts are refused.</li>
 *   <li>Prepaid accounts reserve the worst-case cost with one atomic conditional debit and are refused when the
 *       balance does not cover it. The reservation is settled later by {@link HoldSettlement}.</li>
 *   <li>Postpaid accounts with a monthly spend cap are refused when the projected month-to-date charge, including
 *       this request, would exceed it. The cap is soft: it counts messages already sent, not those still in flight.</li>
 * </ul>
 *
 * <p>Call this inside the transaction that stores the request, so a refusal or a later failure leaves nothing behind.
 */
public class AdmissionControl {

    private final AccountRepository accounts;
    private final PlanRepository plans;
    private final CreditStore credits;
    private final UsageReader usage;
    private final InvoiceCalculator calculator;
    private final Clock clock;

    public AdmissionControl(AccountRepository accounts, PlanRepository plans, CreditStore credits, UsageReader usage,
                            InvoiceCalculator calculator, Clock clock) {
        this.accounts = accounts;
        this.plans = plans;
        this.credits = credits;
        this.usage = usage;
        this.calculator = calculator;
        this.clock = clock;
    }

    public void admit(Admission admission) {
        accounts.findByClientId(admission.clientId()).ifPresent(account -> admit(account, admission));
    }

    private void admit(BillingAccount account, Admission admission) {
        if (account.isSuspended()) {
            throw new AccountSuspendedException();
        }
        Plan plan = plans.findById(account.planId()).orElseThrow(() -> new BillingNotFoundException("Plan"));
        if (account.isPrepaid()) {
            reserve(plan, admission);
        } else {
            enforceSpendCap(account, plan, admission);
        }
    }

    private void reserve(Plan plan, Admission admission) {
        Money unitPrice = plan.rateFor(admission.channel()).unitPrice();
        if (unitPrice.isZero()) {
            return;
        }
        if (admission.scope() == HoldScope.MESSAGE && credits.hasHeldFor(HoldScope.MESSAGE, admission.referenceId())) {
            throw new InvalidBillingStateException("A previous charge for this message is still settling; try again shortly");
        }
        CreditHold hold = CreditHold.place(admission.clientId(), admission.scope(), admission.referenceId(),
                admission.channel(), admission.messages(), unitPrice, clock.instant());
        if (!credits.debitIfSufficient(admission.clientId(), hold.amount())) {
            throw new InsufficientCreditException(credits.balance(admission.clientId()), hold.amount());
        }
        credits.saveHold(hold);
        credits.appendLedger(LedgerEntry.of(admission.clientId(), LedgerEntryType.HOLD, hold.amount().negate(),
                hold.id().toString(), describe(admission), clock.instant()));
    }

    private void enforceSpendCap(BillingAccount account, Plan plan, Admission admission) {
        account.spendCap().ifPresent(cap -> {
            BillingPeriod period = BillingPeriod.current(clock);
            Map<Channel, Long> projected = new EnumMap<>(Channel.class);
            projected.putAll(usage.sentByChannel(account.clientId(), period.start(), clock.instant()));
            projected.merge(admission.channel(), admission.messages(), Long::sum);
            Money subtotal = InvoiceTotals.of(plan.currency(), calculator.linesFor(plan, projected), plan.taxRate()).subtotal();
            if (subtotal.compareTo(cap) > 0) {
                throw new SpendCapExceededException(cap, subtotal);
            }
        });
    }

    private static String describe(Admission admission) {
        return "Reserved for " + admission.messages() + " " + admission.channel() + " message(s)";
    }
}
