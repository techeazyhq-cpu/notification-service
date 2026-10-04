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

import com.techeazy.notification.application.RecipientFingerprints;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The report a tenant hands to a data protection authority, against a real PostgreSQL with the real changelog
 * (ADR-035); skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class RecipientActivityReportTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final RecipientFingerprints FINGERPRINTS = new RecipientFingerprints("a-data-key");
    private static JdbcClient jdbc;
    private static RecipientActivityReport report;

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
        report = new RecipientActivityReport(jdbc, FINGERPRINTS);
    }

    @Test
    void everyMessageToTheRecipientIsListedEventByEventEvenAfterErasure() throws Exception {
        UUID client = client();
        UUID request = request(client, "SINGLE", "order-shipped", "order-A-1", "orders@acme.test");
        UUID message = message(request, client, "ann@example.com", "SENT", "2026-09-01T10:00:00Z");
        event(message, client, "ATTEMPT_FAILED", "2026-09-01T10:00:01Z", 1, "PROVIDER_TEMPORARILY_FAILING", null, "Next attempt in 5 s");
        event(message, client, "SENT", "2026-09-01T10:00:06Z", 2, null, "prov-77", null);
        jdbc.sql("UPDATE notification_message SET recipient = '[erased]', erased_at = now() WHERE id = :id").param("id", message).update();
        event(message, client, "DATA_ERASED", "2026-12-01T00:00:00Z", null, null, null, "Erased on request");

        List<CSVRecord> rows = run(client, "  ANN@Example.com ", null, null);

        assertThat(rows).extracting(r -> r.get("event")).containsExactly("ACCEPTED", "ATTEMPT_FAILED", "SENT", "DATA_ERASED");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("message_id")).isEqualTo(message.toString());
            assertThat(r.get("request_id")).isEqualTo(request.toString());
            assertThat(r.get("client_reference")).isEqualTo("order-A-1");
            assertThat(r.get("template")).isEqualTo("order-shipped");
            assertThat(r.get("sender")).isEqualTo("orders@acme.test");
            assertThat(r.get("category")).isEqualTo("TRANSACTIONAL");
            assertThat(r.get("recipient")).isEqualTo("[erased]");
            assertThat(r.get("current_status")).isEqualTo("SENT");
            assertThat(r.get("source")).isEqualTo("LOG");
        });
        assertThat(rows.get(0).get("occurred_at")).isEqualTo("2026-09-01T10:00:00Z");
        assertThat(rows.get(0).get("detail")).isEqualTo("Accepted in a single request");
        assertThat(rows.get(1).get("error_code")).isEqualTo("PROVIDER_TEMPORARILY_FAILING");
        assertThat(rows.get(1).get("error_id")).isEqualTo("NS-6006");
        assertThat(rows.get(2).get("provider_message_id")).isEqualTo("prov-77");
        assertThat(rows.get(2).get("attempt")).isEqualTo("2");
    }

    @Test
    void anotherTenantsMessagesToTheSameRecipientAreNeverIncluded() throws Exception {
        UUID mine = client();
        UUID theirs = client();
        message(request(mine, "SINGLE", null, null, null), mine, "+14155550123", "SENT", "2026-09-02T10:00:00Z");
        message(request(theirs, "SINGLE", null, null, null), theirs, "+14155550123", "SENT", "2026-09-02T10:00:00Z");

        assertThat(run(mine, "+1 (415) 555-0123", null, null)).extracting(r -> r.get("event"))
                .containsExactly("ACCEPTED", "SENT");
    }

    @Test
    void aMessageFromBeforeTheEventLogTakesItsOutcomeFromItsRecord() throws Exception {
        UUID client = client();
        UUID request = request(client, "BULK", null, null, null);
        message(request, client, "bob@example.com", "FAILED", "2026-09-03T10:00:00Z");
        jdbc.sql("UPDATE notification_message SET error_code = 'DELIVERY_REJECTED', attempts = 1 WHERE client_id = :c")
                .param("c", client).update();

        List<CSVRecord> rows = run(client, "bob@example.com", null, null);

        assertThat(rows).extracting(r -> r.get("event") + "/" + r.get("source")).containsExactly("ACCEPTED/LOG", "FAILED/RECORD");
        assertThat(rows.get(1).get("error_id")).isEqualTo("NS-6001");
    }

    @Test
    void theDateRangeCountsAcceptanceDaysInclusively() throws Exception {
        UUID client = client();
        UUID request = request(client, "SINGLE", null, null, null);
        message(request, client, "cy@example.com", "SENT", "2026-08-31T23:59:59Z");
        UUID inRange = message(request, client, "cy@example.com", "SENT", "2026-09-01T00:00:00Z");
        message(request, client, "cy@example.com", "SENT", "2026-09-03T00:00:00Z");

        List<CSVRecord> rows = run(client, "cy@example.com", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"));

        assertThat(rows).extracting(r -> r.get("message_id")).containsOnly(inRange.toString());
    }

    @Test
    void textATenantOrProviderSuppliedCannotBecomeASpreadsheetFormula() throws Exception {
        UUID client = client();
        message(request(client, "SINGLE", null, "=HYPERLINK(\"http://x\")", null), client, "dee@example.com", "SENT",
                "2026-09-04T10:00:00Z");

        assertThat(run(client, "dee@example.com", null, null).get(0).get("client_reference")).startsWith("'=");
    }

    @Test
    void theReportCarriesPurposeButNeverTheMessageText() throws Exception {
        assertThat(RecipientActivityReport.HEADER).doesNotContain("body", "subject", "variables", "content")
                .contains("category", "template", "client_reference", "occurred_at", "event");
        UUID client = client();

        assertThat(run(client, "nobody@example.com", null, null)).isEmpty();
    }

    private static List<CSVRecord> run(UUID client, String recipient, LocalDate from, LocalDate to) throws Exception {
        StringWriter out = new StringWriter();
        report.write(client, recipient, from, to, out);
        return CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build()
                .parse(new StringReader(out.toString())).getRecords();
    }

    private static UUID client() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO client (id, name, api_key_hash, api_key_prefix, status, allowed_channels, created_at, updated_at) "
                        + "VALUES (:id, :name, :hash, 'ntf_t', 'ACTIVE', 'EMAIL,SMS', now(), now())")
                .param("id", id).param("name", "c-" + id).param("hash", id.toString().repeat(2).substring(0, 64)).update();
        return id;
    }

    private static UUID request(UUID client, String kind, String templateName, String reference, String sender) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO notification_request (id, client_id, kind, channel, body, total, created_at, template_name, "
                        + "client_reference, sender_email) VALUES (:id, :client, :kind, 'EMAIL', 'Hi', 1, now(), :template, "
                        + ":reference, :sender)")
                .param("id", id).param("client", client).param("kind", kind).param("template", templateName)
                .param("reference", reference).param("sender", sender).update();
        return id;
    }

    private static UUID message(UUID request, UUID client, String recipient, String status, String createdAt) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.parse(createdAt));
        jdbc.sql("INSERT INTO notification_message (id, request_id, client_id, channel, recipient, recipient_fingerprint, "
                        + "status, attempts, created_at, updated_at, sent_at) VALUES (:id, :request, :client, 'EMAIL', "
                        + ":recipient, :fingerprint, :status, 1, :at, :at, :sent)")
                .param("id", id).param("request", request).param("client", client).param("recipient", recipient)
                .param("fingerprint", FINGERPRINTS.of(recipient)).param("status", status).param("at", at)
                .param("sent", "SENT".equals(status) ? at : null).update();
        return id;
    }

    private static void event(UUID message, UUID client, String type, String at, Integer attempt, String errorCode,
                              String providerId, String detail) {
        jdbc.sql("INSERT INTO message_event (message_id, client_id, event, occurred_at, attempt, error_code, "
                        + "provider_message_id, detail) VALUES (:m, :c, :e, :at, :attempt, :code, :provider, :detail)")
                .param("m", message).param("c", client).param("e", type).param("at", Timestamp.from(Instant.parse(at)))
                .param("attempt", attempt).param("code", errorCode).param("provider", providerId).param("detail", detail)
                .update();
    }
}
