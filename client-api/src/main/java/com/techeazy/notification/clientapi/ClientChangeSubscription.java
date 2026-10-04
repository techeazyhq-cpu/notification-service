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

package com.techeazy.notification.clientapi;

import com.techeazy.notification.infra.ClientChangeBroadcast;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drops the cached client settings in {@link ClientAuthFilter} whenever an administrator changes a client. The
 * listener is deliberately not a Spring-managed container, which would fail start-up without Redis: the client API
 * must start and serve while Redis is down (the rate limiter fails open), so subscribing is retried on a schedule and
 * the cache expiry alone applies meanwhile. Once subscribed, the container re-subscribes by itself after a connection
 * loss.
 */
@Component
class ClientChangeSubscription {

    private static final Logger LOG = LoggerFactory.getLogger(ClientChangeSubscription.class);

    private final RedisMessageListenerContainer container = new RedisMessageListenerContainer();

    ClientChangeSubscription(RedisConnectionFactory connections, ClientAuthFilter auth) {
        container.setConnectionFactory(connections);
        container.addMessageListener((message, pattern) -> auth.forgetCachedClients(),
                new ChannelTopic(ClientChangeBroadcast.CHANNEL));
        container.afterPropertiesSet();
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${client-api.client-change-subscribe-retry-ms:10000}")
    void keepListening() {
        if (container.isRunning()) {
            return;
        }
        try {
            container.start();
            LOG.info("Listening for client changes on {}", ClientChangeBroadcast.CHANNEL);
        } catch (RuntimeException e) {
            container.stop();
            LOG.warn("Cannot listen for client changes yet; cached clients expire after 30 s meanwhile: {}", e.toString());
        }
    }

    boolean isListening() {
        return container.isRunning();
    }

    @PreDestroy
    void stop() throws Exception {
        container.destroy();
    }
}
