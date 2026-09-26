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

package com.techeazy.notification.clientapi;

import com.techeazy.notification.billing.application.LedgerPage;
import com.techeazy.notification.billing.application.UsageStatement;
import com.techeazy.notification.billing.domain.AccountStatus;
import com.techeazy.notification.billing.domain.BillingAccount;
import com.techeazy.notification.billing.domain.BillingMode;
import com.techeazy.notification.billing.domain.InvoiceLine;
import com.techeazy.notification.billing.domain.InvoiceLineKind;
import com.techeazy.notification.billing.domain.InvoiceStatus;
import com.techeazy.notification.billing.domain.Invoice;
import com.techeazy.notification.billing.domain.LedgerEntry;
import com.techeazy.notification.billing.domain.LedgerEntryType;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.billing.domain.Payment;
import com.techeazy.notification.billing.domain.Plan;
import com.techeazy.notification.domain.Channel;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the Client API returns for billing. Money is always a string with the currency next to it, so no client ever
 * does arithmetic on floating point numbers. {@code creditBalance} is present for prepaid accounts only and
 * {@code monthlySpendCap} for postpaid accounts with a cap.
 */
final class BillingViews {

    private BillingViews() {}

    record RateView(Channel channel, String unitPrice, long freeAllowance) {}

    record AccountView(String planName, String currency, BillingMode mode, AccountStatus status, String creditBalance,
                       String monthlySpendCap, String platformFee, String taxRate, List<RateView> rates) {}

    record LineView(InvoiceLineKind kind, Channel channel, String description, long quantity, String unitPrice, String amount) {}

    record UsageView(String month, String currency, boolean estimate, List<LineView> lines, String subtotal, String tax,
                     String total) {}

    record PaymentView(String amount, String method, String reference, Instant receivedAt) {}

    record InvoiceSummaryView(UUID id, String number, String month, InvoiceStatus status, String currency, String total,
                              String outstanding, Instant issuedAt, Instant dueAt) {}

    record InvoiceView(UUID id, String number, String month, InvoiceStatus status, String currency, List<LineView> lines,
                       String subtotal, String taxRate, String tax, String total, String paid, String outstanding,
                       Instant issuedAt, Instant dueAt, Instant paidAt, List<PaymentView> payments) {}

    record LedgerEntryView(UUID id, LedgerEntryType type, String amount, String reference, String description, Instant createdAt) {}

    record LedgerPageView(String currency, List<LedgerEntryView> items, long total, int page, int size) {}

    static AccountView account(BillingAccount account, Plan plan) {
        List<RateView> rates = plan.rates().entrySet().stream()
                .map(e -> new RateView(e.getKey(), e.getValue().unitPrice().unitFormatted(), e.getValue().freeAllowance())).toList();
        return new AccountView(plan.name(), plan.currency(), account.mode(), account.status(),
                account.isPrepaid() ? account.creditBalance().formatted() : null,
                account.spendCap().map(Money::formatted).orElse(null), plan.platformFee().formatted(),
                percent(plan.taxRate()), rates);
    }

    static UsageView usage(UsageStatement statement) {
        return new UsageView(statement.period().label(), statement.currency(), statement.estimate(),
                statement.lines().stream().map(BillingViews::line).toList(), statement.totals().subtotal().formatted(),
                statement.totals().tax().formatted(), statement.totals().total().formatted());
    }

    static InvoiceSummaryView summary(Invoice invoice) {
        return new InvoiceSummaryView(invoice.id(), invoice.number(), invoice.period().label(), invoice.status(),
                invoice.currency(), invoice.total().formatted(), invoice.outstanding().formatted(), invoice.issuedAt(), invoice.dueAt());
    }

    static InvoiceView invoice(Invoice invoice) {
        return new InvoiceView(invoice.id(), invoice.number(), invoice.period().label(), invoice.status(), invoice.currency(),
                invoice.lines().stream().map(BillingViews::line).toList(), invoice.subtotal().formatted(),
                percent(invoice.taxRate()), invoice.taxAmount().formatted(), invoice.total().formatted(),
                invoice.paid().formatted(), invoice.outstanding().formatted(), invoice.issuedAt(), invoice.dueAt(),
                invoice.paidAt(), invoice.payments().stream().map(BillingViews::payment).toList());
    }

    static LedgerPageView ledger(LedgerPage page, String currency) {
        return new LedgerPageView(currency, page.items().stream().map(BillingViews::entry).toList(), page.total(),
                page.page(), page.size());
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

    private static String percent(java.math.BigDecimal fraction) {
        return fraction.movePointRight(2).stripTrailingZeros().toPlainString();
    }
}
