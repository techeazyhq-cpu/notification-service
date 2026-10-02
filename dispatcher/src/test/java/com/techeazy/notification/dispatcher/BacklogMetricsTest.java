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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BacklogMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<BacklogSnapshot> database = new ArrayList<>();
    private final BacklogMetrics metrics = new BacklogMetrics(() -> List.copyOf(database), meters,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private double gauge(String name, MessageStatus status) {
        return meters.get(name).tag("status", status.name()).gauge().value();
    }

    @Test
    void reportsHowManyMessagesWaitInEachStatusAndHowOldTheOldestIs() {
        database.add(new BacklogSnapshot(MessageStatus.QUEUED, 12, NOW.minusSeconds(400)));
        database.add(new BacklogSnapshot(MessageStatus.RETRYING, 3, NOW.minusSeconds(1200)));

        metrics.refresh();

        assertThat(gauge(BacklogMetrics.MESSAGES, MessageStatus.QUEUED)).isEqualTo(12);
        assertThat(gauge(BacklogMetrics.OLDEST_AGE, MessageStatus.QUEUED)).isEqualTo(400);
        assertThat(gauge(BacklogMetrics.OLDEST_AGE, MessageStatus.RETRYING)).isEqualTo(1200);
    }

    @Test
    void aStatusWithNothingWaitingReportsZeroRatherThanDisappearing() {
        database.add(new BacklogSnapshot(MessageStatus.QUEUED, 12, NOW.minusSeconds(400)));
        metrics.refresh();
        database.clear();

        metrics.refresh();

        for (MessageStatus status : BacklogMetrics.IN_FLIGHT) {
            assertThat(gauge(BacklogMetrics.MESSAGES, status)).isZero();
            assertThat(gauge(BacklogMetrics.OLDEST_AGE, status)).isZero();
        }
    }
}
