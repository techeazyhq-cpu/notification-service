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

import com.techeazy.notification.config.InsecureDefaults;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Duration;

/** Wires the traffic anomaly detection of ADR-039. */
@Configuration
@EnableConfigurationProperties(AnomalyProperties.class)
class AnomalyConfiguration {

    static final String DEVELOPMENT_REPORTING_DEFAULT = "notification_reporting";

    @Bean
    TrafficSource trafficSource(JdbcClient jdbc, AnomalyProperties properties, Environment env) {
        AnomalyProperties.ReportingDatabase reporting = properties.getReportingDatabase();
        if (reporting.isConfigured()) {
            InsecureDefaults.reject(env, "anomaly.reporting-database.password", reporting.getPassword(),
                    DEVELOPMENT_REPORTING_DEFAULT);
        }
        return TrafficSource.of(reporting, jdbc);
    }

    @Bean
    TrafficAggregator trafficAggregator(TrafficSource source, JdbcClient jdbc, Clock clock,
                                        AnomalyProperties properties) {
        return new TrafficAggregator(source.jdbc(), jdbc, clock,
                Duration.ofDays(properties.getNewRecipientLookbackDays()), properties.getEmailDomainMinRecipients());
    }

    @Bean
    TrafficReader trafficReader(JdbcClient jdbc) {
        return new TrafficReader(jdbc);
    }

    @Bean
    BaselineScorer baselineScorer(AnomalyProperties properties) {
        return new BaselineScorer(properties.getScoring());
    }

    @Bean
    AnomalyRepository anomalyRepository(JdbcClient jdbc) {
        return new AnomalyRepository(jdbc);
    }

    @Bean
    AnomalyDetector anomalyDetector(TrafficReader reader, BaselineScorer scorer, JdbcClient jdbc, Clock clock,
                                    AnomalyProperties properties) {
        return new AnomalyDetector(reader, scorer, new AnomalyStore(jdbc), clock, properties.getScoring().getBaselineWeeks());
    }

    @Bean
    TrafficReports trafficReports(TrafficReader reader, AnomalyRepository anomalies, BaselineScorer scorer, Clock clock,
                                  AnomalyProperties properties) {
        return new TrafficReports(reader, anomalies, scorer, clock, properties.getScoring().getBaselineWeeks());
    }

    @Bean
    AnomalyMetrics anomalyMetrics(AnomalyRepository anomalies, MeterRegistry meters) {
        return new AnomalyMetrics(anomalies, meters);
    }
}
