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

package com.techeazy.notification.dispatcher;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs against a real PostgreSQL migrated by the real changelog; skipped automatically without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class PreviousSigningSecretSweepJobTest {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcClient jdbc;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeAll
    static void migrate() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.yaml",
                    new DirectoryResourceAccessor(Path.of("../db-migration/src/main/resources")), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
        jdbc = JdbcClient.create(dataSource);
    }

    @Test
    void aPreviousSecretIsForgottenOnceItsGracePeriodHasEndedAndNotBefore() {
        UUID ended = client("enc:current-1", "enc:previous-1", NOW);
        UUID running = client("enc:current-2", "enc:previous-2", NOW.plusSeconds(1));
        UUID none = client("enc:current-3", null, null);

        new PreviousSigningSecretSweepJob(jdbc, meters, Clock.fixed(NOW, ZoneOffset.UTC)).sweep();

        assertThat(previous(ended)).isNull();
        assertThat(previous(running)).isEqualTo("enc:previous-2");
        assertThat(previous(none)).isNull();
        assertThat(current(ended)).isEqualTo("enc:current-1");
        assertThat(meters.counter("notification.signature.previous_secrets_forgotten").count()).isEqualTo(1);
    }

    private static UUID client(String current, String previous, Instant until) {
        UUID id = UUID.randomUUID();
        String hash = id.toString().replace("-", "");
        jdbc.sql("INSERT INTO client (id, name, api_key_hash, api_key_prefix, status, allowed_channels, signing_secret, "
                        + "previous_signing_secret, previous_signing_secret_expires_at, created_at, updated_at) "
                        + "VALUES (:id, :name, :hash, 'ntf_test', 'ACTIVE', 'SMS', :current, :previous, :until, :now, :now)")
                .param("id", id).param("name", "c-" + id).param("hash", hash + hash).param("current", current)
                .param("previous", previous).param("until", until == null ? null : Timestamp.from(until))
                .param("now", Timestamp.from(NOW)).update();
        return id;
    }

    private static String previous(UUID id) {
        return jdbc.sql("SELECT previous_signing_secret FROM client WHERE id = :id").param("id", id)
                .query(String.class).optional().orElse(null);
    }

    private static String current(UUID id) {
        return jdbc.sql("SELECT signing_secret FROM client WHERE id = :id").param("id", id).query(String.class).single();
    }
}
