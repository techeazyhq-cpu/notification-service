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

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A price list: per-channel rates, an optional flat monthly platform fee and a tax rate, all in one currency.
 * A channel without a rate is not charged.
 */
public record Plan(UUID id, String name, String currency, Money platformFee, BigDecimal taxRate,
                   Map<Channel, ChannelRate> rates, boolean active) {

    public Plan {
        if (name == null || name.isBlank()) {
            throw new InvalidBillingDataException("Plan name is required");
        }
        currency = currency.toUpperCase(Locale.ROOT);
        taxRate = TaxRate.normalize(taxRate);
        if (platformFee.isNegative() || !platformFee.currency().equals(currency)) {
            throw new InvalidBillingDataException("Platform fee must be non-negative and in the plan currency");
        }
        Map<Channel, ChannelRate> copy = new EnumMap<>(Channel.class);
        for (Map.Entry<Channel, ChannelRate> entry : rates.entrySet()) {
            requireCurrency(entry.getValue(), currency);
            copy.put(entry.getKey(), entry.getValue());
        }
        rates = Collections.unmodifiableMap(copy);
    }

    public ChannelRate rateFor(Channel channel) {
        return rates.getOrDefault(channel, ChannelRate.free(currency));
    }

    private static void requireCurrency(ChannelRate rate, String currency) {
        if (!rate.unitPrice().currency().equals(currency)) {
            throw new InvalidBillingDataException("Every rate must be in the plan currency " + currency);
        }
    }
}
