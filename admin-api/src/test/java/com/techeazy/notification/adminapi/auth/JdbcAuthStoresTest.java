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

package com.techeazy.notification.adminapi.auth;

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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Adapter tests against a real PostgreSQL with the real Liquibase changelog; skipped automatically without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class JdbcAuthStoresTest {

    private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcAdminUserStore users;
    private static JdbcSessionStore sessions;

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
        JdbcClient jdbc = JdbcClient.create(dataSource);
        users = new JdbcAdminUserStore(jdbc);
        sessions = new JdbcSessionStore(jdbc);
    }

    private static AdminUser newUser(String name) {
        AdminUser user = new AdminUser(UUID.randomUUID(), name, "{bcrypt}hash", null, null, false, 0, null);
        users.insert(user, NOW);
        return user;
    }

    @Test
    void usersAreStoredFoundAndTheirPasswordAndTotpUpdated() {
        AdminUser user = newUser("stored-" + UUID.randomUUID());

        assertThat(users.findByUsername(user.username())).get().extracting(AdminUser::id).isEqualTo(user.id());
        assertThat(users.findById(user.id())).isPresent();
        assertThat(users.findByUsername("nobody")).isEmpty();

        users.updatePassword(user.id(), "{bcrypt}new", NOW);
        users.saveTotp(user.id(), "encrypted", true);

        AdminUser stored = users.findById(user.id()).orElseThrow();
        assertThat(stored.passwordHash()).isEqualTo("{bcrypt}new");
        assertThat(stored.passwordChangedAt()).isEqualTo(NOW);
        assertThat(stored.totpEnabled()).isTrue();
        assertThat(stored.totpSecret()).isEqualTo("encrypted");
    }

    @Test
    void theTotpStepOnlyMovesForwardSoACodeIsUsedOnce() {
        AdminUser user = newUser("step-" + UUID.randomUUID());

        assertThat(users.advanceTotpStep(user.id(), 100)).isTrue();
        assertThat(users.advanceTotpStep(user.id(), 100)).isFalse();
        assertThat(users.advanceTotpStep(user.id(), 99)).isFalse();
        assertThat(users.advanceTotpStep(user.id(), 101)).isTrue();
    }

    @Test
    void concurrentUseOfTheSameCodeSucceedsForExactlyOneCaller() throws Exception {
        AdminUser user = newUser("race-" + UUID.randomUUID());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> attempts = java.util.stream.IntStream.range(0, 16)
                    .<Callable<Boolean>>mapToObj(i -> () -> users.advanceTotpStep(user.id(), 500)).toList();
            long winners = 0;
            for (Future<Boolean> f : pool.invokeAll(attempts)) {
                winners += f.get() ? 1 : 0;
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failuresLockTheAccountAtTheLimitAndClearingUnlocksIt() {
        AdminUser user = newUser("lock-" + UUID.randomUUID());
        Instant until = NOW.plusSeconds(900);

        users.recordFailure(user.id(), 3, until);
        users.recordFailure(user.id(), 3, until);
        assertThat(users.findById(user.id()).orElseThrow().lockedUntil()).isNull();

        users.recordFailure(user.id(), 3, until);
        assertThat(users.findById(user.id()).orElseThrow().lockedAt(NOW)).isTrue();

        users.clearFailures(user.id());
        assertThat(users.findById(user.id()).orElseThrow().lockedUntil()).isNull();
    }

    @Test
    void recoveryCodesWorkOnceAndReplacingThemInvalidatesTheOldOnes() {
        AdminUser user = newUser("recovery-" + UUID.randomUUID());
        users.replaceRecoveryCodes(user.id(), List.of("a", "b", "c"));

        assertThat(users.remainingRecoveryCodes(user.id())).isEqualTo(3);
        assertThat(users.consumeRecoveryCode(user.id(), "a", NOW)).isTrue();
        assertThat(users.consumeRecoveryCode(user.id(), "a", NOW)).isFalse();
        assertThat(users.consumeRecoveryCode(user.id(), "zzz", NOW)).isFalse();
        assertThat(users.remainingRecoveryCodes(user.id())).isEqualTo(2);

        users.replaceRecoveryCodes(user.id(), List.of("x"));

        assertThat(users.consumeRecoveryCode(user.id(), "b", NOW)).isFalse();
        assertThat(users.remainingRecoveryCodes(user.id())).isEqualTo(1);
    }

    @Test
    void sessionsAreStoredTouchedDeletedAndPurged() {
        AdminUser user = newUser("session-" + UUID.randomUUID());
        sessions.create("hash-1", user.id(), NOW, NOW.plusSeconds(60));
        sessions.create("hash-2", user.id(), NOW, NOW.plusSeconds(60));
        sessions.create("hash-old", user.id(), NOW.minusSeconds(600), NOW.minusSeconds(1));

        assertThat(sessions.find("hash-1")).get().extracting(SessionStore.Session::username).isEqualTo(user.username());

        sessions.touch("hash-1", NOW.plusSeconds(30), NOW.plusSeconds(1800));
        assertThat(sessions.find("hash-1").orElseThrow().expiresAt()).isEqualTo(NOW.plusSeconds(1800));

        assertThat(sessions.purgeExpired(NOW)).isEqualTo(1);
        assertThat(sessions.find("hash-old")).isEmpty();

        sessions.deleteAllExcept(user.id(), "hash-1");
        assertThat(sessions.find("hash-2")).isEmpty();
        assertThat(sessions.find("hash-1")).isPresent();

        sessions.delete("hash-1");
        assertThat(sessions.find("hash-1")).isEmpty();
    }

    @Test
    void deletingAnAdministratorRemovesTheirSessionsAndRecoveryCodes() {
        AdminUser user = newUser("cascade-" + UUID.randomUUID());
        sessions.create("cascade-hash", user.id(), NOW, NOW.plusSeconds(60));
        users.replaceRecoveryCodes(user.id(), List.of("k"));

        JdbcClient.create(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()))
                .sql("DELETE FROM admin_user WHERE id = :id").param("id", user.id()).update();

        assertThat(sessions.find("cascade-hash")).isEmpty();
        assertThat(users.remainingRecoveryCodes(user.id())).isZero();
    }
}
