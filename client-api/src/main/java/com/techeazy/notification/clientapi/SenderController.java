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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The client's own e-mail sender addresses: register, confirm by e-mail, choose a default, remove. */
@RestController
@RequestMapping("/v1/senders")
@Tag(name = "Sender addresses", description = "E-mail addresses your messages may be sent from. An address works only after you confirm it.")
public class SenderController {

    public record SenderInput(@NotBlank @Size(max = 254) String email, @Size(max = 120) String displayName) {}

    public record SenderView(UUID id, String email, String displayName, String status, boolean isDefault,
                             Instant createdAt, Instant verifiedAt, Instant verificationSentAt) {

        static SenderView of(SenderAddress s) {
            return new SenderView(s.id(), s.email(), s.displayName(), s.status().name(), s.isDefault(), s.createdAt(),
                    s.verifiedAt(), s.verificationSentAt());
        }
    }

    private final SenderService senders;

    public SenderController(SenderService senders) {
        this.senders = senders;
    }

    @GetMapping
    @Operation(summary = "List sender addresses")
    public List<SenderView> list(HttpServletRequest request) {
        return senders.list(client(request)).stream().map(SenderView::of).toList();
    }

    @PostMapping
    @Operation(summary = "Register a sender address", description = "Sends a confirmation link to the address. Until it is confirmed the address cannot be used.")
    public ResponseEntity<SenderView> add(HttpServletRequest request, @Valid @RequestBody SenderInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(SenderView.of(senders.add(client(request), input.email(), input.displayName())));
    }

    @PostMapping("/{id}/resend")
    @Operation(summary = "Send the confirmation e-mail again", description = "At most once a minute.")
    public SenderView resend(HttpServletRequest request, @PathVariable UUID id) {
        return SenderView.of(senders.resend(client(request), id));
    }

    @PutMapping("/{id}/default")
    @Operation(summary = "Use this address when a request gives no from", description = "Only a confirmed address can be the default.")
    public SenderView makeDefault(HttpServletRequest request, @PathVariable UUID id) {
        return SenderView.of(senders.makeDefault(client(request), id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a sender address")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable UUID id) {
        senders.delete(client(request), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/verify", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "Confirm a sender address", description = "The link in the confirmation e-mail. It needs no API key.")
    public ResponseEntity<String> verify(@RequestParam String token) {
        return senders.verify(token)
                .map(s -> page(HttpStatus.OK, "Address confirmed", escape(s.email()) + " can now be used as a sender."))
                .orElseGet(() -> page(HttpStatus.BAD_REQUEST, "Link not valid",
                        "This link was already used, has expired, or is not valid. Ask for a new confirmation e-mail."));
    }

    private static ResponseEntity<String> page(HttpStatus status, String title, String message) {
        String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>" + title
                + "</title><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>"
                + "<body style=\"font-family:system-ui,sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem\"><h1>" + title
                + "</h1><p>" + message + "</p></body></html>";
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer").body(html);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static AuthenticatedClient client(HttpServletRequest request) {
        return (AuthenticatedClient) request.getAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE);
    }
}
