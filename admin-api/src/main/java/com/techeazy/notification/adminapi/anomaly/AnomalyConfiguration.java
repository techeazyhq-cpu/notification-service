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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Duration;

/** Wires the traffic anomaly detection of ADR-039. */
@Configuration
@EnableConfigurationProperties(AnomalyProperties.class)
class AnomalyConfiguration {

    @Bean
    TrafficAggregator trafficAggregator(JdbcClient jdbc, Clock clock, AnomalyProperties properties) {
        return new TrafficAggregator(jdbc, clock, Duration.ofDays(properties.getNewRecipientLookbackDays()),
                properties.getEmailDomainMinRecipients());
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
