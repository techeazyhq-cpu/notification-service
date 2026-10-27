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

package com.techeazy.notification.adminapi.anomaly;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against a real PostgreSQL; skipped automatically without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class TrafficSourceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void withoutAReportingUserTheServiceConnectionIsUsed() {
        JdbcClient primary = primary();

        try (TrafficSource source = TrafficSource.of(new AnomalyProperties.ReportingDatabase(), primary)) {
            assertThat(source.jdbc()).isSameAs(primary);
            assertThat(source.isSeparate()).isFalse();
        }
    }

    @Test
    void aReportingUserGetsAPoolOfItsOwnThatCannotWrite() {
        primary().sql("CREATE TABLE IF NOT EXISTS probe (id int)").update();

        try (TrafficSource source = TrafficSource.of(reporting(), primary())) {
            assertThat(source.isSeparate()).isTrue();
            assertThat(source.jdbc().sql("SELECT count(*) FROM probe").query(Integer.class).single()).isZero();
            assertThatThrownBy(() -> source.jdbc().sql("INSERT INTO probe VALUES (1)").update())
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("read-only transaction");
        }
    }

    @Test
    void aBlankUserMeansNoReportingDatabase() {
        AnomalyProperties.ReportingDatabase reporting = reporting();
        reporting.setUsername(" ");

        assertThat(reporting.isConfigured()).isFalse();
    }

    private static AnomalyProperties.ReportingDatabase reporting() {
        AnomalyProperties.ReportingDatabase reporting = new AnomalyProperties.ReportingDatabase();
        reporting.setUrl(POSTGRES.getJdbcUrl());
        reporting.setUsername(POSTGRES.getUsername());
        reporting.setPassword(POSTGRES.getPassword());
        return reporting;
    }

    private static JdbcClient primary() {
        return JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
    }
}
