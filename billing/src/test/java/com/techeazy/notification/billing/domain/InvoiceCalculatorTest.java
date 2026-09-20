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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceCalculatorTest {

    private final InvoiceCalculator calculator = new InvoiceCalculator();

    private static Plan plan(Money fee, Map<Channel, ChannelRate> rates) {
        return new Plan(UUID.randomUUID(), "standard", "USD", fee, new BigDecimal("0.10"), rates, true);
    }

    @Test
    void chargesOnlyWhatExceedsTheFreeAllowance() {
        Plan plan = plan(Money.zero("USD"), Map.of(Channel.SMS, new ChannelRate(Money.of("0.05", "USD"), 1000)));

        List<InvoiceLine> lines = calculator.linesFor(plan, Map.of(Channel.SMS, 1500L));

        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.kind()).isEqualTo(InvoiceLineKind.USAGE);
            assertThat(line.channel()).isEqualTo(Channel.SMS);
            assertThat(line.quantity()).isEqualTo(500);
            assertThat(line.amount()).isEqualTo(Money.of("25.00", "USD"));
            assertThat(line.description()).contains("1500 sent", "1000 included free");
        });
    }

    @Test
    void usageInsideTheAllowanceIsListedButFree() {
        Plan plan = plan(Money.zero("USD"), Map.of(Channel.EMAIL, new ChannelRate(Money.of("0.01", "USD"), 5000)));

        List<InvoiceLine> lines = calculator.linesFor(plan, Map.of(Channel.EMAIL, 120L));

        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isZero();
            assertThat(line.amount().isZero()).isTrue();
            assertThat(line.description()).contains("120 sent", "120 included free");
        });
    }

    @Test
    void omitsChannelsWithoutUsageAndKeepsChannelOrder() {
        Plan plan = plan(Money.zero("USD"), Map.of(Channel.SMS, new ChannelRate(Money.of("1", "USD"), 0),
                Channel.PUSH, new ChannelRate(Money.of("1", "USD"), 0)));

        List<InvoiceLine> lines = calculator.linesFor(plan, Map.of(Channel.PUSH, 2L, Channel.SMS, 3L, Channel.EMAIL, 0L));

        assertThat(lines).extracting(InvoiceLine::channel).containsExactly(Channel.SMS, Channel.PUSH);
    }

    @Test
    void addsThePlatformFeeAsItsOwnLine() {
        Plan plan = plan(Money.of("49.99", "USD"), Map.of());

        List<InvoiceLine> lines = calculator.linesFor(plan, Map.of());

        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.kind()).isEqualTo(InvoiceLineKind.PLATFORM_FEE);
            assertThat(line.channel()).isNull();
            assertThat(line.amount()).isEqualTo(Money.of("49.99", "USD"));
        });
    }

    @Test
    void usageOnAnUnpricedChannelAppearsAsAFreeLine() {
        List<InvoiceLine> lines = calculator.linesFor(plan(Money.zero("USD"), Map.of()), Map.of(Channel.WHATSAPP, 40L));

        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualTo(40);
            assertThat(line.amount().isZero()).isTrue();
        });
    }

    @Test
    void roundsEachLineToTwoDecimals() {
        Plan plan = plan(Money.zero("USD"), Map.of(Channel.SMS, new ChannelRate(Money.of("0.0035", "USD"), 0)));

        assertThat(calculator.linesFor(plan, Map.of(Channel.SMS, 1000L)).get(0).amount()).isEqualTo(Money.of("3.50", "USD"));
        assertThat(calculator.linesFor(plan, Map.of(Channel.SMS, 3L)).get(0).amount()).isEqualTo(Money.of("0.01", "USD"));
    }

    @Test
    void sameInputAlwaysGivesTheSameLines() {
        Plan plan = plan(Money.of("10", "USD"), Map.of(Channel.SMS, new ChannelRate(Money.of("0.02", "USD"), 10)));
        Map<Channel, Long> usage = Map.of(Channel.SMS, 55L);

        assertThat(calculator.linesFor(plan, usage)).isEqualTo(calculator.linesFor(plan, usage));
    }
}
