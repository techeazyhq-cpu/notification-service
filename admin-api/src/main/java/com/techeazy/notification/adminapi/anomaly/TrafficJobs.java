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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Keeps {@code traffic_hourly} current (ADR-039). Each run measures the last few closed hours again, because messages
 * accepted in an hour can still fail after it ends, and deletes measurements past their retention. When there is no
 * history at all, the first start measures the backfill period, so baselines do not have to wait weeks.
 *
 * <p>Every admin API instance runs it. Measuring an hour twice writes the same row, so concurrent runs are harmless.
 */
@Component
@ConditionalOnProperty(prefix = "anomaly", name = "enabled", havingValue = "true")
class TrafficJobs {

    private static final Logger LOG = LoggerFactory.getLogger(TrafficJobs.class);
    private static final int HOURS_PER_DAY = 24;

    private final TrafficAggregator aggregator;
    private final AnomalyProperties properties;
    private final Clock clock;

    TrafficJobs(TrafficAggregator aggregator, AnomalyProperties properties, Clock clock) {
        this.aggregator = aggregator;
        this.properties = properties;
        this.clock = clock;
    }

    /** Backfills on its own thread: weeks of hours take a while, and readiness must not wait for them. */
    @EventListener(ApplicationReadyEvent.class)
    void onStart() {
        Thread.ofVirtual().name("traffic-backfill").start(() -> {
            try {
                backfill();
            } catch (RuntimeException e) {
                LOG.error("Measuring past traffic failed; recent hours are still measured every hour", e);
            }
        });
    }

    @Scheduled(cron = "${anomaly.aggregation-cron:0 5 * * * *}", zone = "UTC")
    void onSchedule() {
        try {
            run();
        } catch (RuntimeException e) {
            LOG.error("Measuring hourly traffic failed; it is retried on the next run", e);
        }
    }

    /** Measures the backfill period if nothing has been measured yet. */
    void backfill() {
        if (aggregator.hasHistory()) {
            return;
        }
        int hours = properties.getBackfillDays() * HOURS_PER_DAY;
        int rows = measureClosedHours(hours);
        LOG.info("Measured {} hour(s) of past traffic into {} row(s) for anomaly baselines", hours, rows);
    }

    /** Measures the most recent closed hours again and deletes measurements past their retention. */
    void run() {
        int rows = measureClosedHours(properties.getRecomputeHours());
        Instant cutOff = currentHour().minus(properties.getRetentionDays(), ChronoUnit.DAYS);
        int deleted = aggregator.deleteBefore(cutOff);
        LOG.debug("Measured {} hourly traffic row(s), deleted {} past retention", rows, deleted);
    }

    private int measureClosedHours(int hours) {
        Instant current = currentHour();
        int rows = 0;
        for (int back = hours; back >= 1; back--) {
            rows += aggregator.aggregate(current.minus(back, ChronoUnit.HOURS));
        }
        return rows;
    }

    private Instant currentHour() {
        return clock.instant().truncatedTo(ChronoUnit.HOURS);
    }
}
