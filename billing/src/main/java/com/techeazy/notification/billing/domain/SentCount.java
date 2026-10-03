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

/**
 * Messages sent on one channel in a period, split into one-time passwords and everything else, because a tenant may
 * pay a price of its own for one-time passwords (ADR-034).
 */
public record SentCount(long ordinary, long oneTimePasswords) {

    public SentCount {
        if (ordinary < 0 || oneTimePasswords < 0) {
            throw new InvalidBillingDataException("A sent count cannot be negative");
        }
    }

    public static SentCount ordinary(long sent) {
        return new SentCount(sent, 0);
    }

    public long total() {
        return ordinary + oneTimePasswords;
    }

    public SentCount plus(SentCount other) {
        return new SentCount(ordinary + other.ordinary, oneTimePasswords + other.oneTimePasswords);
    }
}
