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

package com.techeazy.notification.clientapi;

import com.techeazy.notification.application.RetentionService;
import com.techeazy.notification.config.RetentionProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** How long recipient data is kept, and erasure of a recipient's data on request. */
@RestController
@RequestMapping("/v1/privacy")
@Tag(name = "Privacy", description = "Retention of recipient data and erasure of a recipient's data.")
public class PrivacyController {

    public record ErasureRequest(@NotBlank @Size(max = 320) String recipient) {}

    public record ErasureResult(long erasedMessages, long inFlightMessages, String note) {}

    public record RetentionView(int personalDataDays, int deleteDays, int idempotencyDays) {}

    /** {@code from} and {@code to} are inclusive UTC dates of acceptance; leave either out for no bound. */
    public record RecipientReportRequest(@NotBlank @Size(max = 320) String recipient, LocalDate from, LocalDate to) {}

    private final RetentionService retention;
    private final RecipientActivityReport activityReport;

    public PrivacyController(RetentionService retention, RecipientActivityReport activityReport) {
        this.retention = retention;
        this.activityReport = activityReport;
    }

    /**
     * POST rather than GET, so the recipient's address travels in the body and never in a URL that access logs and
     * proxies record.
     */
    @PostMapping(value = "/recipient-report", produces = "text/csv")
    @Operation(summary = "Download everything you sent to one recipient, as CSV",
            description = "For answering a complaint to a data protection authority: one row per event of every message "
                    + "you sent to the e-mail address, phone number or device token, with UTC timestamps, the category, "
                    + "template and your reference, and how delivery went. Messages are found even after the "
                    + "recipient's data was erased. The message text is never included.")
    public void recipientReport(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
                                @Valid @RequestBody RecipientReportRequest request,
                                HttpServletResponse response) throws IOException {
        if (request.from() != null && request.to() != null && request.from().isAfter(request.to())) {
            throw ApiException.badRequest("'from' must not be after 'to'");
        }
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"recipient-activity-"
                + LocalDate.now(ZoneOffset.UTC) + ".csv\"");
        activityReport.write(client.id(), request.recipient().strip(), request.from(), request.to(),
                response.getWriter());
    }

    @GetMapping("/retention")
    @Operation(summary = "Retention policy", description = "Days after which recipient data is erased, finished message records are deleted, and idempotency keys stop working. 0 means never.")
    public RetentionView policy() {
        RetentionProperties p = retention.policy();
        return new RetentionView(Math.max(p.getPersonalDataDays(), 0), Math.max(p.getDeleteDays(), 0), Math.max(p.getIdempotencyDays(), 0));
    }

    @PostMapping("/erasure")
    @Operation(summary = "Erase a recipient's data",
            description = "Removes the recipient address, template variables and error text from every finished message you sent to that recipient "
                    + "(case-insensitive). A message still being delivered is left and counted in inFlightMessages: repeat the request once delivery has finished. "
                    + "Erased messages keep their status and counts but can no longer be retried.")
    public ErasureResult erase(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
                               @Valid @RequestBody ErasureRequest request) {
        RetentionService.Erasure result = retention.eraseRecipient(client.id(), request.recipient().trim(), Instant.now());
        String note = result.inFlightMessages() > 0
                ? "Some messages are still being delivered; repeat this request after they finish."
                : "Done.";
        return new ErasureResult(result.erasedMessages(), result.inFlightMessages(), note);
    }
}
