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
    }

    @Getter @Setter
    public static class RateLimit {
        /** Applied to client API calls when a client has no CLIENT_API policy. */
        private double defaultClientApiRate = 50;
        private int defaultClientApiBurst = 100;
        private long policyCacheSeconds = 10;
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
        private int batchSize = 500;
    }
}
