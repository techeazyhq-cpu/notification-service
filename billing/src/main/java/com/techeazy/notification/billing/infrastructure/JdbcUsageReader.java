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
import com.techeazy.notification.domain.Channel;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** Counts messages with status SENT by the time they were sent, served by a partial index on exactly that. */
class JdbcUsageReader implements UsageReader {

    private final JdbcClient jdbc;

    JdbcUsageReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Channel, Long> sentByChannel(UUID clientId, Instant fromInclusive, Instant toExclusive) {
        Map<Channel, Long> counts = new EnumMap<>(Channel.class);
        jdbc.sql("""
                SELECT channel, count(*) AS sent FROM notification_message
                WHERE client_id = :client AND status = 'SENT' AND sent_at >= :from AND sent_at < :to
                GROUP BY channel""")
                .param("client", clientId).param("from", Timestamp.from(fromInclusive)).param("to", Timestamp.from(toExclusive))
                .query((rs, n) -> Map.entry(Channel.valueOf(rs.getString("channel")), rs.getLong("sent")))
                .list()
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }
}
