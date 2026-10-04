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
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Recovers messages that fell between database commit and broker publish (PENDING), and messages
 * whose worker died mid-send (PROCESSING), and messages the broker acknowledged or lost without a worker ever
 * claiming them (QUEUED for longer than any healthy backlog). Delivery is therefore at-least-once; workers make
 * redelivery safe by atomically claiming a message before sending.
 *
 * <p>One-time passwords are swept first, with thresholds of seconds rather than minutes, because the general
 * thresholds are longer than their validity: a lost one would otherwise only be recovered after it expired
 * (ADR-033).
 *
 * <p>Each batch is taken in a short transaction of its own: the stale rows are locked ({@code SKIP LOCKED}, so
 * several sweepers never take the same rows), set back to PENDING with a fresh {@code updated_at}, which keeps other
 * sweepers away from them, and committed. Only then are they published, so no database connection or row lock is held
 * while waiting for the broker. Published rows are marked QUEUED afterwards unless a worker already moved them on;
 * rows that failed to publish stay PENDING and are swept again.
 */
@Component
@ConditionalOnProperty(prefix = "notification.sweeper", name = "enabled", havingValue = "true")
public class OutboxSweeper {

    private static final Logger log = LoggerFactory.getLogger(OutboxSweeper.class);

    private final NotificationMessageRepository messages;
    private final OutboxPublisher outbox;
    private final NotificationProperties props;
    private final TransactionOperations transactions;

    public OutboxSweeper(NotificationMessageRepository messages, OutboxPublisher outbox, NotificationProperties props,
                         TransactionOperations transactions) {
        this.messages = messages;
        this.outbox = outbox;
        this.props = props;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${notification.sweeper.interval-ms:10000}")
    public void sweep() {
        var cfg = props.getSweeper();
        Instant now = Instant.now();
        sweepOneTimePasswords(cfg, now);
        republish(() -> messages.lockStale(MessageStatus.PENDING.name(),
                now.minus(Duration.ofSeconds(cfg.getPendingAgeSeconds())), cfg.getBatchSize()), "pending");
        republish(() -> messages.lockStale(MessageStatus.PROCESSING.name(),
                now.minus(Duration.ofSeconds(cfg.getProcessingTimeoutSeconds())), cfg.getBatchSize()), "stuck processing");
        republish(() -> messages.lockStale(MessageStatus.QUEUED.name(),
                now.minus(Duration.ofSeconds(cfg.getQueuedTimeoutSeconds())), cfg.getBatchSize()),
                "queued but never delivered");
        republish(() -> messages.lockStale(MessageStatus.RETRYING.name(),
                now.minus(Duration.ofSeconds(cfg.getRetryingTimeoutSeconds())), cfg.getBatchSize()),
                "retry never redelivered");
    }

    private void sweepOneTimePasswords(NotificationProperties.Sweeper cfg, Instant now) {
        var otp = cfg.getOtp();
        republish(() -> messages.lockStaleOtp(MessageStatus.PENDING.name(),
                now.minus(Duration.ofSeconds(otp.getPendingAgeSeconds())), cfg.getBatchSize()), "pending OTP");
        republish(() -> messages.lockStaleOtp(MessageStatus.PROCESSING.name(),
                now.minus(Duration.ofSeconds(otp.getProcessingTimeoutSeconds())), cfg.getBatchSize()),
                "stuck processing OTP");
        republish(() -> messages.lockStaleOtp(MessageStatus.QUEUED.name(),
                now.minus(Duration.ofSeconds(otp.getQueuedTimeoutSeconds())), cfg.getBatchSize()),
                "queued but never delivered OTP");
        republish(() -> messages.lockStaleOtp(MessageStatus.RETRYING.name(),
                now.minus(Duration.ofSeconds(otp.getRetryingTimeoutSeconds())), cfg.getBatchSize()),
                "retry never redelivered OTP");
    }

    private void republish(Supplier<List<NotificationMessage>> lockStale, String what) {
        List<NotificationMessage> stale = transactions.execute(status -> {
            List<NotificationMessage> locked = lockStale.get();
            if (!locked.isEmpty()) {
                messages.releaseForRepublish(locked.stream().map(NotificationMessage::getId).toList(), Instant.now());
            }
            return locked;
        });
        if (stale == null || stale.isEmpty()) return;
        List<UUID> published = outbox.publishOnly(stale);
        if (!published.isEmpty()) {
            messages.markQueued(published, Instant.now());
        }
        log.info("Sweeper republished {}/{} {} messages", published.size(), stale.size(), what);
    }
}
