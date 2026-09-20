package com.techeazy.notification.clientapi;

import com.techeazy.notification.clientapi.Dtos.*;
import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.domain.NotificationMessage;
import com.techeazy.notification.domain.NotificationRequest;
import com.techeazy.notification.domain.RequestStatus;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import com.techeazy.notification.persistence.NotificationRequestRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class StatusQueryService {

    private static final int MAX_PAGE_SIZE = 200;

    private final NotificationRequestRepository requests;
    private final NotificationMessageRepository messages;

    public StatusQueryService(NotificationRequestRepository requests, NotificationMessageRepository messages) {
        this.requests = requests;
        this.messages = messages;
    }

    public RequestView get(UUID clientId, UUID requestId) {
        NotificationRequest r = requests.findByIdAndClientId(requestId, clientId)
                .orElseThrow(() -> ApiException.notFound("Request " + requestId + " not found"));
        return view(r);
    }

    public PageView<RequestView> list(UUID clientId, int page, int size) {
        Page<NotificationRequest> p = requests.findByClientIdOrderByCreatedAtDesc(clientId, pageRequest(page, size, null));
        return new PageView<>(p.map(this::view).getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    public PageView<MessageView> messages(UUID clientId, UUID requestId, MessageStatus status, int page, int size) {
        requests.findByIdAndClientId(requestId, clientId)
                .orElseThrow(() -> ApiException.notFound("Request " + requestId + " not found"));
        var pr = pageRequest(page, size, Sort.by("createdAt", "id"));
        Page<NotificationMessage> p = status == null
                ? messages.findByRequestId(requestId, pr)
                : messages.findByRequestIdAndStatus(requestId, status, pr);
        return new PageView<>(p.map(StatusQueryService::toView).getContent(), p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages());
    }

    RequestView view(NotificationRequest r) {
        Map<MessageStatus, Long> by = new EnumMap<>(MessageStatus.class);
        for (Object[] row : messages.countByStatus(r.getId())) {
            by.put((MessageStatus) row[0], (Long) row[1]);
        }
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
        int s = Math.clamp(size, 1, MAX_PAGE_SIZE);
        PageRequest pr = PageRequest.of(Math.max(page, 0), s);
        return sort == null ? pr : pr.withSort(sort);
    }
}
