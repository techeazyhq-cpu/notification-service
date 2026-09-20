package com.techeazy.notification.clientapi;

import com.techeazy.notification.clientapi.Dtos.*;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.domain.NotificationMessage;
import com.techeazy.notification.domain.NotificationRequest;
import com.techeazy.notification.domain.RequestStatus;
import com.techeazy.notification.persistence.LikePatterns;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import com.techeazy.notification.persistence.NotificationRequestRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read side of the Client API. Every query is scoped to the calling client. */
@Service
@Transactional(readOnly = true)
public class StatusQueryService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_SUMMARY_HOURS = 24 * 30;

    private final NotificationRequestRepository requests;
    private final NotificationMessageRepository messages;

    public StatusQueryService(NotificationRequestRepository requests, NotificationMessageRepository messages) {
        this.requests = requests;
        this.messages = messages;
    }

    public RequestView get(UUID clientId, UUID requestId) {
        return view(findOwned(clientId, requestId));
    }

    /** Newest first. {@code clientReference} is a case-insensitive "contains" match. */
    public PageView<RequestView> list(UUID clientId, Channel channel, String clientReference, int page, int size) {
        Specification<NotificationRequest> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("clientId"), clientId));
            if (channel != null) where.add(cb.equal(root.get("channel"), channel));
            if (clientReference != null && !clientReference.isBlank()) {
                where.add(cb.like(cb.lower(root.get("clientReference")), LikePatterns.contains(clientReference), LikePatterns.ESCAPE));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
        Page<NotificationRequest> p = requests.findAll(spec, pageRequest(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<UUID, Map<MessageStatus, Long>> counts = countsFor(p.getContent().stream().map(NotificationRequest::getId).toList());
        List<RequestView> items = p.getContent().stream()
                .map(r -> toView(r, counts.getOrDefault(r.getId(), Map.of()))).toList();
        return new PageView<>(items, p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    public PageView<MessageView> messages(UUID clientId, UUID requestId, MessageStatus status, String recipient,
                                          int page, int size) {
        findOwned(clientId, requestId); // 404 unless the request belongs to this client
        Specification<NotificationMessage> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("requestId"), requestId));
            if (status != null) where.add(cb.equal(root.get("status"), status));
            if (recipient != null && !recipient.isBlank()) {
                where.add(cb.like(cb.lower(root.get("recipient")), LikePatterns.contains(recipient), LikePatterns.ESCAPE));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
        Page<NotificationMessage> p = messages.findAll(spec, pageRequest(page, size, Sort.by("createdAt", "id")));
        return new PageView<>(p.map(StatusQueryService::toView).getContent(), p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages());
    }

    public SummaryView summary(UUID clientId, int hours) {
        int window = Math.clamp(hours, 1, MAX_SUMMARY_HOURS);
        Instant since = Instant.now().minus(window, ChronoUnit.HOURS);
        List<ChannelStatusCount> counts = messages.countByChannelAndStatus(clientId, since).stream()
                .map(r -> new ChannelStatusCount((Channel) r[0], (MessageStatus) r[1], (Long) r[2])).toList();
        return new SummaryView(window, requests.countByClientIdAndCreatedAtAfter(clientId, since), counts);
    }

    RequestView view(NotificationRequest r) {
        Map<MessageStatus, Long> by = new EnumMap<>(MessageStatus.class);
        for (Object[] row : messages.countByStatus(r.getId())) {
            by.put((MessageStatus) row[0], (Long) row[1]);
        }
        return toView(r, by);
    }

    private NotificationRequest findOwned(UUID clientId, UUID requestId) {
        return requests.findByIdAndClientId(requestId, clientId)
                .orElseThrow(() -> ApiException.notFound("Request " + requestId + " not found"));
    }

    /** One grouped query for a whole page of requests instead of one count per row. */
    private Map<UUID, Map<MessageStatus, Long>> countsFor(Collection<UUID> requestIds) {
        Map<UUID, Map<MessageStatus, Long>> result = new HashMap<>();
        if (requestIds.isEmpty()) return result;
        for (Object[] row : messages.countByStatusForRequests(requestIds)) {
            result.computeIfAbsent((UUID) row[0], k -> new EnumMap<>(MessageStatus.class)).put((MessageStatus) row[1], (Long) row[2]);
        }
        return result;
    }

    private static RequestView toView(NotificationRequest r, Map<MessageStatus, Long> by) {
        StatusCounts c = new StatusCounts(
                by.getOrDefault(MessageStatus.PENDING, 0L), by.getOrDefault(MessageStatus.QUEUED, 0L),
                by.getOrDefault(MessageStatus.PROCESSING, 0L), by.getOrDefault(MessageStatus.RETRYING, 0L),
                by.getOrDefault(MessageStatus.SENT, 0L), by.getOrDefault(MessageStatus.FAILED, 0L));
        long inFlight = c.pending() + c.queued() + c.processing() + c.retrying();
        RequestStatus status = RequestStatus.derive(r.getTotal(), inFlight, c.sent(), c.failed());
        return new RequestView(r.getId(), r.getKind(), r.getChannel(), status, r.getTotal(), c,
                r.getClientReference(), r.getCreatedAt());
    }

    private static MessageView toView(NotificationMessage m) {
        return new MessageView(m.getId(), m.getRecipient(), m.getStatus(), m.getAttempts(), m.getLastError(),
                m.getProviderMessageId(), m.getSentAt(), m.getUpdatedAt());
    }

    private static PageRequest pageRequest(int page, int size, Sort sort) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), sort);
    }
}
