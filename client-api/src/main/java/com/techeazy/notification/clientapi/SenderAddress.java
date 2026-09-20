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

import java.time.Instant;
import java.util.UUID;

/** A client's own e-mail sender address. Only a {@code VERIFIED} address can appear in the From header of a message. */
public record SenderAddress(UUID id, UUID clientId, String email, String displayName, Status status, boolean isDefault,
                            Instant createdAt, Instant verifiedAt, Instant verificationSentAt) {

    public enum Status { PENDING, VERIFIED }

    public boolean verified() {
        return status == Status.VERIFIED;
    }
}
