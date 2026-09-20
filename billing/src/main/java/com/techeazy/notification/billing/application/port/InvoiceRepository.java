package com.techeazy.notification.billing.application.port;

import com.techeazy.notification.billing.domain.BillingPeriod;
import com.techeazy.notification.billing.domain.Invoice;
import com.techeazy.notification.billing.domain.InvoiceStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface InvoiceRepository {

    /**
     * Inserts or updates an invoice with its lines and payments.
     *
     * @throws com.techeazy.notification.billing.domain.InvoiceAlreadyExistsException if a non-void invoice for the same
     *         client and period exists
     */
    void save(Invoice invoice);

    Optional<Invoice> findById(UUID id);

    /** Loads the invoice and locks its row until the surrounding transaction ends, so concurrent changes queue up. */
    Optional<Invoice> findByIdForUpdate(UUID id);

    /** The invoice of the period that is not void, if any. */
    Optional<Invoice> findCurrent(UUID clientId, BillingPeriod period);

    List<Invoice> findByClient(UUID clientId, Set<InvoiceStatus> statuses);

    /** Newest period first; a null status or client means any. */
    List<Invoice> search(InvoiceStatus status, UUID clientId, int limit);

    /** Reserves the next invoice number, for example {@code INV-2026-000042}. Numbers are unique, gaps are possible. */
    String nextNumber(Instant now);
}
