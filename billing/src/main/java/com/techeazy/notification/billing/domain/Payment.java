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

package com.techeazy.notification.billing.domain;

import java.time.Instant;
import java.util.UUID;

/** A payment received against an invoice, recorded by an administrator. The reference makes recording idempotent. */
public record Payment(UUID id, Money amount, String method, String reference, Instant receivedAt) {

    public Payment {
        if (!amount.isPositive()) {
            throw new InvalidBillingDataException("Payment amount must be positive");
        }
        if (method == null || method.isBlank() || reference == null || reference.isBlank()) {
            throw new InvalidBillingDataException("Payment method and reference are required");
        }
    }
}
