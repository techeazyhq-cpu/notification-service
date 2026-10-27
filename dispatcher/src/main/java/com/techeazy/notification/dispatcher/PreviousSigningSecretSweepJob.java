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

package com.techeazy.notification.dispatcher;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;

/**
 * Deletes a client's previous signing secret once its rotation's grace period has ended (ADR-051). The client API
 * already refuses it from that moment, so this only keeps a secret nobody may use from staying in the database.
 * Running it on several dispatchers at once is safe.
 */
@Component
class PreviousSigningSecretSweepJob {

    private static final Logger LOG = LoggerFactory.getLogger(PreviousSigningSecretSweepJob.class);
    private static final String FORGET_ENDED = """
            UPDATE client SET previous_signing_secret = NULL, previous_signing_secret_expires_at = NULL
            WHERE previous_signing_secret_expires_at <= :now""";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Counter forgotten;

    @Autowired
    PreviousSigningSecretSweepJob(JdbcClient jdbc, MeterRegistry meters) {
        this(jdbc, meters, Clock.systemUTC());
    }

    PreviousSigningSecretSweepJob(JdbcClient jdbc, MeterRegistry meters, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.forgotten = Counter.builder("notification.signature.previous_secrets_forgotten")
                .description("Previous request-signing secrets deleted after their grace period").register(meters);
    }

    @Scheduled(fixedDelayString = "${dispatcher.nonce-sweep-interval-ms:600000}")
    void sweep() {
        try {
            forgotten.increment(jdbc.sql(FORGET_ENDED).param("now", Timestamp.from(clock.instant())).update());
        } catch (RuntimeException e) {
            LOG.warn("Could not delete ended previous signing secrets; trying again at the next interval", e);
        }
    }
}
