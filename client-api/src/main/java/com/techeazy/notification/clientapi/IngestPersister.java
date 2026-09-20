package com.techeazy.notification.clientapi;

import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.domain.NotificationMessage;
import com.techeazy.notification.domain.NotificationRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Writes the request and all of its messages atomically as PENDING (the outbox state).
 * Uses persist() directly: assigned UUIDs would make Spring Data's save() issue a SELECT per row.
 */
@Component
public class IngestPersister {

    public record Recipient(String address, java.util.Map<String, String> variables) {}

    private static final int FLUSH_EVERY = 500;

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public List<NotificationMessage> persist(NotificationRequest request, List<Recipient> recipients) {
        em.persist(request);
        Instant now = request.getCreatedAt();
        List<NotificationMessage> out = new ArrayList<>(recipients.size());
        int n = 0;
        for (Recipient r : recipients) {
            NotificationMessage m = new NotificationMessage();
            m.setId(UUID.randomUUID());
            m.setRequestId(request.getId());
            m.setClientId(request.getClientId());
            m.setChannel(request.getChannel());
            m.setRecipient(r.address());
            m.setVariables(r.variables() == null ? new HashMap<>() : new HashMap<>(r.variables()));
            m.setStatus(MessageStatus.PENDING);
            m.setCreatedAt(now);
            m.setUpdatedAt(now);
            em.persist(m);
            out.add(m);
            if (++n % FLUSH_EVERY == 0) {
                em.flush();
                em.clear();
            }
        }
        return out;
    }
}
