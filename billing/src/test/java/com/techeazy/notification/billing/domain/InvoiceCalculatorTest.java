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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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


    private static Money usd(String amount) {
        return Money.of(amount, "USD");
    }

    private static OtpPrices smsOtpAt(String price) {
        return new OtpPrices(Map.of(Channel.SMS, usd(price)));
    }

    @Test
    void aTenantsOneTimePasswordsGetALineOfTheirOwnAtItsOtpPrice() {
        Plan plan = plan(Money.zero("USD"), Map.of(Channel.SMS, new ChannelRate(usd("0.008"), 0)));

        List<InvoiceLine> lines = calculator.linesFor(plan, smsOtpAt("0.012"),
                Map.of(Channel.SMS, new SentCount(12_000, 3_000)));

        assertThat(lines).extracting(InvoiceLine::kind, InvoiceLine::quantity, InvoiceLine::unitPrice, InvoiceLine::amount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(InvoiceLineKind.USAGE, 12_000L, usd("0.008"), usd("96.00")),
                        org.assertj.core.groups.Tuple.tuple(InvoiceLineKind.OTP_USAGE, 3_000L, usd("0.012"), usd("36.00")));
        assertThat(lines.get(1).description()).isEqualTo("SMS one-time passwords: 3000 sent, 0 included free");
    }

    @Test
    void theFreeAllowanceCoversOrdinaryMessagesFirstAndWhatIsLeftCoversOneTimePasswords() {
        Plan plan = plan(Money.zero("USD"), Map.of(Channel.SMS, new ChannelRate(usd("0.008"), 1_000)));

        List<InvoiceLine> lines = calculator.linesFor(plan, smsOtpAt("0.012"), Map.of(Channel.SMS, new SentCount(700, 500)));

        assertThat(lines).extracting(InvoiceLine::kind, InvoiceLine::quantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(InvoiceLineKind.USAGE, 0L),
                        org.assertj.core.groups.Tuple.tuple(InvoiceLineKind.OTP_USAGE, 200L));
        assertThat(lines.get(0).description()).contains("700 sent", "700 included free");
        assertThat(lines.get(1).description()).contains("500 sent", "300 included free");
    }

    @Test
    void oneTimePasswordsAloneShowOnlyTheirOwnLine() {
        Plan plan = plan(Money.zero("USD"), Map.of(Channel.SMS, new ChannelRate(usd("0.008"), 0)));

        List<InvoiceLine> lines = calculator.linesFor(plan, smsOtpAt("0.012"), Map.of(Channel.SMS, new SentCount(0, 10)));

        assertThat(lines).singleElement().satisfies(line -> assertThat(line.kind()).isEqualTo(InvoiceLineKind.OTP_USAGE));
    }

    /** A tenant without an OTP price for the channel is billed exactly as before OTP prices existed. */
    @Test
    void withoutAnOtpPriceOneTimePasswordsAreOrdinaryMessagesOfTheChannel() {
        Plan plan = plan(Money.zero("USD"), Map.of(Channel.SMS, new ChannelRate(usd("0.008"), 100)));
        OtpPrices emailOnly = new OtpPrices(Map.of(Channel.EMAIL, usd("0.002")));

        List<InvoiceLine> lines = calculator.linesFor(plan, emailOnly, Map.of(Channel.SMS, new SentCount(400, 100)));

        assertThat(lines).isEqualTo(calculator.linesFor(plan, Map.of(Channel.SMS, 500L)));
    }

    @Test
    void otpPricesInAnotherCurrencyThanThePlanAreRefused() {
        Plan plan = plan(Money.zero("USD"), Map.of(Channel.SMS, new ChannelRate(usd("0.008"), 0)));
        OtpPrices euros = new OtpPrices(Map.of(Channel.SMS, Money.of("0.01", "EUR")));
        Map<Channel, SentCount> sent = Map.of(Channel.SMS, new SentCount(1, 1));

        assertThatThrownBy(() -> calculator.linesFor(plan, euros, sent)).isInstanceOf(InvalidBillingStateException.class);
    }

    @Test
    void otpPricesAreNeverNegativeAndShareOneCurrency() {
        Map<Channel, Money> negative = Map.of(Channel.SMS, usd("-0.01"));
        assertThatThrownBy(() -> new OtpPrices(negative))
                .isInstanceOf(InvalidBillingDataException.class);
        Map<Channel, Money> mixed = Map.of(Channel.SMS, usd("0.01"), Channel.EMAIL, Money.of("0.01", "EUR"));
        assertThatThrownBy(() -> new OtpPrices(mixed))
                .isInstanceOf(InvalidBillingDataException.class);
    }
}
