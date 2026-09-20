package com.techeazy.notification.clientapi;

import com.techeazy.notification.application.OutboxPublisher;
import com.techeazy.notification.clientapi.Dtos.SubmitResponse;
import com.techeazy.notification.clientapi.IngestPersister.Recipient;
import com.techeazy.notification.domain.*;
import com.techeazy.notification.persistence.NotificationRequestRepository;
import com.techeazy.notification.persistence.TemplateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accept path: validate, persist as PENDING in one transaction, publish to Pulsar, flip to QUEUED.
 * The API answers 202 once the request is durable; delivery outcome is read via the status API.
 */
@Service
public class IngestService {

    private final TemplateRepository templates;
    private final NotificationRequestRepository requests;
    private final IngestPersister persister;
    private final OutboxPublisher outbox;
    private final StatusQueryService status;
    private final int maxBulkRecipients;

    public IngestService(TemplateRepository templates, NotificationRequestRepository requests, IngestPersister persister,
                         OutboxPublisher outbox, StatusQueryService status,
                         @Value("${client-api.max-bulk-recipients:50000}") int maxBulkRecipients) {
        this.templates = templates;
        this.requests = requests;
        this.persister = persister;
        this.outbox = outbox;
        this.status = status;
        this.maxBulkRecipients = maxBulkRecipients;
    }

    public int maxBulkRecipients() {
        return maxBulkRecipients;
    }

    /** Everything a caller supplies for one submission; {@code idempotencyKey} and content fields may be null. */
    public record SubmitCommand(RequestKind kind, Channel channel, String templateName, String subject, String body,
                                List<Recipient> recipients, String clientReference, String idempotencyKey) {}

    public SubmitResponse submit(AuthenticatedClient client, SubmitCommand cmd) {
        RequestKind kind = cmd.kind();
        Channel channel = cmd.channel();
        List<Recipient> recipients = cmd.recipients();
        String idempotencyKey = cmd.idempotencyKey();
        if (!client.allows(channel)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CHANNEL_NOT_ALLOWED", "Client is not allowed to use channel " + channel);
        }
        if (recipients.size() > maxBulkRecipients) {
            throw ApiException.badRequest("At most " + maxBulkRecipients + " recipients per request");
        }
        Template template = resolveContent(channel, cmd.templateName(), cmd.subject(), cmd.body());
        validateRecipients(channel, recipients);

        if (idempotencyKey != null) {
            Optional<NotificationRequest> existing = requests.findByClientIdAndIdempotencyKey(client.id(), idempotencyKey);
            if (existing.isPresent()) return replay(existing.get());
        }

        NotificationRequest request = new NotificationRequest();
        request.setId(UUID.randomUUID());
        request.setClientId(client.id());
        request.setKind(kind);
        request.setChannel(channel);
        request.setTemplateId(template == null ? null : template.getId());
        request.setSubject(template == null ? cmd.subject() : null);
        request.setBody(template == null ? cmd.body() : null);
        request.setTotal(recipients.size());
        request.setIdempotencyKey(idempotencyKey);
        request.setClientReference(cmd.clientReference());
        request.setCreatedAt(Instant.now());

        List<NotificationMessage> messages;
        try {
            messages = persister.persist(request, recipients);
        } catch (DataIntegrityViolationException e) {
            // Concurrent submit with the same Idempotency-Key lost the race on the unique index.
            if (idempotencyKey != null) {
                Optional<NotificationRequest> existing = requests.findByClientIdAndIdempotencyKey(client.id(), idempotencyKey);
                if (existing.isPresent()) return replay(existing.get());
            }
            throw e;
        }

        outbox.publishAndMarkQueued(messages); // failures stay PENDING; the sweeper retries them

        List<UUID> ids = kind == RequestKind.SINGLE ? messages.stream().map(NotificationMessage::getId).toList() : null;
        return new SubmitResponse(request.getId(), kind, RequestStatus.PROCESSING, request.getTotal(), ids, false, request.getCreatedAt());
    }

    private SubmitResponse replay(NotificationRequest existing) {
        var view = status.view(existing);
        return new SubmitResponse(existing.getId(), existing.getKind(), view.status(), existing.getTotal(), null, true, existing.getCreatedAt());
    }

    /** @return the template when one is used, or null for inline content */
    private Template resolveContent(Channel channel, String templateName, String subject, String body) {
        if (templateName != null && !templateName.isBlank()) {
            Template t = templates.findByName(templateName)
                    .orElseThrow(() -> ApiException.badRequest("Unknown template '" + templateName + "'"));
            if (t.getChannel() != channel) {
                throw ApiException.badRequest("Template '" + templateName + "' is for channel " + t.getChannel() + ", not " + channel);
            }
            return t;
        }
        if (body == null || body.isBlank()) throw ApiException.badRequest("Provide either templateName or body");
        if (channel == Channel.EMAIL && (subject == null || subject.isBlank())) {
            throw ApiException.badRequest("subject is required for inline EMAIL content");
        }
        return null;
    }

    private void validateRecipients(Channel channel, List<Recipient> recipients) {
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < recipients.size() && problems.size() < 10; i++) {
            String reason = RecipientValidator.check(channel, recipients.get(i).address());
            if (reason != null) problems.add("recipient #" + (i + 1) + ": " + reason);
        }
        if (!problems.isEmpty()) {
            throw ApiException.badRequest("Invalid recipients (first " + problems.size() + "): " + String.join("; ", problems));
        }
    }
}
