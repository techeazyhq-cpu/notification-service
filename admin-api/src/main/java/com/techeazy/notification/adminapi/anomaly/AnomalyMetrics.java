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

import com.techeazy.notification.domain.Channel;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exports how many traffic anomalies are open, per channel and severity, for alerting (ADR-039). Every channel and
 * severity is exported, at zero when nothing is open, so an alert resolves instead of going stale. The counts are read
 * on a schedule rather than at each scrape, and every admin API instance exports the same numbers, so alert rules take
 * the maximum across instances.
 */
class AnomalyMetrics {

    static final String OPEN_ANOMALIES = "notification.traffic.anomalies.open";

    private static final Logger LOG = LoggerFactory.getLogger(AnomalyMetrics.class);

    private final AnomalyRepository anomalies;
    private final Map<String, AtomicLong> open = new HashMap<>();

    AnomalyMetrics(AnomalyRepository anomalies, MeterRegistry meters) {
        this.anomalies = anomalies;
        for (Channel channel : Channel.values()) {
            for (Severity severity : Severity.values()) {
                AtomicLong value = new AtomicLong();
                open.put(key(channel.name(), severity), value);
                Gauge.builder(OPEN_ANOMALIES, value, AtomicLong::get)
                        .description("Traffic anomalies no administrator has decided yet")
                        .tag("channel", channel.name()).tag("severity", severity.name())
                        .register(meters);
            }
        }
    }

    /** Reads the open counts again. On failure the previous counts stay, and the next run tries again. */
    @Scheduled(initialDelay = 0, fixedDelayString = "${anomaly.metrics-interval-ms:60000}")
    void refresh() {
        try {
            Map<String, Long> counts = new HashMap<>();
            anomalies.openCounts().forEach(count -> counts.put(key(count.channel(), count.severity()), count.count()));
            open.forEach((key, value) -> value.set(counts.getOrDefault(key, 0L)));
        } catch (RuntimeException e) {
            LOG.warn("Counting open traffic anomalies failed; the previous counts are exported until the next run", e);
        }
    }

    private static String key(String channel, Severity severity) {
        return channel + "/" + severity.name();
    }
}
