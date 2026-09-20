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

import com.techeazy.notification.billing.application.port.AccountRepository;
import com.techeazy.notification.billing.application.port.InvoiceRepository;
import com.techeazy.notification.billing.application.port.PlanRepository;
import com.techeazy.notification.billing.application.port.Transactions;
import com.techeazy.notification.billing.application.port.UsageReader;
import com.techeazy.notification.billing.domain.BillingAccount;
import com.techeazy.notification.billing.domain.BillingNotFoundException;
import com.techeazy.notification.billing.domain.BillingPeriod;
import com.techeazy.notification.billing.domain.InvalidBillingStateException;
import com.techeazy.notification.billing.domain.Invoice;
import com.techeazy.notification.billing.domain.InvoiceAlreadyExistsException;
import com.techeazy.notification.billing.domain.InvoiceCalculator;
import com.techeazy.notification.billing.domain.InvoiceLine;
import com.techeazy.notification.billing.domain.InvoiceStatus;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.billing.domain.Payment;
import com.techeazy.notification.billing.domain.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Invoices postpaid accounts from metered usage.
 *
 * <p>Generation is idempotent: a period that already has a non-void invoice is not billed twice, and a draft is
 * recalculated from current usage and the current plan. Prepaid accounts are never invoiced; their usage is paid
 * from credit. Only a closed period can be invoiced. Issued invoices are immutable.
 */
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);
    private static final int SEARCH_LIMIT = 200;

    private final InvoiceRepository invoices;
    private final AccountRepository accounts;
    private final PlanRepository plans;
    private final UsageReader usage;
    private final InvoiceCalculator calculator;
    private final Transactions transactions;
    private final Clock clock;
    private final int paymentTermsDays;

    public InvoiceService(InvoiceRepository invoices, AccountRepository accounts, PlanRepository plans,
                          UsageReader usage, InvoiceCalculator calculator, Transactions transactions, Clock clock,
                          int paymentTermsDays) {
        this.invoices = invoices;
        this.accounts = accounts;
        this.plans = plans;
        this.usage = usage;
        this.calculator = calculator;
        this.transactions = transactions;
        this.clock = clock;
        this.paymentTermsDays = paymentTermsDays;
    }

    public Invoice generateFor(UUID clientId, BillingPeriod period) {
        return transactions.inTransaction(() -> generate(clientId, period));
    }

    public GenerationReport generateForAll(BillingPeriod period) {
        int created = 0;
        int existing = 0;
        List<String> failures = new ArrayList<>();
        for (BillingAccount account : accounts.findAll()) {
            if (account.isPrepaid()) {
                continue;
            }
            try {
                boolean existed = invoices.findCurrent(account.clientId(), period).isPresent();
                generateFor(account.clientId(), period);
                created += existed ? 0 : 1;
                existing += existed ? 1 : 0;
            } catch (InvoiceAlreadyExistsException race) {
                existing++;
            } catch (RuntimeException e) {
                log.error("Invoice generation failed for client {} and period {}", account.clientId(), period.label(), e);
                failures.add(account.clientId() + ": " + e.getMessage());
            }
        }
        return new GenerationReport(created, existing, failures);
    }

    public int issueDrafts(BillingPeriod period) {
        int issued = 0;
        for (Invoice draft : invoices.search(InvoiceStatus.DRAFT, null, SEARCH_LIMIT)) {
            if (draft.period().equals(period)) {
                issue(draft.id());
                issued++;
            }
        }
        return issued;
    }

    public Invoice issue(UUID id) {
        return transactions.inTransaction(() -> {
            Invoice invoice = lockedInvoice(id);
            invoice.issue(invoices.nextNumber(clock.instant()), clock.instant(), paymentTermsDays);
            invoices.save(invoice);
            return invoice;
        });
    }

    public Invoice recordPayment(UUID id, Money amount, String method, String reference) {
        return transactions.inTransaction(() -> {
            Invoice invoice = lockedInvoice(id);
            if (invoice.hasPaymentReference(reference)) {
                return invoice;
            }
            invoice.recordPayment(new Payment(UUID.randomUUID(), amount, method, reference, clock.instant()), clock.instant());
            invoices.save(invoice);
            return invoice;
        });
    }

    public Invoice voidInvoice(UUID id, String reason) {
        return transactions.inTransaction(() -> {
            Invoice invoice = lockedInvoice(id);
            invoice.voidInvoice(reason, clock.instant());
            invoices.save(invoice);
            return invoice;
        });
    }

    public Invoice get(UUID id) {
        return invoices.findById(id).orElseThrow(() -> new BillingNotFoundException("Invoice"));
    }

    public List<Invoice> forClient(UUID clientId, Set<InvoiceStatus> statuses) {
        return invoices.findByClient(clientId, statuses);
    }

    public List<Invoice> search(InvoiceStatus status, UUID clientId) {
        return invoices.search(status, clientId, SEARCH_LIMIT);
    }

    private Invoice generate(UUID clientId, BillingPeriod period) {
        BillingAccount account = accounts.findByClientId(clientId)
                .orElseThrow(() -> new BillingNotFoundException("Billing account"));
        if (account.isPrepaid()) {
            throw new InvalidBillingStateException("Prepaid accounts are not invoiced");
        }
        if (!period.isClosedAt(clock.instant())) {
            throw new InvalidBillingStateException("Period " + period.label() + " is not over yet");
        }
        Plan plan = plans.findById(account.planId()).orElseThrow(() -> new BillingNotFoundException("Plan"));
        List<InvoiceLine> lines = calculator.linesFor(plan,
                usage.sentByChannel(clientId, period.start(), period.endExclusive()));
        Optional<Invoice> existing = invoices.findCurrent(clientId, period);
        if (existing.isPresent()) {
            return refreshDraft(existing.get(), plan, lines);
        }
        Invoice draft = Invoice.draft(clientId, plan.id(), period, plan.currency(), plan.taxRate(), lines, clock.instant());
        invoices.save(draft);
        return draft;
    }

    private Invoice refreshDraft(Invoice invoice, Plan plan, List<InvoiceLine> lines) {
        if (invoice.status() != InvoiceStatus.DRAFT) {
            return invoice;
        }
        Invoice locked = lockedInvoice(invoice.id());
        locked.regenerate(plan.id(), plan.taxRate(), lines);
        invoices.save(locked);
        return locked;
    }

    private Invoice lockedInvoice(UUID id) {
        return invoices.findByIdForUpdate(id).orElseThrow(() -> new BillingNotFoundException("Invoice"));
    }
}
