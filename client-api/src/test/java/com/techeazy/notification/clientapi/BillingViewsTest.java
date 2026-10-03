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

import com.techeazy.notification.billing.domain.AccountStatus;
import com.techeazy.notification.billing.domain.BillingAccount;
import com.techeazy.notification.billing.domain.BillingMode;
import com.techeazy.notification.billing.domain.ChannelRate;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.billing.domain.OtpPrices;
import com.techeazy.notification.billing.domain.Plan;
import com.techeazy.notification.clientapi.BillingViews.RateView;
import com.techeazy.notification.domain.Channel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A tenant sees what it pays per one-time password next to its plan's prices (ADR-034). */
class BillingViewsTest {

    private final Plan plan = new Plan(UUID.randomUUID(), "standard", "EUR", Money.zero("EUR"), BigDecimal.ZERO,
            Map.of(Channel.SMS, new ChannelRate(Money.of("0.008", "EUR"), 1_000),
                    Channel.EMAIL, new ChannelRate(Money.of("0.001", "EUR"), 0)), true);
    private final BillingAccount account = new BillingAccount(UUID.randomUUID(), plan.id(), BillingMode.POSTPAID, null,
            Money.zero("EUR"), AccountStatus.ACTIVE, null);

    @Test
    void eachRateShowsTheTenantsOtpPriceWhereItHasOne() {
        OtpPrices otp = new OtpPrices(Map.of(Channel.SMS, Money.of("0.012", "EUR")));

        assertThat(BillingViews.account(account, plan, otp).rates()).containsExactly(
                new RateView(Channel.EMAIL, Money.of("0.001", "EUR").unitFormatted(), 0, null),
                new RateView(Channel.SMS, Money.of("0.008", "EUR").unitFormatted(), 1_000,
                        Money.of("0.012", "EUR").unitFormatted()));
    }

    @Test
    void aChannelPricedOnlyForOneTimePasswordsIsListedToo() {
        OtpPrices otp = new OtpPrices(Map.of(Channel.WHATSAPP, Money.of("0.02", "EUR")));

        assertThat(BillingViews.account(account, plan, otp).rates())
                .filteredOn(rate -> rate.channel() == Channel.WHATSAPP)
                .singleElement()
                .satisfies(rate -> {
                    assertThat(rate.unitPrice()).isEqualTo(Money.zero("EUR").unitFormatted());
                    assertThat(rate.otpUnitPrice()).isEqualTo(Money.of("0.02", "EUR").unitFormatted());
                });
    }
}
