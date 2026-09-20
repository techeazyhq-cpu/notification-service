package com.techeazy.notification.clientapi;

import com.techeazy.notification.application.OutboxPublisher;
import com.techeazy.notification.application.TemplateRenderer;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        Content content = resolveContent(client, channel, cmd.templateName(), cmd.subject(), cmd.body());
        validateRecipients(channel, recipients);
        validateVariables(content, recipients);

        if (idempotencyKey != null) {
            Optional<NotificationRequest> existing = requests.findByClientIdAndIdempotencyKey(client.id(), idempotencyKey);
            if (existing.isPresent()) return replay(existing.get());
        }

        NotificationRequest request = new NotificationRequest();
        request.setId(UUID.randomUUID());
        request.setClientId(client.id());
        request.setKind(kind);
        request.setChannel(channel);
        // Snapshot of the content as accepted: later template edits or deletes never touch this request.
        request.setTemplateId(content.templateId());
        request.setSubject(content.subject());
        request.setBody(content.body());
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

    /** The content a request will be sent with, and the template it came from (null for inline content). */
    record Content(UUID templateId, String subject, String body) {}

    /** The client's own template wins over a shared one of the same name. */
    private Content resolveContent(AuthenticatedClient client, Channel channel, String templateName, String subject, String body) {
        if (templateName != null && !templateName.isBlank()) {
            Template t = templates.findByClientIdAndName(client.id(), templateName)
                    .or(() -> templates.findByClientIdIsNullAndName(templateName))
                    .orElseThrow(() -> ApiException.badRequest("Unknown template '" + templateName + "'"));
            if (t.getChannel() != channel) {
                throw ApiException.badRequest("Template '" + templateName + "' is for channel " + t.getChannel() + ", not " + channel);
            }
            return new Content(t.getId(), t.getSubject(), t.getBody());
        }
        if (body == null || body.isBlank()) throw ApiException.badRequest("Provide either templateName or body");
        if (channel == Channel.EMAIL && (subject == null || subject.isBlank())) {
            throw ApiException.badRequest("subject is required for inline EMAIL content");
        }
        return new Content(null, subject, body);
    }

    /**
     * Fails the whole request up front if any recipient lacks a variable the content uses, instead of letting those
     * messages fail one by one later. {{recipient}} is built in.
     */
    private void validateVariables(Content content, List<Recipient> recipients) {
        Set<String> required = TemplateRenderer.requiredVariables(content.subject(), content.body());
        if (required.isEmpty()) return;
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < recipients.size() && problems.size() < 10; i++) {
            Map<String, String> vars = recipients.get(i).variables();
            List<String> missing = required.stream().filter(v -> vars == null || vars.get(v) == null).toList();
            if (!missing.isEmpty()) problems.add("recipient #" + (i + 1) + " is missing " + String.join(", ", missing));
        }
        if (!problems.isEmpty()) {
            throw ApiException.badRequest("Missing template variables (first " + problems.size() + "): " + String.join("; ", problems));
        }
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
