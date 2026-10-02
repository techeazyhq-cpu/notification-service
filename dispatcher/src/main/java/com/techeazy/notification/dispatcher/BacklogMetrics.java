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
package com.techeazy.notification.dispatcher;

import com.techeazy.notification.domain.MessageStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Publishes the delivery backlog as gauges, per in-flight status: {@value #MESSAGES} (how many wait) and
 * {@value #OLDEST_AGE} (seconds since the oldest of them was accepted). They feed the freshness objective and its
 * alerts (see ADR-024). Every dispatcher instance reports the same figures; alert rules take the maximum. A status with
 * nothing waiting reports zero instead of disappearing, so an alert resolves rather than going stale.
 */
@Component
class BacklogMetrics {

    static final String MESSAGES = "notification.backlog.messages";
    static final String OLDEST_AGE = "notification.backlog.oldest.age";
    static final Set<MessageStatus> IN_FLIGHT = MessageStatus.IN_FLIGHT;

    private static final Logger LOG = LoggerFactory.getLogger(BacklogMetrics.class);

    private final BacklogReader reader;
    private final Clock clock;
    private volatile Map<MessageStatus, BacklogSnapshot> latest = Map.of();

    @Autowired
    BacklogMetrics(BacklogReader reader, MeterRegistry meters) {
        this(reader, meters, Clock.systemUTC());
    }

    BacklogMetrics(BacklogReader reader, MeterRegistry meters, Clock clock) {
        this.reader = reader;
        this.clock = clock;
        for (MessageStatus status : IN_FLIGHT) {
            Gauge.builder(MESSAGES, this, metrics -> metrics.messages(status))
                    .description("Messages accepted but not yet sent or failed, by status")
                    .tag("status", status.name()).register(meters);
            Gauge.builder(OLDEST_AGE, this, metrics -> metrics.oldestAgeSeconds(status))
                    .description("Seconds since the oldest message in this status was accepted")
                    .baseUnit("seconds").tag("status", status.name()).register(meters);
        }
    }

    @Scheduled(fixedDelayString = "${dispatcher.backlog-metrics-interval-ms:15000}")
    void refresh() {
        try {
            Map<MessageStatus, BacklogSnapshot> snapshots = new EnumMap<>(MessageStatus.class);
            reader.read().forEach(snapshot -> snapshots.put(snapshot.status(), snapshot));
            latest = snapshots;
        } catch (RuntimeException failure) {
            LOG.warn("Could not read the delivery backlog; the backlog gauges keep their last values: {}",
                    failure.getMessage());
        }
    }

    private double messages(MessageStatus status) {
        BacklogSnapshot snapshot = latest.get(status);
        return snapshot == null ? 0 : snapshot.messages();
    }

    private double oldestAgeSeconds(MessageStatus status) {
        BacklogSnapshot snapshot = latest.get(status);
        return snapshot == null ? 0 : Duration.between(snapshot.oldestCreatedAt(), clock.instant()).toSeconds();
    }
}
