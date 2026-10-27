/*
 * Copyright 2026 Vasantha Kumar Kesavan
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
 * @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>
 */

package com.techeazy.notification.clientapi;

import com.techeazy.notification.domain.Channel;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A client's draft request lands in the admin audit log of ADR-019; skipped automatically without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class DraftAuditTrailTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcClient jdbc;

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
    void aClientsDraftRequestIsRecordedWithoutItsBody() {
        String name = "acme-" + UUID.randomUUID();
        AuthenticatedClient client = new AuthenticatedClient(UUID.randomUUID(), name, Set.of(Channel.SMS));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/templates/drafts");
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("User-Agent", "client-ui test");
        request.setContent("{\"purpose\":\"secret brief\"}".getBytes());

        new DraftAuditTrail(jdbc).record(client, request, 429);

        Map<String, Object> row = jdbc.sql("SELECT * FROM admin_audit_event WHERE actor = :actor")
                .param("actor", "client:" + name).query().singleRow();
        assertThat(row).containsEntry("actor_role", null).containsEntry("http_method", "POST")
                .containsEntry("route", "/v1/templates/drafts").containsEntry("status_code", 429)
                .containsEntry("outcome", "REJECTED").containsEntry("source_address", "203.0.113.7")
                .containsEntry("user_agent", "client-ui test");
        assertThat(row.values()).noneMatch(value -> String.valueOf(value).contains("secret brief"));
    }
}
