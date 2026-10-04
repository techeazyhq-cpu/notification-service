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

package com.techeazy.notification.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Tells every client API instance that a client's key, status or signing settings changed, over a Redis channel, so
 * they drop their cached copy at once instead of when it expires. The message goes out after the surrounding
 * transaction commits, so a listener that reloads straight away sees the change. Delivery is best effort: if Redis
 * cannot be reached the failure is logged and the cache expiry still bounds how long the old settings are used.
 */
@Component
public class ClientChangeBroadcast {

    public static final String CHANNEL = "notification:client-changed";

    private static final Logger LOG = LoggerFactory.getLogger(ClientChangeBroadcast.class);

    private final StringRedisTemplate redis;

    public ClientChangeBroadcast(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void clientChanged(UUID clientId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(clientId);
                }
            });
        } else {
            send(clientId);
        }
    }

    private void send(UUID clientId) {
        try {
            redis.convertAndSend(CHANNEL, clientId.toString());
        } catch (RuntimeException e) {
            LOG.warn("Could not announce the change to client {}; client API instances pick it up when their cache "
                    + "expires: {}", clientId, e.toString());
        }
    }
}
