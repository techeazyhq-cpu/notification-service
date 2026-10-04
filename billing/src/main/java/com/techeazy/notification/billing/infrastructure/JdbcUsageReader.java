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

import com.techeazy.notification.billing.application.port.UsageReader;
import com.techeazy.notification.billing.domain.SentCount;
import com.techeazy.notification.domain.Channel;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Counts messages with status SENT by the time they were sent, one-time passwords apart (ADR-034). A partial index on
 * exactly that, which also carries the category, answers it without reading the table.
 */
class JdbcUsageReader implements UsageReader {

    static final String SENT_BY_CHANNEL = """
            SELECT channel,
                   count(*) FILTER (WHERE category <> 'OTP') AS ordinary,
                   count(*) FILTER (WHERE category = 'OTP') AS otp
            FROM notification_message
            WHERE client_id = :client AND status = 'SENT' AND sent_at >= :from AND sent_at < :to
            GROUP BY channel""";

    static final String IN_FLIGHT_BY_CHANNEL = """
            SELECT channel,
                   count(*) FILTER (WHERE category <> 'OTP') AS ordinary,
                   count(*) FILTER (WHERE category = 'OTP') AS otp
            FROM notification_message
            WHERE client_id = :client AND status IN ('PENDING', 'QUEUED', 'PROCESSING', 'RETRYING')
            GROUP BY channel""";

    private final JdbcClient jdbc;

    JdbcUsageReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Channel, SentCount> sentByChannel(UUID clientId, Instant fromInclusive, Instant toExclusive) {
        Map<Channel, SentCount> counts = new EnumMap<>(Channel.class);
        jdbc.sql(SENT_BY_CHANNEL)
                .param("client", clientId).param("from", Timestamp.from(fromInclusive)).param("to", Timestamp.from(toExclusive))
                .query((rs, n) -> Map.entry(Channel.valueOf(rs.getString("channel")),
                        new SentCount(rs.getLong("ordinary"), rs.getLong("otp"))))
                .list()
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }

    @Override
    public Map<Channel, SentCount> inFlightByChannel(UUID clientId) {
        Map<Channel, SentCount> counts = new EnumMap<>(Channel.class);
        jdbc.sql(IN_FLIGHT_BY_CHANNEL)
                .param("client", clientId)
                .query((rs, n) -> Map.entry(Channel.valueOf(rs.getString("channel")),
                        new SentCount(rs.getLong("ordinary"), rs.getLong("otp"))))
                .list()
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }
}
