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
