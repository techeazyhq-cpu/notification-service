package com.techeazy.notification.billing.application;

import com.techeazy.notification.billing.domain.AccountStatus;
import com.techeazy.notification.billing.domain.BillingMode;
import com.techeazy.notification.billing.domain.Money;

import java.util.UUID;

public record AccountAssignment(UUID clientId, UUID planId, BillingMode mode, Money monthlySpendCap,
                                AccountStatus status, String billingEmail) {}
