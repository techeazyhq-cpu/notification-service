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

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BillingPeriodTest {

    private static Clock clockAt(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    @Test
    void parsesYearMonthAndRejectsGarbage() {
        assertThat(BillingPeriod.parse("2026-08").label()).isEqualTo("2026-08");
        assertThatThrownBy(() -> BillingPeriod.parse("08/2026")).isInstanceOf(InvalidBillingDataException.class);
        assertThatThrownBy(() -> BillingPeriod.parse(null)).isInstanceOf(InvalidBillingDataException.class);
    }

    @Test
    void spansTheCalendarMonthInUtcWithAnExclusiveEnd() {
        BillingPeriod august = BillingPeriod.parse("2026-08");

        assertThat(august.start()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(august.endExclusive()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(august.firstDay()).hasToString("2026-08-01");
    }

    @Test
    void isClosedOnlyOnceTheNextMonthHasStarted() {
        BillingPeriod august = BillingPeriod.parse("2026-08");

        assertThat(august.isClosedAt(Instant.parse("2026-08-31T23:59:59Z"))).isFalse();
        assertThat(august.isClosedAt(Instant.parse("2026-09-01T00:00:00Z"))).isTrue();
    }

    @Test
    void currentAndPreviousFollowTheClockAcrossYearEnd() {
        Clock january = clockAt("2027-01-15T10:00:00Z");

        assertThat(BillingPeriod.current(january).label()).isEqualTo("2027-01");
        assertThat(BillingPeriod.previous(january).label()).isEqualTo("2026-12");
    }
}
