package com.techeazy.notification.adminapi;

import com.techeazy.notification.application.OutboxPublisher;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.Client;
import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.domain.NotificationMessage;
import com.techeazy.notification.persistence.ClientRepository;
import com.techeazy.notification.persistence.LikePatterns;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Cross-client message search and manual re-queue of FAILED messages. */
@RestController
@RequestMapping("/api/admin/messages")
class MessagesController {

    record Row(UUID id, UUID requestId, UUID clientId, String clientName, Channel channel, String recipient,
               MessageStatus status, int attempts, String lastError, String providerMessageId, Instant createdAt,
               Instant sentAt) {}

    record Page(List<Row> items, int page, int size, long totalItems) {}

    private final NotificationMessageRepository messages;
    private final ClientRepository clients;
    private final OutboxPublisher outbox;

    MessagesController(NotificationMessageRepository messages, ClientRepository clients, OutboxPublisher outbox) {
        this.messages = messages;
        this.clients = clients;
        this.outbox = outbox;
    }

    @GetMapping
    Page search(@RequestParam(required = false) MessageStatus status, @RequestParam(required = false) Channel channel,
                @RequestParam(required = false) UUID clientId, @RequestParam(required = false) UUID requestId,
                @RequestParam(required = false) String recipient,
                @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        int pageSize = Math.clamp(size, 1, 200);
        int pageNo = Math.max(page, 0);

        Specification<NotificationMessage> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (status != null) where.add(cb.equal(root.get("status"), status));
            if (channel != null) where.add(cb.equal(root.get("channel"), channel));
            if (clientId != null) where.add(cb.equal(root.get("clientId"), clientId));
            if (requestId != null) where.add(cb.equal(root.get("requestId"), requestId));
            if (recipient != null && !recipient.isBlank()) {
                where.add(cb.like(cb.lower(root.get("recipient")), LikePatterns.contains(recipient), LikePatterns.ESCAPE));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };

        var result = messages.findAll(spec, PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<UUID, String> names = clients.findAllById(result.getContent().stream().map(NotificationMessage::getClientId).distinct().toList())
                .stream().collect(Collectors.toMap(Client::getId, Client::getName, (a, b) -> a));
        List<Row> rows = result.getContent().stream().map(m -> toRow(m, names)).toList();
        return new Page(rows, pageNo, pageSize, result.getTotalElements());
    }

    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void retry(@PathVariable UUID id) {
        NotificationMessage m = messages.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (m.getStatus() != MessageStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only FAILED messages can be retried (status is " + m.getStatus() + ")");
        }
        if (messages.requeueFailed(id, Instant.now()) == 1) {
            m.setStatus(MessageStatus.PENDING);
            outbox.publishAndMarkQueued(List.of(m)); // if the broker is down it stays PENDING for the sweeper
        }
    }

    private static Row toRow(NotificationMessage m, Map<UUID, String> clientNames) {
        return new Row(m.getId(), m.getRequestId(), m.getClientId(), clientNames.getOrDefault(m.getClientId(), "unknown"),
                m.getChannel(), m.getRecipient(), m.getStatus(), m.getAttempts(), m.getLastError(),
                m.getProviderMessageId(), m.getCreatedAt(), m.getSentAt());
    }
}
