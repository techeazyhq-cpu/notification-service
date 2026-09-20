package com.techeazy.notification.adminapi;

import com.techeazy.notification.application.OutboxPublisher;
import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.domain.NotificationMessage;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Cross-client message search and manual re-queue of FAILED messages. */
@RestController
@RequestMapping("/api/admin/messages")
class MessagesController {

    record Row(UUID id, UUID requestId, UUID clientId, String clientName, String channel, String recipient,
               String status, int attempts, String lastError, String providerMessageId, Instant createdAt, Instant sentAt) {}

    record Page(List<Row> items, int page, int size, long totalItems) {}

    private final JdbcTemplate jdbc;
    private final NotificationMessageRepository messages;
    private final OutboxPublisher outbox;

    MessagesController(JdbcTemplate jdbc, NotificationMessageRepository messages, OutboxPublisher outbox) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.outbox = outbox;
    }

    @GetMapping
    Page search(@RequestParam(required = false) String status, @RequestParam(required = false) String channel,
                @RequestParam(required = false) UUID clientId, @RequestParam(required = false) UUID requestId,
                @RequestParam(required = false) String recipient,
                @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        int s = Math.min(Math.max(size, 1), 200);
        int p = Math.max(page, 0);
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) { where.append(" and m.status = ?"); args.add(status); }
        if (channel != null && !channel.isBlank()) { where.append(" and m.channel = ?"); args.add(channel); }
        if (clientId != null) { where.append(" and m.client_id = ?"); args.add(clientId); }
        if (requestId != null) { where.append(" and m.request_id = ?"); args.add(requestId); }
        if (recipient != null && !recipient.isBlank()) { where.append(" and m.recipient ilike ?"); args.add("%" + recipient.trim() + "%"); }

        Long total = jdbc.queryForObject("select count(*) from notification_message m" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(p * s);
        List<Row> rows = jdbc.query("""
                select m.id, m.request_id, m.client_id, c.name, m.channel, m.recipient, m.status, m.attempts,
                       m.last_error, m.provider_message_id, m.created_at, m.sent_at
                from notification_message m join client c on c.id = m.client_id""" + where
                        + " order by m.created_at desc limit ? offset ?",
                (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8), rs.getString(9),
                        rs.getString(10), rs.getTimestamp(11).toInstant(),
                        rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toInstant()),
                pageArgs.toArray());
        return new Page(rows, p, s, total == null ? 0 : total);
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
}
