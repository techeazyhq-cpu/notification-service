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

import com.techeazy.notification.domain.MessageEvent;
import com.techeazy.notification.domain.MessageEventType;
import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.error.ErrorCode;
import com.techeazy.notification.persistence.MessageEventLog;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.UUID;

/**
 * Turns a message the broker gave up on into a visible dead letter: the message becomes FAILED with kind
 * DEAD_LETTERED, ready to be reprocessed from the admin UI. A message that was delivered or had already failed is left
 * as it is, so a late or duplicate dead letter is harmless.
 */
@Component
class DeadLetterRecorder {

    static final String REASON = "The broker gave up delivering this message to a worker after repeated failures";

    private static final Logger LOG = LoggerFactory.getLogger(DeadLetterRecorder.class);

    private final NotificationMessageRepository messages;
    private final MeterRegistry meters;
    private final MessageEventLog events;
    private final TransactionOperations transactions;

    DeadLetterRecorder(NotificationMessageRepository messages, MeterRegistry meters, MessageEventLog events,
                       TransactionOperations transactions) {
        this.messages = messages;
        this.meters = meters;
        this.events = events;
        this.transactions = transactions;
    }

    /** @return true when the message was moved to FAILED by this call */
    boolean recordDeadLetter(UUID messageId, String channel) {
        Instant now = Instant.now();
        boolean changed = Boolean.TRUE.equals(transactions.execute(status -> {
            if (messages.markDeadLettered(messageId, MessageStatus.IN_FLIGHT, REASON, now) != 1) {
                return false;
            }
            messages.findById(messageId).ifPresent(message -> events.record(
                    MessageEvent.of(message, MessageEventType.DEAD_LETTERED, now)
                            .withError(ErrorCode.DELIVERY_DEAD_LETTERED)));
            return true;
        }));
        if (changed) {
            LOG.warn("Message {} on {} was dead-lettered by the broker and is now FAILED", messageId, channel);
            meters.counter("notification.dead_letter", "channel", channel).increment();
            DispatchService.countError(meters, channel, ErrorCode.DELIVERY_DEAD_LETTERED);
        }
        return changed;
    }
}
