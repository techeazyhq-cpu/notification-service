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

import com.techeazy.notification.domain.Channel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceTest {

    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");

    private static InvoiceLine usage(String amount) {
        return new InvoiceLine(InvoiceLineKind.USAGE, Channel.SMS, "SMS", 100, Money.of("1", "USD"), Money.of(amount, "USD"));
    }

    private static Invoice draftOf(String... amounts) {
        List<InvoiceLine> lines = java.util.Arrays.stream(amounts).map(InvoiceTest::usage).toList();
        return Invoice.draft(UUID.randomUUID(), UUID.randomUUID(), BillingPeriod.parse("2026-08"), "USD", new BigDecimal("0.18"), lines, NOW);
    }

    private static Payment payment(String amount, String reference) {
        return new Payment(UUID.randomUUID(), Money.of(amount, "USD"), "bank transfer", reference, NOW);
    }

    @Test
    void totalsAreSubtotalPlusRoundedTax() {
        Invoice invoice = draftOf("100.00", "0.05");

        assertThat(invoice.subtotal()).isEqualTo(Money.of("100.05", "USD"));
        assertThat(invoice.taxAmount()).isEqualTo(Money.of("18.01", "USD"));
        assertThat(invoice.total()).isEqualTo(Money.of("118.06", "USD"));
    }

    @Test
    void issuingNumbersTheInvoiceAndSetsTheDueDate() {
        Invoice invoice = draftOf("10.00");

        invoice.issue("INV-2026-000001", NOW, 30);

        assertThat(invoice.status()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(invoice.number()).isEqualTo("INV-2026-000001");
        assertThat(invoice.dueAt()).isEqualTo(NOW.plus(30, ChronoUnit.DAYS));
    }

    @Test
    void aZeroInvoiceIsPaidTheMomentItIsIssued() {
        Invoice invoice = draftOf("0.00");

        invoice.issue("INV-2026-000002", NOW, 30);

        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    void onlyADraftCanBeRegeneratedOrIssued() {
        Invoice invoice = draftOf("10.00");
        invoice.regenerate(UUID.randomUUID(), BigDecimal.ZERO, List.of(usage("20.00")));
        invoice.issue("INV-2026-000003", NOW, 30);

        assertThat(invoice.subtotal()).isEqualTo(Money.of("20.00", "USD"));
        UUID plan = UUID.randomUUID();
        assertThatThrownBy(() -> invoice.regenerate(plan, BigDecimal.ZERO, List.of()))
                .isInstanceOf(InvalidBillingStateException.class);
        assertThatThrownBy(() -> invoice.issue("INV-2026-000004", NOW, 30)).isInstanceOf(InvalidBillingStateException.class);
    }

    @Test
    void paymentsAccumulateUntilTheInvoiceIsPaid() {
        Invoice invoice = draftOf("100.00");
        invoice.issue("INV-2026-000005", NOW, 30);

        invoice.recordPayment(payment("50.00", "p1"), NOW);
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(invoice.outstanding()).isEqualTo(Money.of("68.00", "USD"));

        invoice.recordPayment(payment("68.00", "p2"), NOW);
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoice.paidAt()).isEqualTo(NOW);
        assertThat(invoice.hasPaymentReference("p1")).isTrue();
    }

    @Test
    void refusesOverpaymentPaymentOnADraftAndWrongCurrency() {
        Invoice draft = draftOf("100.00");
        Payment small = payment("1.00", "x");
        assertThatThrownBy(() -> draft.recordPayment(small, NOW)).isInstanceOf(InvalidBillingStateException.class);

        Invoice issued = draftOf("100.00");
        issued.issue("INV-2026-000006", NOW, 30);
        Payment big = payment("500.00", "big");
        assertThatThrownBy(() -> issued.recordPayment(big, NOW)).isInstanceOf(InvalidBillingStateException.class);
        Payment euros = new Payment(UUID.randomUUID(), Money.of("1", "EUR"), "card", "eur", NOW);
        assertThatThrownBy(() -> issued.recordPayment(euros, NOW)).isInstanceOf(InvalidBillingDataException.class);
    }

    @Test
    void aDraftOrUnpaidIssuedInvoiceCanBeVoidedWithAReason() {
        Invoice draft = draftOf("10.00");
        draft.voidInvoice("wrong client", NOW);
        assertThat(draft.status()).isEqualTo(InvoiceStatus.VOID);
        assertThat(draft.voidReason()).isEqualTo("wrong client");

        Invoice issued = draftOf("10.00");
        issued.issue("INV-2026-000007", NOW, 30);
        issued.voidInvoice("duplicate", NOW);
        assertThat(issued.status()).isEqualTo(InvoiceStatus.VOID);
    }

    @Test
    void anInvoiceWithPaymentsOrNoReasonCannotBeVoided() {
        Invoice paidPartly = draftOf("100.00");
        paidPartly.issue("INV-2026-000008", NOW, 30);
        paidPartly.recordPayment(payment("10.00", "p"), NOW);
        assertThatThrownBy(() -> paidPartly.voidInvoice("oops", NOW)).isInstanceOf(InvalidBillingStateException.class);

        Invoice draft = draftOf("10.00");
        assertThatThrownBy(() -> draft.voidInvoice(" ", NOW)).isInstanceOf(InvalidBillingDataException.class);
    }

    @Test
    void aVoidOrPaidInvoiceIsFinal() {
        Invoice voided = draftOf("10.00");
        voided.voidInvoice("mistake", NOW);
        assertThatThrownBy(() -> voided.issue("INV-2026-000009", NOW, 30)).isInstanceOf(InvalidBillingStateException.class);
        assertThatThrownBy(() -> voided.voidInvoice("again", NOW)).isInstanceOf(InvalidBillingStateException.class);
    }

    @Test
    void stateSnapshotRestoresAnEqualInvoice() {
        Invoice invoice = draftOf("100.00");
        invoice.issue("INV-2026-000010", NOW, 30);
        invoice.recordPayment(payment("20.00", "p1"), NOW);

        Invoice restored = Invoice.restore(invoice.state());

        assertThat(restored.state()).isEqualTo(invoice.state());
        assertThat(restored.outstanding()).isEqualTo(invoice.outstanding());
    }
}
