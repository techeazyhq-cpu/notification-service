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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreditHoldTest {

    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");

    private static CreditHold hold(long messages) {
        return CreditHold.place(UUID.randomUUID(), HoldScope.REQUEST, UUID.randomUUID(), Channel.SMS, messages,
                Money.of("0.05", "USD"), NOW);
    }

    @Test
    void reservesTheWorstCaseAmount() {
        CreditHold hold = hold(200);

        assertThat(hold.amount()).isEqualTo(Money.of("10", "USD"));
        assertThat(hold.status()).isEqualTo(HoldStatus.HELD);
    }

    @Test
    void settlementChargesOnlyMessagesThatWereSentAndRefundsTheRest() {
        CreditHold settled = hold(200).settle(150, NOW);

        assertThat(settled.status()).isEqualTo(HoldStatus.SETTLED);
        assertThat(settled.settledCharge()).isEqualTo(Money.of("7.5", "USD"));
        assertThat(settled.refund()).isEqualTo(Money.of("2.5", "USD"));
        assertThat(settled.settledAt()).isEqualTo(NOW);
    }

    @Test
    void whenEverythingWasSentNothingIsRefunded() {
        assertThat(hold(200).settle(200, NOW).refund().isZero()).isTrue();
    }

    @Test
    void neverChargesMoreThanWasReserved() {
        assertThat(hold(10).settle(999, NOW).settledCharge()).isEqualTo(Money.of("0.5", "USD"));
    }

    @Test
    void whenNothingWasSentTheWholeReservationIsReturned() {
        CreditHold settled = hold(40).settle(0, NOW);

        assertThat(settled.settledCharge().isZero()).isTrue();
        assertThat(settled.refund()).isEqualTo(Money.of("2", "USD"));
    }

    @Test
    void aHoldSettlesOnceAndRefundsOnlyAfterSettlement() {
        CreditHold held = hold(10);
        CreditHold settled = held.settle(5, NOW);

        assertThatThrownBy(() -> settled.settle(5, NOW)).isInstanceOf(InvalidBillingStateException.class);
        assertThatThrownBy(held::refund).isInstanceOf(InvalidBillingStateException.class);
    }
}
