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

import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    DeadLetterRecorder(NotificationMessageRepository messages, MeterRegistry meters) {
        this.messages = messages;
        this.meters = meters;
    }

    /** @return true when the message was moved to FAILED by this call */
    boolean recordDeadLetter(UUID messageId, String channel) {
        boolean changed = messages.markDeadLettered(messageId, MessageStatus.IN_FLIGHT, REASON, Instant.now()) == 1;
        if (changed) {
            LOG.warn("Message {} on {} was dead-lettered by the broker and is now FAILED", messageId, channel);
            meters.counter("notification.dead_letter", "channel", channel).increment();
        }
        return changed;
    }
}
