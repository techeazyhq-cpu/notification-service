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
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** How long recipient data is kept, and erasure of a recipient's data on request. */
@RestController
@RequestMapping("/v1/privacy")
@Tag(name = "Privacy", description = "Retention of recipient data and erasure of a recipient's data.")
public class PrivacyController {

    public record ErasureRequest(@NotBlank @Size(max = 320) String recipient) {}

    public record ErasureResult(long erasedMessages, long inFlightMessages, String note) {}

    public record RetentionView(int personalDataDays, int deleteDays, int idempotencyDays) {}

    private final RetentionService retention;

    public PrivacyController(RetentionService retention) {
        this.retention = retention;
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
