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
import com.techeazy.notification.billing.domain.BillingNotFoundException;
import com.techeazy.notification.billing.domain.BillingPeriod;
import com.techeazy.notification.billing.domain.InvalidBillingStateException;
import com.techeazy.notification.billing.domain.Invoice;
import com.techeazy.notification.billing.domain.InvoiceStatus;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.billing.domain.Plan;
import com.techeazy.notification.domain.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.techeazy.notification.billing.application.BillingFixture.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceServiceTest {

    private static final BillingPeriod AUGUST = BillingPeriod.parse("2026-08");

    private final BillingFixture f = new BillingFixture();
    private final UUID client = UUID.randomUUID();
    private Plan plan;

    @BeforeEach
    void postpaidClientWithAugustUsage() {
        plan = f.smsPlan("0.05", 1000);
        f.account(client, plan, BillingMode.POSTPAID, null);
        f.usage.sent(client, Channel.SMS, "2026-08-10T10:00:00Z", 1500);
        f.usage.sent(client, Channel.SMS, "2026-09-02T10:00:00Z", 999);
    }

    private static Money usd(String amount) {
        return Money.of(amount, USD);
    }

    @Test
    void generatesADraftFromThePeriodsUsageOnly() {
        Invoice invoice = f.invoiceService.generateFor(client, AUGUST);

        assertThat(invoice.status()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(invoice.lines()).singleElement().satisfies(line -> assertThat(line.quantity()).isEqualTo(500));
        assertThat(invoice.subtotal()).isEqualTo(usd("25.00"));
        assertThat(invoice.total()).isEqualTo(usd("27.50"));
        assertThat(invoice.planId()).isEqualTo(plan.id());
    }

    @Test
    void aPeriodThatIsNotOverYetCannotBeInvoiced() {
        BillingPeriod september = BillingPeriod.parse("2026-09");

        assertThatThrownBy(() -> f.invoiceService.generateFor(client, september))
                .isInstanceOf(InvalidBillingStateException.class);
    }

    @Test
    void prepaidAndUnknownAccountsAreNotInvoiced() {
        UUID prepaid = UUID.randomUUID();
        f.account(prepaid, f.smsPlan("1", 0), BillingMode.PREPAID, null);

        assertThatThrownBy(() -> f.invoiceService.generateFor(prepaid, AUGUST)).isInstanceOf(InvalidBillingStateException.class);
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> f.invoiceService.generateFor(unknown, AUGUST)).isInstanceOf(BillingNotFoundException.class);
    }

    @Test
    void generatingAgainNeverCreatesASecondInvoice() {
        Invoice first = f.invoiceService.generateFor(client, AUGUST);
        Invoice again = f.invoiceService.generateFor(client, AUGUST);

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(f.invoices.count()).isEqualTo(1);
    }

    @Test
    void aDraftIsRecalculatedFromCurrentUsage() {
        f.invoiceService.generateFor(client, AUGUST);
        f.usage.sent(client, Channel.SMS, "2026-08-31T22:00:00Z", 500);

        Invoice refreshed = f.invoiceService.generateFor(client, AUGUST);

        assertThat(refreshed.subtotal()).isEqualTo(usd("50.00"));
        assertThat(f.invoices.count()).isEqualTo(1);
    }

    @Test
    void anIssuedInvoiceIsNotChangedByLaterGeneration() {
        Invoice draft = f.invoiceService.generateFor(client, AUGUST);
        f.invoiceService.issue(draft.id());
        f.usage.sent(client, Channel.SMS, "2026-08-31T22:00:00Z", 500);

        Invoice again = f.invoiceService.generateFor(client, AUGUST);

        assertThat(again.status()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(again.subtotal()).isEqualTo(usd("25.00"));
    }

    @Test
    void aVoidedInvoiceLetsThePeriodBeInvoicedAgain() {
        Invoice first = f.invoiceService.generateFor(client, AUGUST);
        f.invoiceService.voidInvoice(first.id(), "wrong plan");

        Invoice second = f.invoiceService.generateFor(client, AUGUST);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.status()).isEqualTo(InvoiceStatus.DRAFT);
    }

    @Test
    void generatingForAllSkipsPrepaidReportsExistingAndCreatesTheRest() {
        UUID other = UUID.randomUUID();
        f.account(other, f.smsPlan("0.02", 0), BillingMode.POSTPAID, null);
        f.usage.sent(other, Channel.SMS, "2026-08-05T10:00:00Z", 100);
        f.account(UUID.randomUUID(), f.smsPlan("1", 0), BillingMode.PREPAID, null);
        f.invoiceService.generateFor(client, AUGUST);

        GenerationReport report = f.invoiceService.generateForAll(AUGUST);

        assertThat(report.created()).isEqualTo(1);
        assertThat(report.existing()).isEqualTo(1);
        assertThat(report.failures()).isEmpty();
        assertThat(f.invoices.count()).isEqualTo(2);
    }

    @Test
    void generatingForAllReportsAFailureWithoutStoppingTheOthers() {
        UUID broken = UUID.randomUUID();
        f.account(broken, f.smsPlan("0.02", 0), BillingMode.POSTPAID, null);
        f.plans.findAll().stream().filter(p -> f.accounts.findByClientId(broken).orElseThrow().planId().equals(p.id()))
                .forEach(p -> f.plans.byId().remove(p.id()));

        GenerationReport report = f.invoiceService.generateForAll(AUGUST);

        assertThat(report.created()).isEqualTo(1);
        assertThat(report.failures()).singleElement().satisfies(text -> assertThat(text).startsWith(broken.toString()));
    }

    @Test
    void issuingNumbersInvoicesInSequence() {
        UUID other = UUID.randomUUID();
        f.account(other, f.smsPlan("0.02", 0), BillingMode.POSTPAID, null);
        f.usage.sent(other, Channel.SMS, "2026-08-05T10:00:00Z", 100);
        Invoice a = f.invoiceService.issue(f.invoiceService.generateFor(client, AUGUST).id());
        Invoice b = f.invoiceService.issue(f.invoiceService.generateFor(other, AUGUST).id());

        assertThat(a.number()).isEqualTo("INV-2026-000001");
        assertThat(b.number()).isEqualTo("INV-2026-000002");
        assertThat(a.dueAt()).isEqualTo(f.clock.instant().plusSeconds(30L * 24 * 3600));
    }

    @Test
    void paymentsAreRecordedOnceAndSettleTheInvoice() {
        Invoice issued = f.invoiceService.issue(f.invoiceService.generateFor(client, AUGUST).id());

        f.invoiceService.recordPayment(issued.id(), usd("10.00"), "bank transfer", "wire-1");
        Invoice repeated = f.invoiceService.recordPayment(issued.id(), usd("10.00"), "bank transfer", "wire-1");
        Invoice paid = f.invoiceService.recordPayment(issued.id(), usd("17.50"), "bank transfer", "wire-2");

        assertThat(repeated.payments()).hasSize(1);
        assertThat(paid.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(paid.payments()).hasSize(2);
    }

    @Test
    void issueDraftsIssuesOnlyThePeriodsDrafts() {
        UUID other = UUID.randomUUID();
        f.account(other, f.smsPlan("0.02", 0), BillingMode.POSTPAID, null);
        f.usage.sent(other, Channel.SMS, "2026-07-05T10:00:00Z", 100);
        f.invoiceService.generateFor(client, AUGUST);
        f.invoiceService.generateFor(other, BillingPeriod.parse("2026-07"));

        int issued = f.invoiceService.issueDrafts(AUGUST);

        assertThat(issued).isEqualTo(1);
        assertThat(f.invoiceService.forClient(client, Set.of(InvoiceStatus.ISSUED))).hasSize(1);
        assertThat(f.invoiceService.forClient(other, Set.of(InvoiceStatus.DRAFT))).hasSize(1);
    }

    @Test
    void searchAndLookupWork() {
        Invoice draft = f.invoiceService.generateFor(client, AUGUST);

        assertThat(f.invoiceService.get(draft.id()).id()).isEqualTo(draft.id());
        assertThat(f.invoiceService.search(InvoiceStatus.DRAFT, client)).hasSize(1);
        assertThat(f.invoiceService.search(InvoiceStatus.PAID, null)).isEmpty();
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> f.invoiceService.get(unknown)).isInstanceOf(BillingNotFoundException.class);
        assertThat(List.of(draft.status())).containsExactly(InvoiceStatus.DRAFT);
    }
}
