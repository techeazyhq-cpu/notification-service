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

package com.techeazy.notification.adminapi;

import com.techeazy.notification.application.PersonalData;
import com.techeazy.notification.billing.application.Admission;
import com.techeazy.notification.billing.application.AdmissionControl;
import com.techeazy.notification.billing.domain.HoldScope;
import com.techeazy.notification.domain.FailureKind;
import com.techeazy.notification.domain.MessageCategory;
import com.techeazy.notification.domain.MessageEvent;
import com.techeazy.notification.domain.MessageEventType;
import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.domain.NotificationMessage;
import com.techeazy.notification.persistence.MessageEventLog;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * Puts a failed message back into the outbox state. For a prepaid client the cost of the new attempt is reserved in
 * the same transaction, so an insufficient balance leaves the message failed and nothing is charged. The returned
 * message must be published after this transaction commits.
 */
@Service
class MessageRetry {

    private final NotificationMessageRepository messages;
    private final AdmissionControl admission;
    private final MessageEventLog events;

    MessageRetry(NotificationMessageRepository messages, AdmissionControl admission, MessageEventLog events) {
        this.messages = messages;
        this.admission = admission;
        this.events = events;
    }

    @Transactional
    NotificationMessage requeue(UUID id) {
        NotificationMessage message = messages.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (message.getStatus() != MessageStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only FAILED messages can be retried (status is " + message.getStatus() + ")");
        }
        if (message.getFailureKind() == FailureKind.EXPIRED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This one-time password expired, so its code is no longer valid; the user must request a new one");
        }
        if (PersonalData.isErased(message.getRecipient())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The personal data of this message was erased, so it cannot be sent again");
        }
        admission.admit(new Admission(message.getClientId(), message.getChannel(), 1, HoldScope.MESSAGE, message.getId(),
                message.getCategory() == MessageCategory.OTP));
        Instant now = Instant.now();
        if (messages.requeueFailed(id, now) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The message changed while it was being retried");
        }
        events.record(MessageEvent.of(message, MessageEventType.REQUEUED, now)
                .withDetail("Sent again by the platform operator"));
        return message;
    }
}
