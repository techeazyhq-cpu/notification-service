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
