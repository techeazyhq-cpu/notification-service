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

package com.techeazy.notification.adminapi;

import com.techeazy.notification.billing.application.LedgerPage;
import com.techeazy.notification.billing.domain.AccountStatus;
import com.techeazy.notification.billing.domain.BillingAccount;
import com.techeazy.notification.billing.domain.BillingMode;
import com.techeazy.notification.billing.domain.Invoice;
import com.techeazy.notification.billing.domain.InvoiceLine;
import com.techeazy.notification.billing.domain.InvoiceLineKind;
import com.techeazy.notification.billing.domain.InvoiceStatus;
import com.techeazy.notification.billing.domain.LedgerEntry;
import com.techeazy.notification.billing.domain.LedgerEntryType;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.billing.domain.Payment;
import com.techeazy.notification.billing.domain.Plan;
import com.techeazy.notification.domain.Channel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Administrator-facing billing views. Money is a string next to its currency, never a floating point number. */
final class BillingAdminViews {

    private BillingAdminViews() {}

    record RateView(Channel channel, String unitPrice, long freeAllowance) {}

    record PlanView(UUID id, String name, String currency, String platformFee, BigDecimal taxRate, boolean active, List<RateView> rates) {}

    record AccountView(UUID clientId, String clientName, UUID planId, String planName, String currency, BillingMode mode,
                       AccountStatus status, String creditBalance, String monthlySpendCap, String billingEmail) {}

    record LineView(InvoiceLineKind kind, Channel channel, String description, long quantity, String unitPrice, String amount) {}

    record PaymentView(String amount, String method, String reference, Instant receivedAt) {}

    record InvoiceView(UUID id, String number, UUID clientId, String clientName, String month, InvoiceStatus status,
                       String currency, List<LineView> lines, String subtotal, BigDecimal taxRate, String tax, String total,
                       String paid, String outstanding, Instant issuedAt, Instant dueAt, Instant paidAt, String voidReason,
                       List<PaymentView> payments) {}

    record LedgerEntryView(UUID id, LedgerEntryType type, String amount, String reference, String description, Instant createdAt) {}

    record LedgerPageView(List<LedgerEntryView> items, long total, int page, int size) {}

    record GenerationView(int created, int existing, List<String> failures) {}

    static PlanView plan(Plan plan) {
        List<RateView> rates = plan.rates().entrySet().stream()
                .map(e -> new RateView(e.getKey(), e.getValue().unitPrice().unitFormatted(), e.getValue().freeAllowance())).toList();
        return new PlanView(plan.id(), plan.name(), plan.currency(), plan.platformFee().formatted(), plan.taxRate(), plan.active(), rates);
    }

    static AccountView account(BillingAccount account, String clientName, Plan plan) {
        return new AccountView(account.clientId(), clientName, plan.id(), plan.name(), plan.currency(), account.mode(),
                account.status(), account.isPrepaid() ? account.creditBalance().formatted() : null,
                account.spendCap().map(Money::formatted).orElse(null), account.billingEmail());
    }

    static InvoiceView invoice(Invoice invoice, String clientName) {
        return new InvoiceView(invoice.id(), invoice.number(), invoice.clientId(), clientName, invoice.period().label(),
                invoice.status(), invoice.currency(), invoice.lines().stream().map(BillingAdminViews::line).toList(),
                invoice.subtotal().formatted(), invoice.taxRate(), invoice.taxAmount().formatted(), invoice.total().formatted(),
                invoice.paid().formatted(), invoice.outstanding().formatted(), invoice.issuedAt(), invoice.dueAt(),
                invoice.paidAt(), invoice.voidReason(), invoice.payments().stream().map(BillingAdminViews::payment).toList());
    }

    static LedgerPageView ledger(LedgerPage page) {
        return new LedgerPageView(page.items().stream().map(BillingAdminViews::entry).toList(), page.total(), page.page(), page.size());
    }

    private static LineView line(InvoiceLine line) {
        return new LineView(line.kind(), line.channel(), line.description(), line.quantity(), line.unitPrice().unitFormatted(),
                line.amount().formatted());
    }

    private static PaymentView payment(Payment payment) {
        return new PaymentView(payment.amount().formatted(), payment.method(), payment.reference(), payment.receivedAt());
    }

    private static LedgerEntryView entry(LedgerEntry entry) {
        return new LedgerEntryView(entry.id(), entry.type(), entry.amount().formatted(), entry.reference(),
                entry.description(), entry.createdAt());
    }
}
