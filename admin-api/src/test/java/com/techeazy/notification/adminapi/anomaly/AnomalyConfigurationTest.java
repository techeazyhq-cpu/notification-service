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

package com.techeazy.notification.adminapi.anomaly;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/** The job reads through its own read-only pool only when a reporting user is configured (ADR-050). */
class AnomalyConfigurationTest {

    private static final JdbcClient PRIMARY = JdbcClient.create(
            new DriverManagerDataSource("jdbc:postgresql://localhost:5432/notification"));

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(JdbcClient.class, () -> PRIMARY)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(AnomalyConfiguration.class);

    @Test
    void withoutAReportingUserTheJobReadsThroughTheServiceConnection() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TrafficSource.class).jdbc()).isSameAs(PRIMARY);
        });
    }

    @Test
    void aReportingUserGivesTheJobAPoolOfItsOwn() {
        runner.withPropertyValues("anomaly.reporting-database.url=jdbc:postgresql://replica:5432/notification",
                        "anomaly.reporting-database.username=notification_reporting",
                        "anomaly.reporting-database.password=a-real-password")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TrafficSource.class).isSeparate()).isTrue();
                });
    }

    @Test
    void theShippedDevelopmentPasswordIsRefusedOutsideLocal() {
        runner.withPropertyValues("anomaly.reporting-database.username=notification_reporting",
                        "anomaly.reporting-database.password=" + AnomalyConfiguration.DEVELOPMENT_REPORTING_DEFAULT)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("anomaly.reporting-database.password"));
    }

    @Test
    void theShippedDevelopmentPasswordIsAcceptedInLocal() {
        runner.withPropertyValues("spring.profiles.active=local",
                        "anomaly.reporting-database.username=notification_reporting",
                        "anomaly.reporting-database.password=" + AnomalyConfiguration.DEVELOPMENT_REPORTING_DEFAULT)
                .run(context -> assertThat(context).hasNotFailed());
    }
}
