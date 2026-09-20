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

import com.techeazy.notification.domain.NotificationMessage;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import com.techeazy.notification.port.MessagePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes committed PENDING messages and flips the confirmed ones to QUEUED.
 * Anything that fails to publish stays PENDING and is picked up by the {@link OutboxSweeper}.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int CHUNK = 500;

    private final MessagePublisher publisher;
    private final NotificationMessageRepository messages;
    private final QueuedMarker queuedMarker;

    public OutboxPublisher(MessagePublisher publisher, NotificationMessageRepository messages, QueuedMarker queuedMarker) {
        this.publisher = publisher;
        this.messages = messages;
        this.queuedMarker = queuedMarker;
    }

    /** @return ids that were confirmed published */
    public List<UUID> publishAndMarkQueued(List<NotificationMessage> batch) {
        List<UUID> confirmed = new ArrayList<>(batch.size());
        for (int from = 0; from < batch.size(); from += CHUNK) {
            List<NotificationMessage> chunk = batch.subList(from, Math.min(batch.size(), from + CHUNK));
            List<UUID> ok = publishChunk(chunk);
            if (!ok.isEmpty()) messages.markQueued(ok, Instant.now());
            confirmed.addAll(ok);
        }
        return confirmed;
    }

    /**
     * Publishes and returns as soon as the broker has confirmed; the PENDING to QUEUED update is batched by
     * {@link QueuedMarker}. Used on the request path, where waiting for a second database round trip only adds latency.
     */
    public List<UUID> publishAndMarkQueuedLater(List<NotificationMessage> batch) {
        List<UUID> confirmed = publishOnly(batch);
        queuedMarker.markLater(confirmed);
        return confirmed;
    }

    /** Publish without touching the database; used by callers that manage entities themselves. */
    public List<UUID> publishOnly(List<NotificationMessage> batch) {
        List<UUID> confirmed = new ArrayList<>(batch.size());
        for (int from = 0; from < batch.size(); from += CHUNK) {
            confirmed.addAll(publishChunk(batch.subList(from, Math.min(batch.size(), from + CHUNK))));
        }
        return confirmed;
    }

    private List<UUID> publishChunk(List<NotificationMessage> chunk) {
        List<CompletableFuture<Void>> futures = new ArrayList<>(chunk.size());
        for (NotificationMessage m : chunk) {
            futures.add(publisher.publish(m.getChannel(), m.getId(), m.getClientId()));
        }
        List<UUID> ok = new ArrayList<>(chunk.size());
        for (int i = 0; i < chunk.size(); i++) {
            try {
                futures.get(i).get(30, TimeUnit.SECONDS);
                ok.add(chunk.get(i).getId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while publishing; {} message(s) left for the sweeper", chunk.size() - i);
                break;
            } catch (ExecutionException | TimeoutException e) {
                log.warn("Publish failed for message {}, left for sweeper: {}", chunk.get(i).getId(), e.toString());
            }
        }
        return ok;
    }
}
