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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnomalyMetricsTest {

    private final AnomalyRepository repository = mock(AnomalyRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AnomalyMetrics metrics = new AnomalyMetrics(repository, meters);

    @Test
    void everyChannelAndSeverityIsExportedAtZeroBeforeAnythingIsOpen() {
        assertThat(meters.find(AnomalyMetrics.OPEN_ANOMALIES).gauges()).hasSize(8)
                .allMatch(gauge -> gauge.value() == 0);
    }

    @Test
    void aRefreshExportsTheOpenCountsAndResetsCombinationsThatClosed() {
        when(repository.openCounts()).thenReturn(List.of(new AnomalyRepository.OpenCount("SMS", Severity.HIGH, 3),
                new AnomalyRepository.OpenCount("EMAIL", Severity.WARNING, 1)));
        metrics.refresh();
        when(repository.openCounts()).thenReturn(List.of(new AnomalyRepository.OpenCount("SMS", Severity.HIGH, 2)));

        metrics.refresh();

        assertThat(open("SMS", "HIGH")).isEqualTo(2);
        assertThat(open("EMAIL", "WARNING")).isZero();
    }

    @Test
    void aFailedRefreshKeepsTheLastCounts() {
        when(repository.openCounts()).thenReturn(List.of(new AnomalyRepository.OpenCount("SMS", Severity.HIGH, 3)));
        metrics.refresh();
        when(repository.openCounts()).thenThrow(new IllegalStateException("database down"));

        metrics.refresh();

        assertThat(open("SMS", "HIGH")).isEqualTo(3);
    }

    private double open(String channel, String severity) {
        return meters.get(AnomalyMetrics.OPEN_ANOMALIES).tag("channel", channel).tag("severity", severity).gauge().value();
    }
}
