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
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.Client;
import com.techeazy.notification.domain.FailureKind;
import com.techeazy.notification.domain.NotificationMessage;
import com.techeazy.notification.persistence.ClientRepository;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The dead-letter queue: every message that ended FAILED, why, and bulk reprocessing. Failed messages come from the
 * dispatcher (permanent errors and exhausted attempts) and from the broker's own dead-letter topics.
 */
@RestController
@RequestMapping("/api/admin/dead-letters")
class DeadLetterController {

    record Row(UUID id, UUID requestId, UUID clientId, String clientName, Channel channel, String recipient, FailureKind kind,
               boolean retryable, boolean erased, int attempts, int reprocessCount, String lastError, Instant failedAt, Instant createdAt) {}

    record Page(List<Row> items, int page, int size, long totalItems) {}

    record Count(String key, long count) {}

    record Summary(long total, List<Count> byKind, List<Count> byChannel, List<Count> byClient, List<Count> topErrors, Instant oldest) {}

    record ReprocessRequest(List<UUID> ids, UUID clientId, Channel channel, FailureKind kind, String errorContains,
                            Boolean includePermanent, Integer limit) {}

    private final NotificationMessageRepository messages;
    private final ClientRepository clients;
    private final DeadLetterReprocessor reprocessor;
    private final JdbcClient jdbc;

    DeadLetterController(NotificationMessageRepository messages, ClientRepository clients, DeadLetterReprocessor reprocessor, JdbcClient jdbc) {
        this.messages = messages;
        this.clients = clients;
        this.reprocessor = reprocessor;
        this.jdbc = jdbc;
    }

    @GetMapping
    Page list(@RequestParam(required = false) UUID clientId, @RequestParam(required = false) Channel channel,
              @RequestParam(required = false) FailureKind kind, @RequestParam(required = false) String q,
              @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        int pageSize = Math.clamp(size, 1, 200);
        int pageNo = Math.max(page, 0);
        var result = messages.findAll(new DeadLetterFilter(clientId, channel, kind, q, false).viewSpecification(),
                PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "updatedAt")));
        Map<UUID, String> names = clients.findAllById(result.getContent().stream().map(NotificationMessage::getClientId).distinct().toList())
                .stream().collect(Collectors.toMap(Client::getId, Client::getName, (a, b) -> a));
        List<Row> rows = result.getContent().stream().map(m -> toRow(m, names)).toList();
        return new Page(rows, pageNo, pageSize, result.getTotalElements());
    }

    @GetMapping("/summary")
    Summary summary() {
        long total = jdbc.sql("SELECT count(*) FROM notification_message WHERE status = 'FAILED'").query(Long.class).single();
        Instant oldest = jdbc.sql("SELECT min(updated_at) FROM notification_message WHERE status = 'FAILED'")
                .query((rs, row) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant()).single();
        return new Summary(total,
                counts("SELECT coalesce(failure_kind, 'UNKNOWN') k, count(*) c FROM notification_message WHERE status = 'FAILED' GROUP BY 1 ORDER BY 2 DESC"),
                counts("SELECT channel k, count(*) c FROM notification_message WHERE status = 'FAILED' GROUP BY 1 ORDER BY 2 DESC"),
                counts("SELECT coalesce(c.name, 'unknown') k, count(*) c FROM notification_message m LEFT JOIN client c ON c.id = m.client_id "
                        + "WHERE m.status = 'FAILED' GROUP BY 1 ORDER BY 2 DESC LIMIT 10"),
                counts("SELECT left(coalesce(last_error, 'no error text'), 80) k, count(*) c FROM notification_message WHERE status = 'FAILED' "
                        + "AND erased_at IS NULL GROUP BY 1 ORDER BY 2 DESC LIMIT 10"),
                oldest);
    }

    /**
     * Re-queues failed messages: the given {@code ids}, or up to {@code limit} (default 200, at most 1000) that match the
     * filters, oldest first. Messages that failed permanently are skipped unless {@code includePermanent} is true or they
     * are named in {@code ids}. Refusals (insufficient credit, erased data, changed state) are listed, not fatal.
     */
    @PostMapping("/reprocess")
    DeadLetterReprocessor.Result reprocess(@RequestBody ReprocessRequest request) {
        DeadLetterFilter filter = new DeadLetterFilter(request.clientId(), request.channel(), request.kind(), request.errorContains(),
                !Boolean.TRUE.equals(request.includePermanent()));
        return reprocessor.reprocess(request.ids(), filter, request.limit());
    }

    private List<Count> counts(String sql) {
        return jdbc.sql(sql).query((rs, row) -> new Count(rs.getString("k"), rs.getLong("c"))).list();
    }

    private static Row toRow(NotificationMessage m, Map<UUID, String> clientNames) {
        boolean erased = PersonalData.isErased(m.getRecipient());
        FailureKind kind = m.getFailureKind();
        return new Row(m.getId(), m.getRequestId(), m.getClientId(), clientNames.getOrDefault(m.getClientId(), "unknown"), m.getChannel(),
                m.getRecipient(), kind, !erased && (kind == null || kind.retryable()), erased, m.getAttempts(), m.getReprocessCount(),
                m.getLastError(), m.getUpdatedAt(), m.getCreatedAt());
    }
}
