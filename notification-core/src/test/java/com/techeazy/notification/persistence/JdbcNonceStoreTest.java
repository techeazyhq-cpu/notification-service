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
package com.techeazy.notification.persistence;

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
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs against a real PostgreSQL migrated by the real changelog; skipped automatically without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class JdbcNonceStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(5);

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcClient jdbc;
    private static JdbcNonceStore nonces;

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
        nonces = new JdbcNonceStore(jdbc);
    }

    @Test
    void aNonceIsAcceptedOnceAndRefusedWhileItsUseIsLive() {
        UUID client = UUID.randomUUID();

        assertThat(nonces.claim(client, "nonce-once-0000001", NOW, NOW.plus(WINDOW))).isTrue();
        assertThat(nonces.claim(client, "nonce-once-0000001", NOW.plusSeconds(60), NOW.plus(WINDOW))).isFalse();
    }

    @Test
    void aReplayAtTheVeryEndOfTheWindowIsStillRefused() {
        UUID client = UUID.randomUUID();
        nonces.claim(client, "nonce-boundary-001", NOW, NOW.plus(WINDOW));
        Instant lastAcceptedMoment = NOW.plus(WINDOW);

        assertThat(nonces.claim(client, "nonce-boundary-001", lastAcceptedMoment, lastAcceptedMoment.plus(WINDOW))).isFalse();
        assertThat(nonces.purgeExpired(lastAcceptedMoment, 100)).isZero();
    }

    @Test
    void theSameNonceFromAnotherClientIsUnrelated() {
        String nonce = "shared-nonce-00001";

        assertThat(nonces.claim(UUID.randomUUID(), nonce, NOW, NOW.plus(WINDOW))).isTrue();
        assertThat(nonces.claim(UUID.randomUUID(), nonce, NOW, NOW.plus(WINDOW))).isTrue();
    }

    @Test
    void anExpiredUseThatWasNotPurgedYetDoesNotBlockTheNonce() {
        UUID client = UUID.randomUUID();
        nonces.claim(client, "nonce-expired-0001", NOW, NOW.plus(WINDOW));

        Instant later = NOW.plus(WINDOW).plusSeconds(1);

        assertThat(nonces.claim(client, "nonce-expired-0001", later, later.plus(WINDOW))).isTrue();
        assertThat(nonces.claim(client, "nonce-expired-0001", later, later.plus(WINDOW))).isFalse();
    }

    @Test
    void concurrentCopiesOfOneRequestAreAcceptedExactlyOnce() throws Exception {
        UUID client = UUID.randomUUID();
        Callable<Boolean> claim = () -> nonces.claim(client, "nonce-raced-000001", NOW, NOW.plus(WINDOW));

        long accepted;
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            accepted = pool.invokeAll(IntStream.range(0, 16).mapToObj(i -> claim).toList()).stream()
                    .map(JdbcNonceStoreTest::result).filter(Boolean::booleanValue).count();
        }

        assertThat(accepted).isEqualTo(1);
    }

    @Test
    void purgingRemovesOnlyExpiredNoncesAndAtMostTheLimit() {
        UUID client = UUID.randomUUID();
        Instant past = NOW.minus(Duration.ofDays(30));
        for (int i = 0; i < 3; i++) {
            nonces.claim(client, "nonce-purge-old-0" + i, past, past.plus(WINDOW));
        }
        nonces.claim(client, "nonce-purge-live-01", NOW, NOW.plus(WINDOW));

        int first = nonces.purgeExpired(past.plus(WINDOW).plusSeconds(1), 2);
        int rest = nonces.purgeExpired(past.plus(WINDOW).plusSeconds(1), 100);

        assertThat(first).isEqualTo(2);
        assertThat(rest).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM api_request_nonce WHERE client_id = :client").param("client", client)
                .query(Long.class).single()).isEqualTo(1);
    }

    private static boolean result(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
