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

import com.techeazy.notification.domain.Channel;

/**
 * One charge on an invoice. For usage lines the quantity is the billable messages after the free allowance;
 * the channel is empty for the platform fee.
 */
public record InvoiceLine(InvoiceLineKind kind, Channel channel, String description, long quantity,
                          Money unitPrice, Money amount) {

    public InvoiceLine {
        if (quantity < 0) {
            throw new InvalidBillingDataException("Quantity cannot be negative");
        }
    }
}
