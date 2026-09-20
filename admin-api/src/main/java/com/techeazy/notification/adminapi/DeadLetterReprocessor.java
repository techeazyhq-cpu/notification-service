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

import com.techeazy.notification.application.OutboxPublisher;
import com.techeazy.notification.billing.domain.BillingException;
import com.techeazy.notification.domain.NotificationMessage;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Puts many dead letters back into the send path. Each message is re-queued in its own transaction through
 * {@link MessageRetry}, which reserves credit for prepaid clients and refuses erased or already changed messages, so
 * one refusal never blocks the others. The re-queued messages are then published; anything that fails to publish stays
 * in the outbox state and the sweeper sends it. Oldest first, so a partial run always makes progress on the longest waiting.
 */
@Service
class DeadLetterReprocessor {

    static final int MAX_BATCH = 1000;
    static final int DEFAULT_BATCH = 200;
    static final int MAX_REPORTED_REFUSALS = 50;

    private static final Logger LOG = LoggerFactory.getLogger(DeadLetterReprocessor.class);

    record Refusal(UUID id, String reason) {}

    record Result(int selected, int requeued, int published, int refusedCount, List<Refusal> refused, long remaining) {}

    private final NotificationMessageRepository messages;
    private final MessageRetry retry;
    private final OutboxPublisher outbox;

    DeadLetterReprocessor(NotificationMessageRepository messages, MessageRetry retry, OutboxPublisher outbox) {
        this.messages = messages;
        this.retry = retry;
        this.outbox = outbox;
    }

    Result reprocess(List<UUID> ids, DeadLetterFilter filter, Integer limit) {
        List<UUID> selected = ids != null && !ids.isEmpty() ? explicit(ids) : matching(filter, limit);
        List<NotificationMessage> requeued = new ArrayList<>(selected.size());
        List<Refusal> refused = new ArrayList<>();
        int refusedCount = 0;
        for (UUID id : selected) {
            try {
                requeued.add(retry.requeue(id));
            } catch (ResponseStatusException e) {
                refusedCount++;
                addRefusal(refused, id, e.getReason());
            } catch (BillingException e) {
                refusedCount++;
                addRefusal(refused, id, e.getMessage());
            }
        }
        int published = requeued.isEmpty() ? 0 : outbox.publishAndMarkQueued(requeued).size();
        long remaining = messages.count(filter.reprocessSpecification());
        LOG.info("Reprocessed dead letters: {} selected, {} re-queued, {} published, {} refused, {} remaining",
                selected.size(), requeued.size(), published, refusedCount, remaining);
        return new Result(selected.size(), requeued.size(), published, refusedCount, refused, remaining);
    }

    private static List<UUID> explicit(List<UUID> ids) {
        List<UUID> unique = new ArrayList<>(new LinkedHashSet<>(ids));
        return unique.size() > MAX_BATCH ? unique.subList(0, MAX_BATCH) : unique;
    }

    private List<UUID> matching(DeadLetterFilter filter, Integer limit) {
        int size = Math.clamp(limit == null ? DEFAULT_BATCH : limit, 1, MAX_BATCH);
        return messages.findAll(filter.reprocessSpecification(), PageRequest.of(0, size, Sort.by(Sort.Direction.ASC, "updatedAt")))
                .stream().map(NotificationMessage::getId).toList();
    }

    private static void addRefusal(List<Refusal> refused, UUID id, String reason) {
        if (refused.size() < MAX_REPORTED_REFUSALS) {
            refused.add(new Refusal(id, reason == null ? "Refused" : reason));
        }
    }
}
