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

import com.techeazy.notification.application.RetentionService;
import com.techeazy.notification.config.RetentionProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Retention policy and data-subject erasure for operators, across all clients or for one. */
@RestController
@RequestMapping("/api/admin/privacy")
class PrivacyAdminController {

    record ErasureRequest(@NotBlank @Size(max = 320) String recipient, UUID clientId) {}

    record ErasureResult(long erasedMessages, long inFlightMessages) {}

    record RetentionView(int personalDataDays, int deleteDays, int idempotencyDays, String cron) {}

    private final RetentionService retention;

    PrivacyAdminController(RetentionService retention) {
        this.retention = retention;
    }

    @GetMapping("/retention")
    RetentionView policy() {
        RetentionProperties p = retention.policy();
        return new RetentionView(Math.max(p.getPersonalDataDays(), 0), Math.max(p.getDeleteDays(), 0),
                Math.max(p.getIdempotencyDays(), 0), p.getCron());
    }

    @PostMapping("/erasure")
    ErasureResult erase(@Valid @RequestBody ErasureRequest request) {
        RetentionService.Erasure result = retention.eraseRecipient(request.clientId(), request.recipient().trim(), Instant.now());
        return new ErasureResult(result.erasedMessages(), result.inFlightMessages());
    }
}
