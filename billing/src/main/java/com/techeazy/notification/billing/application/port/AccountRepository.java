package com.techeazy.notification.billing.application.port;

import com.techeazy.notification.billing.domain.BillingAccount;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores billing accounts. Saving never writes the credit balance: it is changed only through
 * {@link CreditStore}, whose operations are atomic, so a concurrent edit of the account cannot overwrite it.
 */
public interface AccountRepository {

    BillingAccount save(BillingAccount account);

    Optional<BillingAccount> findByClientId(UUID clientId);

    List<BillingAccount> findAll();

    boolean existsWithPlan(UUID planId);
}
