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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanTest {

    private static Plan plan(BigDecimal tax, Money fee, Map<Channel, ChannelRate> rates) {
        return new Plan(UUID.randomUUID(), "standard", "USD", fee, tax, rates, true);
    }

    @Test
    void anUnpricedChannelIsFree() {
        Plan plan = plan(BigDecimal.ZERO, Money.zero("USD"), Map.of(Channel.SMS, new ChannelRate(Money.of("0.05", "USD"), 100)));

        assertThat(plan.rateFor(Channel.SMS).unitPrice()).isEqualTo(Money.of("0.05", "USD"));
        assertThat(plan.rateFor(Channel.EMAIL).unitPrice().isZero()).isTrue();
        assertThat(plan.rateFor(Channel.EMAIL).freeAllowance()).isZero();
    }

    @Test
    void rejectsTaxOutsideZeroToOne() {
        Money noFee = Money.zero("USD");
        BigDecimal above = new BigDecimal("1.5");
        BigDecimal below = new BigDecimal("-0.1");

        assertThatThrownBy(() -> plan(above, noFee, Map.of())).isInstanceOf(InvalidBillingDataException.class);
        assertThatThrownBy(() -> plan(below, noFee, Map.of())).isInstanceOf(InvalidBillingDataException.class);
    }

    @Test
    void rejectsRatesAndFeesInAnotherCurrency() {
        Map<Channel, ChannelRate> euroRates = Map.of(Channel.SMS, new ChannelRate(Money.of("0.05", "EUR"), 0));

        Money noFee = Money.zero("USD");
        Money euroFee = Money.of("5", "EUR");

        assertThatThrownBy(() -> plan(BigDecimal.ZERO, noFee, euroRates)).isInstanceOf(InvalidBillingDataException.class);
        assertThatThrownBy(() -> plan(BigDecimal.ZERO, euroFee, Map.of())).isInstanceOf(InvalidBillingDataException.class);
    }

    @Test
    void rejectsNegativePricesAndAllowances() {
        Money negativePrice = Money.of("-1", "USD");
        Money price = Money.of("1", "USD");
        Money negativeFee = Money.of("-5", "USD");

        assertThatThrownBy(() -> new ChannelRate(negativePrice, 0)).isInstanceOf(InvalidBillingDataException.class);
        assertThatThrownBy(() -> new ChannelRate(price, -1)).isInstanceOf(InvalidBillingDataException.class);
        assertThatThrownBy(() -> plan(BigDecimal.ZERO, negativeFee, Map.of())).isInstanceOf(InvalidBillingDataException.class);
    }

    @Test
    void ratesCannotBeChangedAfterConstruction() {
        Plan plan = plan(BigDecimal.ZERO, Money.zero("USD"), Map.of(Channel.SMS, ChannelRate.free("USD")));

        Map<Channel, ChannelRate> rates = plan.rates();
        ChannelRate free = ChannelRate.free("USD");

        assertThatThrownBy(() -> rates.put(Channel.PUSH, free)).isInstanceOf(UnsupportedOperationException.class);
    }
}
