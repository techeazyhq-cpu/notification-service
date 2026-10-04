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

import com.techeazy.notification.port.NonceStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Forgets the nonces of signed requests once their timestamp is outside the acceptance window (ADR-036). An expired
 * nonce is harmless, since a request that reuses it is refused as expired anyway; this only keeps the table small. It
 * deletes in batches until none are left, and running it on several dispatchers at once is safe.
 */
@Component
class NonceSweepJob {

    private static final Logger LOG = LoggerFactory.getLogger(NonceSweepJob.class);

    private final NonceStore nonces;
    private final Clock clock;
    private final int batchSize;
    private final Counter purged;

    @Autowired
    NonceSweepJob(NonceStore nonces, MeterRegistry meters,
                  @Value("${dispatcher.nonce-sweep-batch-size:10000}") int batchSize) {
        this(nonces, meters, Clock.systemUTC(), batchSize);
    }

    NonceSweepJob(NonceStore nonces, MeterRegistry meters, Clock clock, int batchSize) {
        this.nonces = nonces;
        this.clock = clock;
        this.batchSize = batchSize;
        this.purged = Counter.builder("notification.signature.nonces_purged")
                .description("Expired request-signature nonces deleted").register(meters);
    }

    @Scheduled(fixedDelayString = "${dispatcher.nonce-sweep-interval-ms:600000}")
    void sweep() {
        try {
            int deleted;
            do {
                deleted = nonces.purgeExpired(clock.instant(), batchSize);
                purged.increment(deleted);
            } while (deleted == batchSize);
        } catch (RuntimeException e) {
            LOG.warn("Could not delete expired request nonces; trying again at the next interval", e);
        }
    }
}
