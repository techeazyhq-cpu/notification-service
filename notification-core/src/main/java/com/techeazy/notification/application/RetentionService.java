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

package com.techeazy.notification.application;

import com.techeazy.notification.config.RetentionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Limits how long personal data lives, and erases it on request.
 *
 * <p>Retention works in steps so that billing and audit counts survive after the personal data is gone:
 * <ol>
 *   <li>after {@code personalDataDays}, a finished (SENT or FAILED) message loses its recipient, template variables
 *       and error text, and a request that has only finished messages loses its subject and body;</li>
 *   <li>after {@code deleteDays}, finished messages and the requests left without messages are deleted;</li>
 *   <li>after {@code idempotencyDays}, idempotency keys are cleared.</li>
 * </ol>
 * Messages that are still in flight are never touched. Every statement changes at most one batch of rows and is safe
 * to run twice or from two instances at once, because it only selects rows that are still due.
 */
@Service
public class RetentionService {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionService.class);
    private static final String FINISHED = "('SENT','FAILED')";
    private static final String ERASED = "erased";
    private static final String CUTOFF = "cutoff";
    private static final String BATCH = "batch";

    private static final String ERASE_FINISHED_MESSAGES = """
            UPDATE notification_message
            SET recipient = :erased, variables = '{}'::jsonb, last_error = NULL, erased_at = :now
            WHERE id IN (SELECT id FROM notification_message
                         WHERE erased_at IS NULL AND status IN %s AND updated_at < :cutoff
                         ORDER BY updated_at LIMIT :batch)
            """.formatted(FINISHED);

    private static final String ERASE_FINISHED_REQUESTS = """
            UPDATE notification_request SET subject = NULL, body = :erased, erased_at = :now
            WHERE id IN (SELECT r.id FROM notification_request r
                         WHERE r.erased_at IS NULL AND r.created_at < :cutoff
                           AND NOT EXISTS (SELECT 1 FROM notification_message m WHERE m.request_id = r.id AND m.status NOT IN %s)
                         ORDER BY r.created_at LIMIT :batch)
            """.formatted(FINISHED);

    private static final String DELETE_FINISHED_MESSAGES = """
            DELETE FROM notification_message
            WHERE id IN (SELECT id FROM notification_message WHERE status IN %s AND updated_at < :cutoff LIMIT :batch)
            """.formatted(FINISHED);

    private static final String DELETE_EMPTY_REQUESTS = """
            DELETE FROM notification_request
            WHERE id IN (SELECT r.id FROM notification_request r
                         WHERE r.created_at < :cutoff AND NOT EXISTS (SELECT 1 FROM notification_message m WHERE m.request_id = r.id)
                         LIMIT :batch)
            """;

    private static final String CLEAR_IDEMPOTENCY_KEYS = """
            UPDATE notification_request SET idempotency_key = NULL
            WHERE id IN (SELECT id FROM notification_request WHERE idempotency_key IS NOT NULL AND created_at < :cutoff LIMIT :batch)
            """;

    /** How many rows each step changed in one run. */
    public record Report(long messagesErased, long requestsErased, long messagesDeleted, long requestsDeleted, long idempotencyKeysCleared) {}

    /** Outcome of an erasure request: messages erased, and messages left because they are still being delivered. */
    public record Erasure(long erasedMessages, long inFlightMessages) {}

    private final JdbcClient jdbc;
    private final RetentionProperties properties;

    public RetentionService(JdbcClient jdbc, RetentionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public RetentionProperties policy() {
        return properties;
    }

    public Report run(Instant now) {
        long messagesErased = 0;
        long requestsErased = 0;
        long messagesDeleted = 0;
        long requestsDeleted = 0;
        if (properties.getPersonalDataDays() > 0) {
            Instant cutoff = now.minus(Duration.ofDays(properties.getPersonalDataDays()));
            messagesErased = eraseFinishedMessages(cutoff, now);
            requestsErased = eraseFinishedRequests(cutoff, now);
        }
        if (properties.getDeleteDays() > 0) {
            Instant cutoff = now.minus(Duration.ofDays(properties.getDeleteDays()));
            messagesDeleted = deleteFinishedMessages(cutoff);
            requestsDeleted = deleteEmptyRequests(cutoff);
        }
        long keys = properties.getIdempotencyDays() > 0
                ? clearIdempotencyKeys(now.minus(Duration.ofDays(properties.getIdempotencyDays()))) : 0;
        Report report = new Report(messagesErased, requestsErased, messagesDeleted, requestsDeleted, keys);
        LOG.info("Retention run: {}", report);
        return report;
    }

    /**
     * Erases the personal data of one recipient, for one client or for all clients ({@code clientId} null). Finished
     * messages are erased at once; a message still being delivered is left and counted, so the caller can repeat the
     * request once delivery has finished.
     */
    public Erasure eraseRecipient(UUID clientId, String recipient, Instant now) {
        Timestamp at = Timestamp.from(now);
        long erased = jdbc.sql("""
                UPDATE notification_message
                SET recipient = :erased, variables = '{}'::jsonb, last_error = NULL, erased_at = :now
                WHERE lower(recipient) = lower(:recipient) AND (CAST(:client AS uuid) IS NULL OR client_id = :client)
                  AND status IN %s AND erased_at IS NULL
                """.formatted(FINISHED)).param(ERASED, PersonalData.ERASED).param("now", at)
                .param("recipient", recipient).param("client", clientId).update();
        long inFlight = jdbc.sql("""
                SELECT count(*) FROM notification_message
                WHERE lower(recipient) = lower(:recipient) AND (CAST(:client AS uuid) IS NULL OR client_id = :client)
                  AND status NOT IN %s
                """.formatted(FINISHED)).param("recipient", recipient).param("client", clientId).query(Long.class).single();
        jdbc.sql("""
                UPDATE notification_request r SET subject = NULL, body = :erased, erased_at = :now
                WHERE r.erased_at IS NULL AND r.total = 1
                  AND EXISTS (SELECT 1 FROM notification_message m WHERE m.request_id = r.id AND m.recipient = :erased)
                  AND NOT EXISTS (SELECT 1 FROM notification_message m WHERE m.request_id = r.id AND m.status NOT IN %s)
                """.formatted(FINISHED)).param(ERASED, PersonalData.ERASED).param("now", at).update();
        LOG.info("Erasure request for client {}: {} message(s) erased, {} still in flight", clientId, erased, inFlight);
        return new Erasure(erased, inFlight);
    }

    private long eraseFinishedMessages(Instant cutoff, Instant now) {
        return batches(() -> jdbc.sql(ERASE_FINISHED_MESSAGES).param(ERASED, PersonalData.ERASED).param("now", Timestamp.from(now))
                .param(CUTOFF, Timestamp.from(cutoff)).param(BATCH, properties.getBatchSize()).update());
    }

    private long eraseFinishedRequests(Instant cutoff, Instant now) {
        return batches(() -> jdbc.sql(ERASE_FINISHED_REQUESTS).param(ERASED, PersonalData.ERASED).param("now", Timestamp.from(now))
                .param(CUTOFF, Timestamp.from(cutoff)).param(BATCH, properties.getBatchSize()).update());
    }

    private long deleteFinishedMessages(Instant cutoff) {
        return batches(() -> jdbc.sql(DELETE_FINISHED_MESSAGES).param(CUTOFF, Timestamp.from(cutoff)).param(BATCH, properties.getBatchSize()).update());
    }

    private long deleteEmptyRequests(Instant cutoff) {
        return batches(() -> jdbc.sql(DELETE_EMPTY_REQUESTS).param(CUTOFF, Timestamp.from(cutoff)).param(BATCH, properties.getBatchSize()).update());
    }

    private long clearIdempotencyKeys(Instant cutoff) {
        return batches(() -> jdbc.sql(CLEAR_IDEMPOTENCY_KEYS).param(CUTOFF, Timestamp.from(cutoff)).param(BATCH, properties.getBatchSize()).update());
    }

    private long batches(java.util.function.IntSupplier statement) {
        long total = 0;
        int changed;
        do {
            changed = statement.getAsInt();
            total += changed;
        } while (changed >= properties.getBatchSize());
        return total;
    }
}
