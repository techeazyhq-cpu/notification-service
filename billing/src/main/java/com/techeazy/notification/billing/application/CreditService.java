package com.techeazy.notification.billing.application;

import com.techeazy.notification.billing.application.port.AccountRepository;
import com.techeazy.notification.billing.application.port.CreditStore;
import com.techeazy.notification.billing.application.port.PlanRepository;
import com.techeazy.notification.billing.application.port.Transactions;
import com.techeazy.notification.billing.domain.BillingAccount;
import com.techeazy.notification.billing.domain.BillingNotFoundException;
import com.techeazy.notification.billing.domain.InsufficientCreditException;
import com.techeazy.notification.billing.domain.InvalidBillingDataException;
import com.techeazy.notification.billing.domain.InvalidBillingStateException;
import com.techeazy.notification.billing.domain.LedgerEntry;
import com.techeazy.notification.billing.domain.LedgerEntryType;
import com.techeazy.notification.billing.domain.Money;

import java.time.Clock;
import java.util.UUID;

/**
 * Adds and corrects credit on prepaid accounts. Every change writes a ledger entry in the same transaction, and the
 * reference makes a repeated request (a payment notification delivered twice) a no-op.
 */
public class CreditService {

    private static final int MAX_PAGE_SIZE = 200;

    private final AccountRepository accounts;
    private final PlanRepository plans;
    private final CreditStore credits;
    private final Transactions transactions;
    private final Clock clock;

    public CreditService(AccountRepository accounts, PlanRepository plans, CreditStore credits, Transactions transactions,
                         Clock clock) {
        this.accounts = accounts;
        this.plans = plans;
        this.credits = credits;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Money topUp(UUID clientId, Money amount, String reference, String description) {
        if (!amount.isPositive()) {
            throw new InvalidBillingDataException("A top-up must be positive");
        }
        return apply(clientId, amount, LedgerEntryType.TOP_UP, reference, description);
    }

    public Money adjust(UUID clientId, Money signedAmount, String reference, String description) {
        if (signedAmount.isZero()) {
            throw new InvalidBillingDataException("An adjustment cannot be zero");
        }
        return apply(clientId, signedAmount, LedgerEntryType.ADJUSTMENT, reference, description);
    }

    public LedgerPage ledger(UUID clientId, int page, int size) {
        int pageSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int pageNumber = Math.max(page, 0);
        return new LedgerPage(credits.ledger(clientId, pageSize, pageNumber * pageSize), credits.ledgerSize(clientId),
                pageNumber, pageSize);
    }

    private Money apply(UUID clientId, Money amount, LedgerEntryType type, String reference, String description) {
        requireReference(reference);
        BillingAccount account = accounts.findByClientId(clientId)
                .orElseThrow(() -> new BillingNotFoundException("Billing account"));
        if (!account.isPrepaid()) {
            throw new InvalidBillingStateException("Only prepaid accounts hold credit");
        }
        requirePlanCurrency(account, amount);
        return transactions.inTransaction(() -> {
            LedgerEntry entry = LedgerEntry.of(clientId, type, amount, reference, description, clock.instant());
            if (!credits.appendLedger(entry)) {
                return credits.balance(clientId);
            }
            move(clientId, amount);
            return credits.balance(clientId);
        });
    }

    private void move(UUID clientId, Money amount) {
        if (amount.isNegative()) {
            if (!credits.debitIfSufficient(clientId, amount.negate())) {
                throw new InsufficientCreditException(credits.balance(clientId), amount.negate());
            }
            return;
        }
        credits.credit(clientId, amount);
    }

    private void requirePlanCurrency(BillingAccount account, Money amount) {
        String currency = plans.findById(account.planId()).orElseThrow(() -> new BillingNotFoundException("Plan")).currency();
        if (!amount.currency().equals(currency)) {
            throw new InvalidBillingDataException("Amount must be in the plan currency " + currency);
        }
    }

    private static void requireReference(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new InvalidBillingDataException("A reference is required");
        }
    }
}
