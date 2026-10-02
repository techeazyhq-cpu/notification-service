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

package com.techeazy.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter @Setter
@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {

    private Pulsar pulsar = new Pulsar();
    private RateLimit rateLimit = new RateLimit();
    private Sweeper sweeper = new Sweeper();

    @Getter @Setter
    public static class Pulsar {
        private String serviceUrl = "pulsar://localhost:6650";
        /** Topics are {@code <topicPrefix><channel>}, e.g. persistent://public/default/notification-email. */
        private String topicPrefix = "persistent://public/default/notification-";
        /** PEM file with the CA that signed the broker certificate; set together with a {@code pulsar+ssl://} service URL. */
        private String tlsTrustCertsFile = "";
        /** How long an accept waits for the broker to confirm a publish; on timeout the row stays PENDING and the sweeper publishes it. */
        private int publishTimeoutSeconds = 5;
        private CircuitBreaker circuitBreaker = new CircuitBreaker();

        /**
         * Guards publishing (ADR-032): once enough publishes fail, further ones fail at once instead of each waiting
         * out the publish timeout; the messages stay PENDING and the sweeper publishes them when the broker is back.
         */
        @Getter @Setter
        public static class CircuitBreaker {
            private boolean enabled = true;
            /** Publishes in the window the failure rate is computed over. */
            private int slidingWindowSize = 20;
            /** Publishes needed before the failure rate counts. */
            private int minimumNumberOfCalls = 10;
            /** Percentage of failed publishes that opens the circuit. */
            private float failureRateThreshold = 50;
            /** How long the circuit stays open before probe publishes are let through. */
            private long waitDurationInOpenStateMs = 10_000;
            /** Probe publishes while half-open; enough of them must succeed to close the circuit. */
            private int permittedCallsInHalfOpenState = 3;
        }
    }

    @Getter @Setter
    public static class RateLimit {
        /** Applied to client API calls when a client has no CLIENT_API policy. */
        private double defaultClientApiRate = 50;
        private int defaultClientApiBurst = 100;
        private long policyCacheSeconds = 10;
        /** Share of each delivery bucket that only priority messages (one-time passwords) may use (ADR-033). */
        private double priorityReserveFraction = 0.2;
    }

    @Getter @Setter
    public static class Sweeper {
        /** Only the dispatcher runs the sweeper by default. */
        private boolean enabled = false;
        private long intervalMs = 10_000;
        /** PENDING rows older than this were never confirmed as published. */
        private long pendingAgeSeconds = 30;
        /** PROCESSING rows older than this belong to a crashed worker. */
        private long processingTimeoutSeconds = 300;
        /** QUEUED rows older than this were lost between the broker and a worker; republishing is safe because workers claim atomically. */
        private long queuedTimeoutSeconds = 900;
        /**
         * RETRYING rows older than this lost their delayed redelivery in the broker. Well past the longest backoff
         * (dispatcher.max-backoff-seconds, 300), so a retry that is merely waiting is never sent twice early.
         */
        private long retryingTimeoutSeconds = 900;
        private int batchSize = 500;
    }
}
