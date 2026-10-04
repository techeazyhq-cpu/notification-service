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

import com.techeazy.notification.application.PersonalData;
import com.techeazy.notification.application.RecipientFingerprints;
import com.techeazy.notification.error.ErrorCode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.Writer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Everything one tenant sent to one recipient, as a CSV of timestamped events, for answering a complaint to a data
 * protection authority (ADR-035).
 *
 * <p>Messages are found by the recipient's fingerprint, so they are found even after the address was erased. Each
 * message starts with its acceptance, followed by its event log. A message from before the event log existed has no
 * events; its outcome is then taken from the message record itself and marked {@code RECORD}.
 *
 * <p>Only purpose metadata is reported: what the message was for (category, template, the tenant's reference) and how
 * it went. Never its text, so no one-time password code can leak through the report.
 */
@Service
public class RecipientActivityReport {

    private static final String ERROR_CODE_COLUMN = "error_code";
    private static final String PROVIDER_MESSAGE_ID_COLUMN = "provider_message_id";

    static final List<String> HEADER = List.of("message_id", "request_id", "client_reference", "channel", "category",
            "template", "sender", "recipient", "current_status", "event", "occurred_at", "attempt", ERROR_CODE_COLUMN,
            "error_id", PROVIDER_MESSAGE_ID_COLUMN, "detail", "source");

    private static final Logger log = LoggerFactory.getLogger(RecipientActivityReport.class);
    private static final String SOURCE_LOG = "LOG";
    private static final String SOURCE_RECORD = "RECORD";

    /** What was found, for the response and the log; never the address. */
    public record Summary(int messages, int rows) {}

    private record Message(UUID id, UUID requestId, String clientReference, String channel, String category,
                           String template, String sender, String recipient, String status, String requestKind,
                           Instant acceptedAt, Instant sentAt, Instant updatedAt, int attempts, String errorCode,
                           String providerMessageId) {}

    private record Event(String type, Instant occurredAt, Integer attempt, String errorCode, String providerMessageId,
                         String detail) {}

    private final JdbcClient jdbc;
    private final RecipientFingerprints fingerprints;

    public RecipientActivityReport(JdbcClient jdbc, RecipientFingerprints fingerprints) {
        this.jdbc = jdbc;
        this.fingerprints = fingerprints;
    }

    /**
     * Writes the report of {@code recipient} for {@code clientId}, for messages accepted from {@code from} to
     * {@code to} inclusive (UTC dates); either may be null for no bound.
     */
    @Transactional(readOnly = true)
    public Summary write(UUID clientId, String recipient, LocalDate from, LocalDate to, Writer out) throws IOException {
        String fingerprint = fingerprints.of(recipient);
        Instant start = from == null ? Instant.EPOCH : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = to == null ? Instant.now().plusSeconds(1) : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<Message> messages = jdbc.sql("""
                SELECT m.id, m.request_id, r.client_reference, m.channel, m.category,
                       COALESCE(r.template_name, t.name) AS template, r.sender_email, m.recipient, m.status, r.kind,
                       m.created_at, m.sent_at, m.updated_at, m.attempts, m.error_code, m.provider_message_id
                FROM notification_message m
                JOIN notification_request r ON r.id = m.request_id
                LEFT JOIN template t ON t.id = r.template_id
                WHERE m.client_id = :client AND m.recipient_fingerprint = :fingerprint
                  AND m.created_at >= :start AND m.created_at < :end
                ORDER BY m.created_at, m.id""")
                .param("client", clientId).param("fingerprint", fingerprint)
                .param("start", Timestamp.from(start)).param("end", Timestamp.from(end))
                .query(RecipientActivityReport::message).list();
        Map<UUID, List<Event>> events = eventsOf(clientId, fingerprint, start, end);
        int rows = 0;
        try (CSVPrinter csv = new CSVPrinter(out, CSVFormat.DEFAULT)) {
            csv.printRecord(HEADER);
            for (Message m : messages) {
                csv.printRecord(row(m, new Event("ACCEPTED", m.acceptedAt(), null, null, null,
                        "Accepted in a " + m.requestKind().toLowerCase(Locale.ROOT) + " request"), SOURCE_LOG));
                rows++;
                List<Event> logged = events.getOrDefault(m.id(), List.of());
                for (Event e : logged.isEmpty() ? fromRecord(m) : logged) {
                    csv.printRecord(row(m, e, logged.isEmpty() ? SOURCE_RECORD : SOURCE_LOG));
                    rows++;
                }
            }
        }
        if (log.isInfoEnabled()) {
            log.info("Recipient activity report for client {}: recipient fingerprint {}…, {} message(s), {} row(s)",
                    clientId, fingerprint.substring(0, 12), messages.size(), rows);
        }
        return new Summary(messages.size(), rows);
    }

    private Map<UUID, List<Event>> eventsOf(UUID clientId, String fingerprint, Instant start, Instant end) {
        Map<UUID, List<Event>> byMessage = new HashMap<>();
        jdbc.sql("""
                SELECT e.message_id, e.event, e.occurred_at, e.attempt, e.error_code, e.provider_message_id, e.detail
                FROM message_event e
                JOIN notification_message m ON m.id = e.message_id
                WHERE m.client_id = :client AND m.recipient_fingerprint = :fingerprint
                  AND m.created_at >= :start AND m.created_at < :end
                ORDER BY e.occurred_at, e.id""")
                .param("client", clientId).param("fingerprint", fingerprint)
                .param("start", Timestamp.from(start)).param("end", Timestamp.from(end))
                .query((rs, n) -> Map.entry(rs.getObject("message_id", UUID.class), new Event(rs.getString("event"),
                        rs.getTimestamp("occurred_at").toInstant(), (Integer) rs.getObject("attempt"),
                        rs.getString(ERROR_CODE_COLUMN), rs.getString(PROVIDER_MESSAGE_ID_COLUMN), rs.getString("detail"))))
                .list()
                .forEach(entry -> byMessage.computeIfAbsent(entry.getKey(), id -> new ArrayList<>()).add(entry.getValue()));
        return byMessage;
    }

    /** The outcome of a message from before the event log, as far as its record still tells it. */
    private static List<Event> fromRecord(Message m) {
        if ("SENT".equals(m.status()) && m.sentAt() != null) {
            return List.of(new Event("SENT", m.sentAt(), m.attempts(), null, m.providerMessageId(), null));
        }
        if ("FAILED".equals(m.status())) {
            return List.of(new Event("FAILED", m.updatedAt(), m.attempts(), m.errorCode(), null, null));
        }
        return List.of();
    }

    private static List<String> row(Message m, Event e, String source) {
        return List.of(m.id().toString(), m.requestId().toString(), MessageCsv.neutralize(m.clientReference()),
                m.channel(), m.category(), MessageCsv.neutralize(m.template()), MessageCsv.neutralize(m.sender()),
                m.recipient(), m.status(), e.type(), e.occurredAt().toString(),
                e.attempt() == null ? "" : e.attempt().toString(), e.errorCode() == null ? "" : e.errorCode(),
                e.errorCode() == null ? "" : ErrorCode.findByCode(e.errorCode()).map(ErrorCode::errorId).orElse(""),
                MessageCsv.neutralize(e.providerMessageId()), MessageCsv.neutralize(e.detail()), source);
    }

    private static Message message(ResultSet rs, int row) throws SQLException {
        String recipient = rs.getString("recipient");
        Timestamp sentAt = rs.getTimestamp("sent_at");
        return new Message(rs.getObject("id", UUID.class), rs.getObject("request_id", UUID.class),
                rs.getString("client_reference"), rs.getString("channel"), rs.getString("category"),
                rs.getString("template"), rs.getString("sender_email"),
                recipient == null ? PersonalData.ERASED : recipient, rs.getString("status"), rs.getString("kind"),
                rs.getTimestamp("created_at").toInstant(), sentAt == null ? null : sentAt.toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getInt("attempts"), rs.getString(ERROR_CODE_COLUMN),
                rs.getString(PROVIDER_MESSAGE_ID_COLUMN));
    }
}
