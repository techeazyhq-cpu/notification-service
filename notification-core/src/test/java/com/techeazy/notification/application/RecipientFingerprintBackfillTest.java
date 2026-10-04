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

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Messages accepted before fingerprints existed get one, so a report finds them too (ADR-035). */
@Testcontainers(disabledWithoutDocker = true)
class RecipientFingerprintBackfillTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcClient jdbc;
    private static JdbcTemplate template;
    private static final RecipientFingerprints FINGERPRINTS = new RecipientFingerprints("a-data-key");

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
        template = new JdbcTemplate(dataSource);
    }

    @Test
    void earlierMessagesAreFingerprintedInBatchesAndErasedOnesAreLeftAlone() {
        UUID client = UUID.randomUUID();
        jdbc.sql("INSERT INTO client (id, name, api_key_hash, api_key_prefix, status, allowed_channels, created_at, updated_at) "
                        + "VALUES (:id, :name, :hash, 'ntf_t', 'ACTIVE', 'SMS', now(), now())")
                .param("id", client).param("name", "c-" + client).param("hash", client.toString().repeat(2).substring(0, 64)).update();
        UUID request = UUID.randomUUID();
        jdbc.sql("INSERT INTO notification_request (id, client_id, kind, channel, body, total, created_at) "
                + "VALUES (:id, :client, 'BULK', 'SMS', 'x', 5, now())").param("id", request).param("client", client).update();
        UUID first = message(request, client, "+1 (415) 555-0101", null);
        UUID second = message(request, client, "+14155550102", null);
        UUID third = message(request, client, "+14155550103", null);
        UUID erased = message(request, client, "[erased]", Instant.now());
        UUID already = message(request, client, "+14155550104", null);
        jdbc.sql("UPDATE notification_message SET recipient_fingerprint = 'kept' WHERE id = :id").param("id", already).update();

        int done = new RecipientFingerprintBackfill(jdbc, template, FINGERPRINTS, 2, 10).run();

        assertThat(done).isEqualTo(3);
        assertThat(fingerprintOf(first)).isEqualTo(FINGERPRINTS.of("+14155550101"));
        assertThat(fingerprintOf(second)).isEqualTo(FINGERPRINTS.of("+14155550102"));
        assertThat(fingerprintOf(third)).isEqualTo(FINGERPRINTS.of("+14155550103"));
        assertThat(fingerprintOf(erased)).isNull();
        assertThat(fingerprintOf(already)).isEqualTo("kept");
        assertThat(new RecipientFingerprintBackfill(jdbc, template, FINGERPRINTS, 2, 10).run()).isZero();
    }

    private static UUID message(UUID request, UUID client, String recipient, Instant erasedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO notification_message (id, request_id, client_id, channel, recipient, status, attempts, "
                        + "created_at, updated_at, erased_at) VALUES (:id, :request, :client, 'SMS', :recipient, 'SENT', 1, "
                        + "now(), now(), :erased)")
                .param("id", id).param("request", request).param("client", client).param("recipient", recipient)
                .param("erased", erasedAt == null ? null : Timestamp.from(erasedAt)).update();
        return id;
    }

    private static String fingerprintOf(UUID message) {
        return jdbc.sql("SELECT recipient_fingerprint FROM notification_message WHERE id = :id").param("id", message)
                .query(String.class).optional().orElse(null);
    }
}
