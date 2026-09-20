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

package com.techeazy.notification.billing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The bill of one client for one period.
 *
 * <p>Lifecycle: {@code DRAFT} (calculated, can be regenerated) to {@code ISSUED} (numbered, immutable) to
 * {@code PAID} once payments cover the total; an invoice can be voided from {@code DRAFT} or from {@code ISSUED}
 * while no payment has been recorded. Every transition is enforced here, so no caller can skip a rule.
 */
public final class Invoice {

    /** Plain snapshot used by persistence adapters to save and restore an invoice without exposing its internals. */
    public record State(UUID id, UUID clientId, UUID planId, BillingPeriod period, String currency, BigDecimal taxRate,
                        List<InvoiceLine> lines, InvoiceStatus status, String number, Instant createdAt, Instant issuedAt,
                        Instant dueAt, Instant paidAt, Instant voidedAt, String voidReason, List<Payment> payments) {}

    private final UUID id;
    private final UUID clientId;
    private final BillingPeriod period;
    private final String currency;
    private final Instant createdAt;
    private final List<Payment> payments;
    private UUID planId;
    private BigDecimal taxRate;
    private List<InvoiceLine> lines;
    private InvoiceStatus status;
    private String number;
    private Instant issuedAt;
    private Instant dueAt;
    private Instant paidAt;
    private Instant voidedAt;
    private String voidReason;

    private Invoice(State state) {
        this.id = state.id();
        this.clientId = state.clientId();
        this.planId = state.planId();
        this.period = state.period();
        this.currency = state.currency();
        this.taxRate = TaxRate.normalize(state.taxRate());
        this.lines = List.copyOf(state.lines());
        this.status = state.status();
        this.number = state.number();
        this.createdAt = state.createdAt();
        this.issuedAt = state.issuedAt();
        this.dueAt = state.dueAt();
        this.paidAt = state.paidAt();
        this.voidedAt = state.voidedAt();
        this.voidReason = state.voidReason();
        this.payments = new ArrayList<>(state.payments());
    }

    public static Invoice draft(UUID clientId, UUID planId, BillingPeriod period, String currency, BigDecimal taxRate,
                                List<InvoiceLine> lines, Instant now) {
        return new Invoice(new State(UUID.randomUUID(), clientId, planId, period, currency, taxRate, lines,
                InvoiceStatus.DRAFT, null, now, null, null, null, null, null, List.of()));
    }

    public static Invoice restore(State state) {
        return new Invoice(state);
    }

    public State state() {
        return new State(id, clientId, planId, period, currency, taxRate, lines, status, number, createdAt, issuedAt,
                dueAt, paidAt, voidedAt, voidReason, List.copyOf(payments));
    }

    public void regenerate(UUID newPlanId, BigDecimal newTaxRate, List<InvoiceLine> newLines) {
        requireStatus("regenerated", InvoiceStatus.DRAFT);
        this.planId = newPlanId;
        this.taxRate = TaxRate.normalize(newTaxRate);
        this.lines = List.copyOf(newLines);
    }

    public void issue(String invoiceNumber, Instant now, int paymentTermsDays) {
        requireStatus("issued", InvoiceStatus.DRAFT);
        this.number = invoiceNumber;
        this.issuedAt = now;
        this.dueAt = now.plus(paymentTermsDays, ChronoUnit.DAYS);
        this.status = InvoiceStatus.ISSUED;
        if (total().isZero()) {
            markPaid(now);
        }
    }

    public void recordPayment(Payment payment, Instant now) {
        requireStatus("paid", InvoiceStatus.ISSUED);
        if (!payment.amount().currency().equals(currency)) {
            throw new InvalidBillingDataException("Payment currency must be " + currency);
        }
        if (payment.amount().compareTo(outstanding()) > 0) {
            throw new InvalidBillingStateException("Payment exceeds the outstanding amount of " + outstanding().display());
        }
        payments.add(payment);
        if (outstanding().isZero()) {
            markPaid(now);
        }
    }

    public void voidInvoice(String reason, Instant now) {
        requireStatus("voided", InvoiceStatus.DRAFT, InvoiceStatus.ISSUED);
        if (!payments.isEmpty()) {
            throw new InvalidBillingStateException("An invoice with payments cannot be voided");
        }
        if (reason == null || reason.isBlank()) {
            throw new InvalidBillingDataException("A reason is required to void an invoice");
        }
        this.status = InvoiceStatus.VOID;
        this.voidedAt = now;
        this.voidReason = reason;
    }

    public boolean hasPaymentReference(String reference) {
        return payments.stream().anyMatch(p -> p.reference().equals(reference));
    }

    public InvoiceTotals totals() {
        return InvoiceTotals.of(currency, lines, taxRate);
    }

    public Money subtotal() {
        return totals().subtotal();
    }

    public Money taxAmount() {
        return totals().tax();
    }

    public Money total() {
        return totals().total();
    }

    public Money paid() {
        return payments.stream().map(Payment::amount).reduce(Money.zero(currency), Money::plus);
    }

    public Money outstanding() {
        return total().minus(paid());
    }

    public UUID id() { return id; }
    public UUID clientId() { return clientId; }
    public UUID planId() { return planId; }
    public BillingPeriod period() { return period; }
    public String currency() { return currency; }
    public BigDecimal taxRate() { return taxRate; }
    public List<InvoiceLine> lines() { return lines; }
    public InvoiceStatus status() { return status; }
    public String number() { return number; }
    public Instant createdAt() { return createdAt; }
    public Instant issuedAt() { return issuedAt; }
    public Instant dueAt() { return dueAt; }
    public Instant paidAt() { return paidAt; }
    public Instant voidedAt() { return voidedAt; }
    public String voidReason() { return voidReason; }
    public List<Payment> payments() { return Collections.unmodifiableList(payments); }

    private void markPaid(Instant now) {
        this.status = InvoiceStatus.PAID;
        this.paidAt = now;
    }

    private void requireStatus(String action, InvoiceStatus... allowed) {
        for (InvoiceStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new InvalidBillingStateException("An invoice that is " + status + " cannot be " + action);
    }
}
