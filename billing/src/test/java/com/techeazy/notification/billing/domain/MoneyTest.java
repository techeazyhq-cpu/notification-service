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

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void normalisesScaleAndCurrency() {
        Money money = new Money(new BigDecimal("1.5"), "usd");

        assertThat(money.amount()).isEqualByComparingTo("1.500000");
        assertThat(money.amount().scale()).isEqualTo(6);
        assertThat(money.currency()).isEqualTo("USD");
    }

    @Test
    void addsSubtractsAndMultiplies() {
        Money price = Money.of("0.0035", "USD");

        assertThat(price.times(1000)).isEqualTo(Money.of("3.5", "USD"));
        assertThat(price.plus(Money.of("1", "USD"))).isEqualTo(Money.of("1.0035", "USD"));
        assertThat(price.minus(Money.of("0.0005", "USD"))).isEqualTo(Money.of("0.003", "USD"));
        assertThat(price.negate().isNegative()).isTrue();
    }

    @Test
    void roundsHalfUpToTwoDecimalsForInvoices() {
        assertThat(Money.of("0.125", "USD").rounded()).isEqualTo(Money.of("0.13", "USD"));
        assertThat(Money.of("0.124999", "USD").rounded()).isEqualTo(Money.of("0.12", "USD"));
        assertThat(Money.of("10.005", "USD").rounded().display()).isEqualTo("10.01 USD");
    }

    @Test
    void appliesPercentagesAndComparesAmounts() {
        assertThat(Money.of("200", "EUR").percent(new BigDecimal("0.18"))).isEqualTo(Money.of("36", "EUR"));
        assertThat(Money.of("1", "EUR")).isLessThan(Money.of("2", "EUR"));
        assertThat(Money.of("5", "EUR").min(Money.of("3", "EUR"))).isEqualTo(Money.of("3", "EUR"));
        assertThat(Money.zero("EUR").isZero()).isTrue();
        assertThat(Money.of("0.000001", "EUR").isPositive()).isTrue();
    }

    @Test
    void refusesToMixCurrencies() {
        Money dollars = Money.of("1", "USD");
        Money euros = Money.of("1", "EUR");

        assertThatThrownBy(() -> dollars.plus(euros)).isInstanceOf(InvalidBillingDataException.class);
        assertThatThrownBy(() -> dollars.compareTo(euros)).isInstanceOf(InvalidBillingDataException.class);
    }

    @Test
    void rejectsMalformedCurrencyCodes() {
        assertThatThrownBy(() -> Money.of("1", "US")).isInstanceOf(InvalidBillingDataException.class);
        assertThatThrownBy(() -> Money.of("1", null)).isInstanceOf(InvalidBillingDataException.class);
    }
}
