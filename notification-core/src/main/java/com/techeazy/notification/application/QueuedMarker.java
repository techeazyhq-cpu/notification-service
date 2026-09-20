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

import com.techeazy.notification.persistence.NotificationMessageRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Moves published messages from PENDING to QUEUED in batches, off the request path.
 *
 * <p>A request no longer waits for its own status update: once the broker has confirmed a message the caller can be
 * answered, and the status change follows within {@code notification.queued-flush-ms}. The update only touches rows
 * that are still PENDING, so it can never undo progress made by the dispatcher. If the process dies before a flush, the
 * messages stay PENDING although they were published; the outbox sweeper then publishes them again, which is safe
 * because delivery claims each message atomically (at-least-once, never twice at the same time).
 */
@Component
public class QueuedMarker {

    private static final Logger log = LoggerFactory.getLogger(QueuedMarker.class);
    private static final int BATCH = 1000;

    private final NotificationMessageRepository messages;
    private final ConcurrentLinkedQueue<UUID> pending = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService flusher;

    public QueuedMarker(NotificationMessageRepository messages, @Value("${notification.queued-flush-ms:50}") long flushMillis) {
        this.messages = messages;
        this.flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "queued-marker");
            thread.setDaemon(true);
            return thread;
        });
        this.flusher.scheduleWithFixedDelay(this::flush, flushMillis, flushMillis, TimeUnit.MILLISECONDS);
    }

    public void markLater(List<UUID> ids) {
        pending.addAll(ids);
    }

    int backlog() {
        return pending.size();
    }

    void flush() {
        try {
            List<UUID> batch = drain();
            while (!batch.isEmpty()) {
                messages.markQueued(batch, Instant.now());
                batch = drain();
            }
        } catch (RuntimeException e) {
            log.warn("Could not mark published messages as queued; the sweeper will recover them: {}", e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        flusher.shutdown();
        flush();
    }

    private List<UUID> drain() {
        List<UUID> batch = new ArrayList<>(BATCH);
        UUID id;
        while (batch.size() < BATCH && (id = pending.poll()) != null) {
            batch.add(id);
        }
        return batch;
    }
}
