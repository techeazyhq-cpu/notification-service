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
import com.techeazy.notification.billing.domain.Invoice;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.domain.Channel;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.techeazy.notification.billing.application.BillingFixture.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageAndCsvTest {

    private final BillingFixture f = new BillingFixture();
    private final UUID client = UUID.randomUUID();

    private void postpaidWithUsage() {
        f.account(client, f.smsPlan("0.05", 1000), BillingMode.POSTPAID, null);
        f.usage.sent(client, Channel.SMS, "2026-08-10T10:00:00Z", 1500);
        f.usage.sent(client, Channel.SMS, "2026-09-05T10:00:00Z", 200);
    }

    @Test
    void theRunningMonthIsAnEstimateAndAClosedMonthIsNot() {
        postpaidWithUsage();

        UsageStatement september = f.usageService.statement(client, BillingPeriod.parse("2026-09"));
        UsageStatement august = f.usageService.statement(client, BillingPeriod.parse("2026-08"));

        assertThat(september.estimate()).isTrue();
        assertThat(september.totals().total().isZero()).isTrue();
        assertThat(august.estimate()).isFalse();
        assertThat(august.totals().subtotal()).isEqualTo(Money.of("25.00", USD));
    }

    @Test
    void theStatementUsesTheSameCalculationAsTheInvoice() {
        postpaidWithUsage();
        BillingPeriod august = BillingPeriod.parse("2026-08");

        UsageStatement statement = f.usageService.statement(client, august);
        Invoice invoice = f.invoiceService.generateFor(client, august);

        assertThat(statement.lines()).isEqualTo(invoice.lines());
        assertThat(statement.totals()).isEqualTo(invoice.totals());
    }

    @Test
    void aClientWithoutAnAccountHasNoStatement() {
        assertThatThrownBy(() -> f.usageService.statement(UUID.randomUUID(), BillingPeriod.parse("2026-08")))
                .isInstanceOf(BillingNotFoundException.class);
    }

    @Test
    void csvHasAHeaderBlockLinesAndTotals() {
        postpaidWithUsage();
        Invoice invoice = f.invoiceService.issue(f.invoiceService.generateFor(client, BillingPeriod.parse("2026-08")).id());

        String csv = InvoiceCsv.render(invoice);

        assertThat(csv).contains("Invoice,INV-2026-000001", "Period,2026-08", "Status,ISSUED", "Currency,USD");
        assertThat(csv).contains("Description,Channel,Quantity,Unit price,Amount");
        assertThat(csv).contains("\"SMS messages: 1500 sent, 1000 included free\",SMS,500,0.05,25.00");
        assertThat(csv).contains("Subtotal,,,,25.00", "Tax 10%,,,,2.50", "Total,,,,27.50", "Paid,,,,0.00", "Outstanding,,,,27.50");
        assertThat(csv).endsWith("\r\n");
    }

    @Test
    void aDraftIsLabelledAsSuchInTheCsv() {
        postpaidWithUsage();

        String csv = InvoiceCsv.render(f.invoiceService.generateFor(client, BillingPeriod.parse("2026-08")));

        assertThat(csv).contains("Invoice,(draft)", "Issued,");
    }
}
