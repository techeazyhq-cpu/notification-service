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

import java.util.Optional;
import java.util.UUID;

/**
 * Links a client to a plan and says how it pays. A client without an account is not billed.
 * The spend cap applies to postpaid accounts only, the credit balance to prepaid accounts only.
 */
public record BillingAccount(UUID clientId, UUID planId, BillingMode mode, Money monthlySpendCap,
                             Money creditBalance, AccountStatus status, String billingEmail) {

    public BillingAccount {
        if (mode == BillingMode.PREPAID && monthlySpendCap != null) {
            throw new InvalidBillingDataException("A monthly spend cap applies to postpaid accounts only");
        }
        if (monthlySpendCap != null && monthlySpendCap.isNegative()) {
            throw new InvalidBillingDataException("Spend cap cannot be negative");
        }
        if (creditBalance.isNegative()) {
            throw new InvalidBillingDataException("Credit balance cannot be negative");
        }
    }

    public boolean isPrepaid() {
        return mode == BillingMode.PREPAID;
    }

    public boolean isSuspended() {
        return status == AccountStatus.SUSPENDED;
    }

    public Optional<Money> spendCap() {
        return Optional.ofNullable(monthlySpendCap);
    }
}
