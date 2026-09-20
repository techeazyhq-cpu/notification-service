package com.techeazy.notification.clientapi;

import com.techeazy.notification.clientapi.Dtos.*;
import com.techeazy.notification.clientapi.IngestPersister.Recipient;
import com.techeazy.notification.clientapi.IngestService.SubmitCommand;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.domain.RequestKind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/v1/notifications")
@Tag(name = "Notifications")
public class NotificationController {

    private static final int EXPORT_PAGE_SIZE = 200;

    private final IngestService ingest;
    private final StatusQueryService status;

    public NotificationController(IngestService ingest, StatusQueryService status) {
        this.ingest = ingest;
        this.status = status;
    }

    @Operation(summary = "Send a single notification",
            description = "Returns 202 once the request is durably accepted. Use the status endpoints to follow delivery. "
                    + "Send an Idempotency-Key header to make retries safe.")
    @PostMapping
    public ResponseEntity<SubmitResponse> send(
            @RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SendRequest req) {
        var recipients = List.of(new Recipient(req.recipient(), req.variables()));
        return respond(ingest.submit(client, new SubmitCommand(RequestKind.SINGLE, req.channel(), req.templateName(),
                req.subject(), req.body(), recipients, req.clientReference(), idempotencyKey)));
    }

    @Operation(summary = "Send to many recipients (JSON body)",
            description = "Each recipient may carry its own template variables.")
    @PostMapping("/bulk")
    public ResponseEntity<SubmitResponse> bulk(
            @RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody BulkRequest req) {
        List<Recipient> recipients = new ArrayList<>(req.recipients().size());
        req.recipients().forEach(r -> recipients.add(new Recipient(r.recipient(), r.variables())));
        return respond(ingest.submit(client, new SubmitCommand(RequestKind.BULK, req.channel(), req.templateName(),
                req.subject(), req.body(), recipients, req.clientReference(), idempotencyKey)));
    }

    @Operation(summary = "Send to many recipients (CSV upload)",
            description = "CSV with a header row. The 'recipient' column is the address; other columns become template variables.")
    @PostMapping(value = "/bulk/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SubmitResponse> bulkUpload(
            @RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestPart("file") MultipartFile file,
            @Parameter(description = "EMAIL, SMS, WHATSAPP or PUSH") @RequestParam Channel channel,
            @RequestParam(required = false) String templateName,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String body,
            @RequestParam(required = false) String clientReference) throws IOException {
        if (file.isEmpty()) throw ApiException.badRequest("file is empty");
        List<Recipient> recipients = CsvRecipientParser.parse(file.getInputStream(), ingest.maxBulkRecipients()).stream()
                .map(r -> new Recipient(r.recipient(), r.variables())).toList();
        if (recipients.isEmpty()) throw ApiException.badRequest("CSV contains no recipients");
        return respond(ingest.submit(client, new SubmitCommand(RequestKind.BULK, channel, templateName, subject, body,
                recipients, clientReference, idempotencyKey)));
    }

    @Operation(summary = "Get request status with per-status message counts")
    @GetMapping("/{requestId}")
    public RequestView get(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client, @PathVariable UUID requestId) {
        return status.get(client.id(), requestId);
    }

    @Operation(summary = "List per-recipient messages of a request",
            description = "Filter by status and/or recipient (contains, case-insensitive); paged.")
    @GetMapping("/{requestId}/messages")
    public PageView<MessageView> messages(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
                                          @PathVariable UUID requestId,
                                          @RequestParam(required = false) MessageStatus status,
                                          @RequestParam(required = false) String recipient,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        return this.status.messages(client.id(), requestId, status, recipient, page, size);
    }

    @Operation(summary = "Download a request's recipients as CSV",
            description = "Optionally only one status, e.g. status=FAILED to get the recipients to re-send to.")
    @GetMapping(value = "/{requestId}/messages/export", produces = "text/csv")
    public void export(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
                       @PathVariable UUID requestId,
                       @RequestParam(required = false) MessageStatus status,
                       HttpServletResponse response) throws IOException {
        this.status.get(client.id(), requestId); // 404 before any bytes are written
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"request-" + requestId
                + (status == null ? "" : "-" + status.name().toLowerCase(Locale.ROOT)) + ".csv\"");
        try (CSVPrinter out = new CSVPrinter(response.getWriter(), CSVFormat.DEFAULT)) {
            out.printRecord(MessageCsv.HEADER);
            int page = 0;
            PageView<MessageView> current;
            do {
                current = this.status.messages(client.id(), requestId, status, null, page++, EXPORT_PAGE_SIZE);
                for (MessageView m : current.items()) out.printRecord(MessageCsv.row(m));
            } while (page < current.totalPages());
        }
    }

    @Operation(summary = "List your recent requests",
            description = "Newest first. Filter by channel and/or clientReference (contains, case-insensitive).")
    @GetMapping
    public PageView<RequestView> list(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
                                      @RequestParam(required = false) Channel channel,
                                      @RequestParam(required = false) String clientReference,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return status.list(client.id(), channel, clientReference, page, size);
    }

    @Operation(summary = "Message counts per channel and status over the last N hours (default 24, max 720)")
    @GetMapping("/summary")
    public SummaryView summary(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
                               @RequestParam(defaultValue = "24") int hours) {
        return status.summary(client.id(), hours);
    }

    private static ResponseEntity<SubmitResponse> respond(SubmitResponse r) {
        return ResponseEntity.status(r.idempotentReplay() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .header("Location", "/v1/notifications/" + r.requestId())
                .body(r);
    }
}
