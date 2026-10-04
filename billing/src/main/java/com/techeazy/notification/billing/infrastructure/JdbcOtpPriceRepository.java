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

package com.techeazy.notification.billing.infrastructure;

import com.techeazy.notification.billing.application.port.OtpPriceRepository;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.billing.domain.OtpPrices;
import com.techeazy.notification.domain.Channel;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** One row per tenant and channel with an OTP price of its own (ADR-034). */
class JdbcOtpPriceRepository implements OtpPriceRepository {

    private static final String CLIENT = "client";

    private final JdbcClient jdbc;

    JdbcOtpPriceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OtpPrices findByClientId(UUID clientId) {
        Map<Channel, Money> prices = new EnumMap<>(Channel.class);
        jdbc.sql("SELECT channel, unit_price, currency FROM billing_account_otp_price WHERE client_id = :client")
                .param(CLIENT, clientId)
                .query((rs, n) -> Map.entry(Channel.valueOf(rs.getString("channel")),
                        new Money(rs.getBigDecimal("unit_price"), rs.getString("currency"))))
                .list()
                .forEach(entry -> prices.put(entry.getKey(), entry.getValue()));
        return new OtpPrices(prices);
    }

    /** Deletes and inserts in the caller's transaction, so readers see the old prices or the new ones, never a mix. */
    @Override
    public void replace(UUID clientId, OtpPrices prices) {
        jdbc.sql("DELETE FROM billing_account_otp_price WHERE client_id = :client").param(CLIENT, clientId).update();
        prices.prices().forEach((channel, price) -> jdbc.sql("""
                INSERT INTO billing_account_otp_price (client_id, channel, unit_price, currency, updated_at)
                VALUES (:client, :channel, :price, :currency, now())""")
                .param(CLIENT, clientId).param("channel", channel.name())
                .param("price", price.amount()).param("currency", price.currency())
                .update());
    }
}
