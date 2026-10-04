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
package com.techeazy.notification.adminapi.audit;

import com.techeazy.notification.adminapi.auth.AdminRole;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against a real PostgreSQL migrated by the real changelog; skipped automatically without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class JdbcAuditLogTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcClient jdbc;
    private static JdbcAuditLog auditLog;

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
        auditLog = new JdbcAuditLog(jdbc);
    }

    private static String uniqueActor(String name) {
        return name + "-" + UUID.randomUUID();
    }

    private static AuditEvent event(String actor, Instant occurredAt, AuditOutcome outcome, int statusCode) {
        return new AuditEvent(UUID.randomUUID(), occurredAt, actor, AdminRole.ADMIN, "POST",
                "/api/admin/clients/{id}/rotate-key", "/api/admin/clients/7f0c/rotate-key", statusCode, outcome,
                "203.0.113.9", "admin-ui test");
    }

    private static AuditQuery byActor(String actor) {
        return new AuditQuery(actor, null, null, null);
    }

    @Test
    void aRecordedEventIsReadBackExactly() {
        String actor = uniqueActor("alice");
        AuditEvent recorded = event(actor, NOW, AuditOutcome.SUCCEEDED, 200);

        auditLog.append(recorded);

        assertThat(auditLog.search(byActor(actor), 0, 10).items()).containsExactly(recorded);
    }

    @Test
    void optionalValuesSurviveBeingAbsent() {
        AuditEvent anonymous = new AuditEvent(UUID.randomUUID(), NOW, null, null, "DELETE", null,
                "/api/admin/clients/" + UUID.randomUUID(), 401, AuditOutcome.DENIED, null, null);

        auditLog.append(anonymous);

        AuditPage page = auditLog.search(new AuditQuery(null, AuditOutcome.DENIED, NOW, NOW.plusMillis(1)), 0, 200);
        assertThat(page.items()).contains(anonymous);
    }

    @Test
    void eventsComeNewestFirstInPagesWithTheTotalCount() {
        String actor = uniqueActor("paging");
        AuditEvent oldest = event(actor, NOW.minus(Duration.ofMinutes(3)), AuditOutcome.SUCCEEDED, 200);
        AuditEvent middle = event(actor, NOW.minus(Duration.ofMinutes(2)), AuditOutcome.SUCCEEDED, 200);
        AuditEvent newest = event(actor, NOW.minus(Duration.ofMinutes(1)), AuditOutcome.SUCCEEDED, 200);
        auditLog.append(middle);
        auditLog.append(oldest);
        auditLog.append(newest);

        AuditPage first = auditLog.search(byActor(actor), 0, 2);
        AuditPage second = auditLog.search(byActor(actor), 1, 2);

        assertThat(first.items()).containsExactly(newest, middle);
        assertThat(second.items()).containsExactly(oldest);
        assertThat(first.totalItems()).isEqualTo(3);
        assertThat(first.page()).isZero();
        assertThat(first.size()).isEqualTo(2);
    }

    @Test
    void searchNarrowsByOutcomeAndByAHalfOpenTimeWindow() {
        String actor = uniqueActor("filtering");
        AuditEvent beforeWindow = event(actor, NOW.minus(Duration.ofHours(2)), AuditOutcome.SUCCEEDED, 200);
        AuditEvent deniedInWindow = event(actor, NOW.minus(Duration.ofMinutes(30)), AuditOutcome.DENIED, 403);
        AuditEvent succeededInWindow = event(actor, NOW.minus(Duration.ofMinutes(20)), AuditOutcome.SUCCEEDED, 200);
        AuditEvent atWindowEnd = event(actor, NOW, AuditOutcome.SUCCEEDED, 200);
        auditLog.append(beforeWindow);
        auditLog.append(deniedInWindow);
        auditLog.append(succeededInWindow);
        auditLog.append(atWindowEnd);
        Instant windowStart = NOW.minus(Duration.ofHours(1));

        assertThat(auditLog.search(new AuditQuery(actor, null, windowStart, NOW), 0, 10).items())
                .containsExactly(succeededInWindow, deniedInWindow);
        assertThat(auditLog.search(new AuditQuery(actor, AuditOutcome.DENIED, null, null), 0, 10).items())
                .containsExactly(deniedInWindow);
    }

    @Test
    void recordedEventsCannotBeChangedOrRemovedEvenByTheApplication() {
        String actor = uniqueActor("tamper");
        AuditEvent recorded = event(actor, NOW, AuditOutcome.SUCCEEDED, 200);
        auditLog.append(recorded);

        var update = jdbc.sql("UPDATE admin_audit_event SET actor = 'someone-else' WHERE id = :id")
                .param("id", recorded.id());
        assertThatThrownBy(update::update)
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
        var delete = jdbc.sql("DELETE FROM admin_audit_event WHERE id = :id").param("id", recorded.id());
        assertThatThrownBy(delete::update)
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
        var truncate = jdbc.sql("TRUNCATE admin_audit_event");
        assertThatThrownBy(truncate::update)
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");

        assertThat(auditLog.search(byActor(actor), 0, 10).items()).containsExactly(recorded);
    }
}
