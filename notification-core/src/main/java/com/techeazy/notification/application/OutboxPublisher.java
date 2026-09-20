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
import java.util.concurrent.TimeUnit;

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

    public OutboxPublisher(MessagePublisher publisher, NotificationMessageRepository messages) {
        this.publisher = publisher;
        this.messages = messages;
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
            } catch (Exception e) {
                log.warn("Publish failed for message {}, left for sweeper: {}", chunk.get(i).getId(), e.toString());
            }
        }
        return ok;
    }
}
