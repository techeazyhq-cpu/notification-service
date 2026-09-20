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

/**
 * One line of the append-only credit ledger. The signed sum of a client's entries always equals its credit balance,
 * and the reference makes each entry idempotent per client and type.
 */
public record LedgerEntry(UUID id, UUID clientId, LedgerEntryType type, Money amount, String reference,
                          String description, Instant createdAt) {

    public static LedgerEntry of(UUID clientId, LedgerEntryType type, Money amount, String reference,
                                 String description, Instant now) {
        return new LedgerEntry(UUID.randomUUID(), clientId, type, amount, reference, description, now);
    }
}
