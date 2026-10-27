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
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TrafficJobsTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:07:30Z");

    private final TrafficAggregator aggregator = mock(TrafficAggregator.class);
    private final AnomalyDetector detector = mock(AnomalyDetector.class);
    private final AnomalyProperties properties = new AnomalyProperties();
    private final TrafficJobs jobs = new TrafficJobs(aggregator, detector, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void eachRunMeasuresTheRecentClosedHoursOldestFirstAndDeletesPastRetention() {
        properties.setRecomputeHours(3);
        properties.setRetentionDays(400);

        jobs.run();

        InOrder order = inOrder(aggregator);
        order.verify(aggregator).aggregate(Instant.parse("2026-09-01T07:00:00Z"));
        order.verify(aggregator).aggregate(Instant.parse("2026-09-01T08:00:00Z"));
        order.verify(aggregator).aggregate(Instant.parse("2026-09-01T09:00:00Z"));
        order.verify(aggregator).deleteBefore(Instant.parse("2026-09-01T10:00:00Z").minus(400, ChronoUnit.DAYS));
        verify(aggregator, never()).aggregate(Instant.parse("2026-09-01T10:00:00Z"));
    }

    @Test
    void eachRunScoresTheHourJustClosedAtOnceAndTheFailureRateOfTheOldestReMeasuredHour() {
        properties.setRecomputeHours(3);

        jobs.run();

        InOrder order = inOrder(aggregator, detector);
        order.verify(aggregator).aggregate(Instant.parse("2026-09-01T09:00:00Z"));
        order.verify(detector).detect(Instant.parse("2026-09-01T09:00:00Z"), Feature.IMMEDIATE);
        order.verify(detector).detect(Instant.parse("2026-09-01T07:00:00Z"), Feature.SETTLED);
    }

    @Test
    void withoutHistoryTheBackfillMeasuresEveryHourOfThePeriod() {
        properties.setBackfillDays(2);
        when(aggregator.hasHistory()).thenReturn(false);

        jobs.backfill();

        verify(aggregator, times(48)).aggregate(any());
        verify(aggregator).aggregate(Instant.parse("2026-08-30T10:00:00Z"));
        verify(aggregator).aggregate(Instant.parse("2026-09-01T09:00:00Z"));
        verifyNoInteractions(detector);
    }

    @Test
    void withHistoryTheBackfillDoesNothing() {
        when(aggregator.hasHistory()).thenReturn(true);

        jobs.backfill();

        verify(aggregator, never()).aggregate(any());
    }
}
