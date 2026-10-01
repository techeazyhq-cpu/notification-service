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
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real retention SQL against a real PostgreSQL with the real Liquibase changelog; skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class RetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcClient jdbc;

    @BeforeAll
    static void migrate() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.yaml",
                    new DirectoryResourceAccessor(Path.of("../db-migration/src/main/resources")), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
        jdbc = JdbcClient.create(dataSource);
    }

    private static RetentionService service(int personalDataDays, int deleteDays, int idempotencyDays, int batch) {
        RetentionProperties p = new RetentionProperties();
        p.setPersonalDataDays(personalDataDays);
        p.setDeleteDays(deleteDays);
        p.setIdempotencyDays(idempotencyDays);
        p.setBatchSize(batch);
        return new RetentionService(jdbc, p);
    }

    private static Timestamp ago(int days) {
        return Timestamp.from(NOW.minus(Duration.ofDays(days)));
    }

    private static UUID client() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO client (id, name, api_key_hash, api_key_prefix, status, allowed_channels, created_at, updated_at) "
                        + "VALUES (:id, :name, :hash, 'ntf_test', 'ACTIVE', 'EMAIL,SMS', :now, :now)")
                .param("id", id).param("name", "c-" + id).param("hash", id.toString().replace("-", "") + id.toString().replace("-", ""))
                .param("now", ago(0)).update();
        return id;
    }

    private static UUID request(UUID client, int ageDays, int total, String body, String idempotencyKey) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO notification_request (id, client_id, kind, channel, subject, body, total, idempotency_key, created_at) "
                        + "VALUES (:id, :client, 'SINGLE', 'SMS', 'subject', :body, :total, :key, :at)")
                .param("id", id).param("client", client).param("body", body).param("total", total).param("key", idempotencyKey)
                .param("at", ago(ageDays)).update();
        return id;
    }

    private static UUID message(UUID client, UUID request, String recipient, String status, int ageDays) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO notification_message (id, request_id, client_id, channel, recipient, variables, status, last_error, created_at, updated_at, sent_at) "
                        + "VALUES (:id, :request, :client, 'SMS', :recipient, '{\"name\":\"Ada\"}'::jsonb, :status, 'boom to ada', :at, :at, :sentAt)")
                .param("id", id).param("request", request).param("client", client).param("recipient", recipient)
                .param("status", status).param("at", ago(ageDays)).param("sentAt", "SENT".equals(status) ? ago(ageDays) : null).update();
        return id;
    }

    private static Map<String, Object> row(String table, UUID id) {
        return jdbc.sql("SELECT * FROM " + table + " WHERE id = :id").param("id", id).query().singleRow();
    }

    @Test
    void oldFinishedMessagesLosePersonalDataButKeepStatusAndBillingFields() {
        UUID client = client();
        UUID request = request(client, 100, 1, "Hi Ada, code 1234", null);
        UUID old = message(client, request, "+14155550101", "SENT", 100);

        RetentionService.Report report = service(90, 0, 0, 100).run(NOW);

        assertThat(report.messagesErased()).isGreaterThanOrEqualTo(1);
        Map<String, Object> row = row("notification_message", old);
        assertThat(row).containsEntry("recipient", "[erased]").containsEntry("last_error", null).containsEntry("status", "SENT")
                .extractingByKey("variables").hasToString("{}");
        assertThat(row).extractingByKey("sent_at").isNotNull();
        assertThat(row).extractingByKey("erased_at").isNotNull();
        assertThat(row("notification_request", request)).containsEntry("body", "[erased]");
        assertThat(row("notification_request", request)).containsEntry("subject", null);
    }

    @Test
    void recentMessagesAndMessagesStillInFlightAreNeverTouched() {
        UUID client = client();
        UUID request = request(client, 100, 3, "content", null);
        UUID recent = message(client, request, "+14155550102", "SENT", 5);
        UUID queued = message(client, request, "+14155550103", "QUEUED", 200);
        UUID retrying = message(client, request, "+14155550104", "RETRYING", 200);

        service(90, 180, 0, 100).run(NOW);

        assertThat(row("notification_message", recent)).containsEntry("recipient", "+14155550102");
        assertThat(row("notification_message", queued)).containsEntry("recipient", "+14155550103");
        assertThat(row("notification_message", retrying)).containsEntry("recipient", "+14155550104");
        assertThat(row("notification_request", request)).containsEntry("body", "content");
        assertThat(jdbc.sql("SELECT count(*) FROM notification_message WHERE id IN (:a, :b)").param("a", queued).param("b", retrying).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void veryOldFinishedMessagesAndTheirEmptiedRequestsAreDeleted() {
        UUID client = client();
        UUID request = request(client, 500, 1, "old", null);
        UUID message = message(client, request, "+14155550105", "FAILED", 500);
        UUID keptRequest = request(client, 500, 1, "kept because one message is still queued", null);
        message(client, keptRequest, "+14155550106", "QUEUED", 500);

        RetentionService.Report report = service(90, 400, 0, 100).run(NOW);

        assertThat(report.messagesDeleted()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM notification_message WHERE id = :id").param("id", message).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM notification_request WHERE id = :id").param("id", request).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM notification_request WHERE id = :id").param("id", keptRequest).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void idempotencyKeysAreClearedAfterTheirLifetimeSoTheyStopBlockingAndStopBeingStored() {
        UUID client = client();
        UUID old = request(client, 30, 1, "x", "order-1");
        UUID fresh = request(client, 1, 1, "x", "order-2");

        RetentionService.Report report = service(0, 0, 7, 100).run(NOW);

        assertThat(report.idempotencyKeysCleared()).isGreaterThanOrEqualTo(1);
        assertThat(row("notification_request", old)).containsEntry("idempotency_key", null);
        assertThat(row("notification_request", fresh)).containsEntry("idempotency_key", "order-2");
    }

    @Test
    void aStepWithZeroDaysIsSwitchedOffAndRunsBatchByBatch() {
        UUID client = client();
        UUID request = request(client, 200, 5, "x", null);
        List<UUID> ids = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> message(client, request, "+1415555020" + i, "SENT", 200)).toList();

        RetentionService.Report off = service(0, 0, 0, 2).run(NOW);
        assertThat(off).isEqualTo(new RetentionService.Report(0, 0, 0, 0, 0));
        assertThat(row("notification_message", ids.get(0))).containsEntry("recipient", "+14155550200");

        RetentionService.Report batched = service(90, 0, 0, 2).run(NOW);
        assertThat(batched.messagesErased()).isGreaterThanOrEqualTo(5);
        assertThat(ids).allSatisfy(id -> assertThat(row("notification_message", id)).containsEntry("recipient", "[erased]"));
    }

    @Test
    void erasingARecipientIsCaseInsensitiveScopedToTheClientAndReportsWhatIsStillInFlight() {
        UUID mine = client();
        UUID other = client();
        UUID request = request(mine, 1, 1, "Hi Ada", null);
        UUID finished = message(mine, request, "Ada@Example.com", "SENT", 1);
        UUID otherRequest = request(mine, 1, 2, "bulk template {{name}}", null);
        UUID inFlight = message(mine, otherRequest, "ada@example.com", "QUEUED", 0);
        UUID othersMessage = message(other, request(other, 1, 1, "Hi", null), "ada@example.com", "SENT", 1);

        RetentionService.Erasure result = service(90, 400, 7, 100).eraseRecipient(mine, "ADA@example.com", NOW);

        assertThat(result.erasedMessages()).isEqualTo(1);
        assertThat(result.inFlightMessages()).isEqualTo(1);
        assertThat(row("notification_message", finished)).containsEntry("recipient", "[erased]");
        assertThat(row("notification_message", inFlight)).containsEntry("recipient", "ada@example.com");
        assertThat(row("notification_message", othersMessage)).containsEntry("recipient", "ada@example.com");
        assertThat(row("notification_request", request)).containsEntry("body", "[erased]");
        assertThat(row("notification_request", otherRequest)).containsEntry("body", "bulk template {{name}}");
    }

    @Test
    void erasureWithoutAClientCoversEveryClientAndRepeatingItChangesNothing() {
        UUID a = client();
        UUID b = client();
        message(a, request(a, 1, 1, "x", null), "shared@example.com", "SENT", 1);
        message(b, request(b, 1, 1, "x", null), "shared@example.com", "FAILED", 1);
        RetentionService service = service(90, 400, 7, 100);

        assertThat(service.eraseRecipient(null, "shared@example.com", NOW).erasedMessages()).isEqualTo(2);
        assertThat(service.eraseRecipient(null, "shared@example.com", NOW).erasedMessages()).isZero();
    }

    @Test
    void thePlaceholderIsRecognisedSoErasedMessagesCanBeRefusedForRetry() {
        assertThat(PersonalData.isErased("[erased]")).isTrue();
        assertThat(PersonalData.isErased("+14155550123")).isFalse();
        assertThat(PersonalData.isErased(null)).isFalse();
    }
}
