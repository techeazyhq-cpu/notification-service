package com.techeazy.notification.adminapi;

import com.techeazy.notification.billing.application.Admission;
import com.techeazy.notification.billing.application.AdmissionControl;
import com.techeazy.notification.billing.domain.HoldScope;
import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.domain.NotificationMessage;
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

    MessageRetry(NotificationMessageRepository messages, AdmissionControl admission) {
        this.messages = messages;
        this.admission = admission;
    }

    @Transactional
    NotificationMessage requeue(UUID id) {
        NotificationMessage message = messages.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (message.getStatus() != MessageStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only FAILED messages can be retried (status is " + message.getStatus() + ")");
        }
        admission.admit(new Admission(message.getClientId(), message.getChannel(), 1, HoldScope.MESSAGE, message.getId()));
        if (messages.requeueFailed(id, Instant.now()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The message changed while it was being retried");
        }
        return message;
    }
}
