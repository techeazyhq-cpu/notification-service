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

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * What one tenant pays per one-time password, per channel, instead of its plan's price for the channel (ADR-034).
 * A channel without a price here charges one-time passwords at the plan's price, so a tenant without any is billed
 * exactly as before. Every price is in the currency of the tenant's plan.
 */
public record OtpPrices(Map<Channel, Money> prices) {

    public static final OtpPrices NONE = new OtpPrices(Map.of());

    public OtpPrices {
        Map<Channel, Money> copy = new EnumMap<>(Channel.class);
        String currency = null;
        for (Map.Entry<Channel, Money> entry : prices.entrySet()) {
            Money price = entry.getValue();
            if (price.isNegative()) {
                throw new InvalidBillingDataException("An OTP price cannot be negative");
            }
            if (currency != null && !currency.equals(price.currency())) {
                throw new InvalidBillingDataException("Every OTP price must be in the same currency");
            }
            currency = price.currency();
            copy.put(entry.getKey(), price);
        }
        prices = Collections.unmodifiableMap(copy);
    }

    public Optional<Money> priceFor(Channel channel) {
        return Optional.ofNullable(prices.get(channel));
    }

    public boolean isEmpty() {
        return prices.isEmpty();
    }

    /** The currency of the prices; empty when there are none. */
    public Optional<String> currency() {
        return prices.values().stream().map(Money::currency).findFirst();
    }
}
