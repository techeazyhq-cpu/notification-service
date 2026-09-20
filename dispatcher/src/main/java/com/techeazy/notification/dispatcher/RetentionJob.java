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

import com.techeazy.notification.application.RetentionService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Runs the retention steps once a day (see {@link RetentionService}); a failed run is logged and tried again the next day. */
@Component
@ConditionalOnProperty(name = "retention.enabled", havingValue = "true")
class RetentionJob {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionJob.class);

    private final RetentionService retention;
    private final MeterRegistry meters;

    RetentionJob(RetentionService retention, MeterRegistry meters) {
        this.retention = retention;
        this.meters = meters;
    }

    @Scheduled(cron = "${retention.cron:0 30 2 * * *}")
    void run() {
        try {
            RetentionService.Report report = retention.run(Instant.now());
            count("messages_erased", report.messagesErased());
            count("requests_erased", report.requestsErased());
            count("messages_deleted", report.messagesDeleted());
            count("requests_deleted", report.requestsDeleted());
            count("idempotency_keys_cleared", report.idempotencyKeysCleared());
        } catch (RuntimeException e) {
            LOG.error("Retention run failed; it will be tried again at the next scheduled time", e);
        }
    }

    private void count(String action, long rows) {
        meters.counter("notification.retention", "action", action).increment(rows);
    }
}
