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

package com.techeazy.notification.application;

import com.techeazy.notification.config.NotificationProperties;
import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.domain.NotificationMessage;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Recovers messages that fell between database commit and broker publish (PENDING), and messages
 * whose worker died mid-send (PROCESSING), and messages the broker acknowledged or lost without a worker ever
 * claiming them (QUEUED for longer than any healthy backlog). Delivery is therefore at-least-once; workers make
 * redelivery safe by atomically claiming a message before sending.
 */
@Component
@ConditionalOnProperty(prefix = "notification.sweeper", name = "enabled", havingValue = "true")
public class OutboxSweeper {

    private static final Logger log = LoggerFactory.getLogger(OutboxSweeper.class);

    private final NotificationMessageRepository messages;
    private final OutboxPublisher outbox;
    private final NotificationProperties props;

    public OutboxSweeper(NotificationMessageRepository messages, OutboxPublisher outbox, NotificationProperties props) {
        this.messages = messages;
        this.outbox = outbox;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${notification.sweeper.interval-ms:10000}")
    @Transactional
    public void sweep() {
        var cfg = props.getSweeper();
        Instant now = Instant.now();
        republish(messages.lockStale(MessageStatus.PENDING.name(), now.minus(Duration.ofSeconds(cfg.getPendingAgeSeconds())),
                cfg.getBatchSize()), "pending");
        republish(messages.lockStale(MessageStatus.PROCESSING.name(), now.minus(Duration.ofSeconds(cfg.getProcessingTimeoutSeconds())),
                cfg.getBatchSize()), "stuck processing");
        republish(messages.lockStale(MessageStatus.QUEUED.name(), now.minus(Duration.ofSeconds(cfg.getQueuedTimeoutSeconds())),
                cfg.getBatchSize()), "queued but never delivered");
    }

    private void republish(List<NotificationMessage> stale, String what) {
        if (stale.isEmpty()) return;
        Set<UUID> ok = new HashSet<>(outbox.publishOnly(stale));
        Instant now = Instant.now();
        for (NotificationMessage m : stale) {
            if (ok.contains(m.getId())) m.setStatus(MessageStatus.QUEUED);
            else m.setStatus(MessageStatus.PENDING);
            m.setUpdatedAt(now);
        }
        log.info("Sweeper republished {}/{} {} messages", ok.size(), stale.size(), what);
    }
}
