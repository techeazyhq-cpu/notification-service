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

package com.techeazy.notification.billing.infrastructure;

import com.techeazy.notification.billing.application.port.InvoiceRepository;
import com.techeazy.notification.billing.domain.BillingPeriod;
import com.techeazy.notification.billing.domain.Invoice;
import com.techeazy.notification.billing.domain.InvoiceAlreadyExistsException;
import com.techeazy.notification.billing.domain.InvoiceLine;
import com.techeazy.notification.billing.domain.InvoiceLineKind;
import com.techeazy.notification.billing.domain.InvoiceStatus;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.billing.domain.Payment;
import com.techeazy.notification.domain.Channel;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

class JdbcInvoiceRepository implements InvoiceRepository {

    private static final String HEADER = """
            SELECT id, number, client_id, plan_id, period_start, currency, tax_rate, status, created_at, issued_at,
                   due_at, paid_at, voided_at, void_reason
            FROM invoice
            """;

    private record Header(UUID id, String number, UUID clientId, UUID planId, LocalDate periodStart, String currency,
                          BigDecimal taxRate, InvoiceStatus status, Instant createdAt, Instant issuedAt, Instant dueAt,
                          Instant paidAt, Instant voidedAt, String voidReason) {}

    private final JdbcClient jdbc;

    JdbcInvoiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Invoice invoice) {
        try {
            upsertHeader(invoice);
        } catch (DuplicateKeyException e) {
            if (String.valueOf(e.getMessage()).contains("ux_invoice_period")) {
                throw new InvoiceAlreadyExistsException();
            }
            throw e;
        }
        jdbc.sql("DELETE FROM invoice_line WHERE invoice_id = :id").param("id", invoice.id()).update();
        int position = 0;
        for (InvoiceLine line : invoice.lines()) {
            insertLine(invoice.id(), position++, line);
        }
        invoice.payments().forEach(payment -> insertPayment(invoice.id(), payment));
    }

    @Override
    public Optional<Invoice> findById(UUID id) {
        return hydrate(jdbc.sql(HEADER + " WHERE id = :id").param("id", id).query(this::header).list()).stream().findFirst();
    }

    @Override
    public Optional<Invoice> findByIdForUpdate(UUID id) {
        return hydrate(jdbc.sql(HEADER + " WHERE id = :id FOR UPDATE").param("id", id).query(this::header).list())
                .stream().findFirst();
    }

    @Override
    public Optional<Invoice> findCurrent(UUID clientId, BillingPeriod period) {
        return hydrate(jdbc.sql(HEADER + " WHERE client_id = :client AND period_start = :period AND status <> 'VOID'")
                .param("client", clientId).param("period", java.sql.Date.valueOf(period.firstDay()))
                .query(this::header).list()).stream().findFirst();
    }

    @Override
    public List<Invoice> findByClient(UUID clientId, Set<InvoiceStatus> statuses) {
        List<String> names = statuses.stream().map(Enum::name).toList();
        return hydrate(jdbc.sql(HEADER + " WHERE client_id = :client AND status IN (:statuses) ORDER BY period_start DESC, created_at DESC")
                .param("client", clientId).param("statuses", names).query(this::header).list());
    }

    @Override
    public List<Invoice> search(InvoiceStatus status, UUID clientId, int limit) {
        return hydrate(jdbc.sql(HEADER + """
                 WHERE (CAST(:status AS varchar) IS NULL OR status = CAST(:status AS varchar))
                   AND (CAST(:client AS uuid) IS NULL OR client_id = CAST(:client AS uuid))
                 ORDER BY period_start DESC, created_at DESC
                 LIMIT :limit""")
                .param("status", status == null ? null : status.name()).param("client", clientId).param("limit", limit)
                .query(this::header).list());
    }

    @Override
    public String nextNumber(Instant now) {
        long sequence = jdbc.sql("SELECT nextval('invoice_number_seq')").query(Long.class).single();
        return String.format("INV-%d-%06d", now.atZone(ZoneOffset.UTC).getYear(), sequence);
    }

    private void upsertHeader(Invoice invoice) {
        jdbc.sql("""
                INSERT INTO invoice (id, number, client_id, plan_id, period_start, currency, subtotal, tax_rate, tax_amount,
                                     total, status, issued_at, due_at, paid_at, voided_at, void_reason, created_at, updated_at)
                VALUES (:id, :number, :client, :plan, :period, :currency, :subtotal, :taxRate, :taxAmount, :total, :status,
                        :issuedAt, :dueAt, :paidAt, :voidedAt, :voidReason, :createdAt, now())
                ON CONFLICT (id) DO UPDATE SET number = EXCLUDED.number, plan_id = EXCLUDED.plan_id,
                    subtotal = EXCLUDED.subtotal, tax_rate = EXCLUDED.tax_rate, tax_amount = EXCLUDED.tax_amount,
                    total = EXCLUDED.total, status = EXCLUDED.status, issued_at = EXCLUDED.issued_at,
                    due_at = EXCLUDED.due_at, paid_at = EXCLUDED.paid_at, voided_at = EXCLUDED.voided_at,
                    void_reason = EXCLUDED.void_reason, updated_at = now()""")
                .param("id", invoice.id()).param("number", invoice.number()).param("client", invoice.clientId())
                .param("plan", invoice.planId()).param("period", java.sql.Date.valueOf(invoice.period().firstDay()))
                .param("currency", invoice.currency()).param("subtotal", invoice.subtotal().amount())
                .param("taxRate", invoice.taxRate()).param("taxAmount", invoice.taxAmount().amount())
                .param("total", invoice.total().amount()).param("status", invoice.status().name())
                .param("issuedAt", timestamp(invoice.issuedAt())).param("dueAt", timestamp(invoice.dueAt()))
                .param("paidAt", timestamp(invoice.paidAt())).param("voidedAt", timestamp(invoice.voidedAt()))
                .param("voidReason", invoice.voidReason()).param("createdAt", timestamp(invoice.createdAt()))
                .update();
    }

    private void insertLine(UUID invoiceId, int position, InvoiceLine line) {
        jdbc.sql("""
                INSERT INTO invoice_line (id, invoice_id, position, kind, channel, description, quantity, unit_price, amount)
                VALUES (:id, :invoice, :position, :kind, :channel, :description, :quantity, :unitPrice, :amount)""")
                .param("id", UUID.randomUUID()).param("invoice", invoiceId).param("position", position)
                .param("kind", line.kind().name()).param("channel", line.channel() == null ? null : line.channel().name())
                .param("description", line.description()).param("quantity", line.quantity())
                .param("unitPrice", line.unitPrice().amount()).param("amount", line.amount().amount())
                .update();
    }

    private void insertPayment(UUID invoiceId, Payment payment) {
        jdbc.sql("""
                INSERT INTO invoice_payment (id, invoice_id, amount, method, reference, received_at)
                VALUES (:id, :invoice, :amount, :method, :reference, :receivedAt)
                ON CONFLICT (id) DO NOTHING""")
                .param("id", payment.id()).param("invoice", invoiceId).param("amount", payment.amount().amount())
                .param("method", payment.method()).param("reference", payment.reference())
                .param("receivedAt", timestamp(payment.receivedAt()))
                .update();
    }

    private Header header(ResultSet rs, int row) throws SQLException {
        return new Header(rs.getObject("id", UUID.class), rs.getString("number"), rs.getObject("client_id", UUID.class),
                rs.getObject("plan_id", UUID.class), rs.getObject("period_start", LocalDate.class), rs.getString("currency"),
                rs.getBigDecimal("tax_rate"), InvoiceStatus.valueOf(rs.getString("status")),
                JdbcRows.instant(rs, "created_at"), JdbcRows.instant(rs, "issued_at"), JdbcRows.instant(rs, "due_at"),
                JdbcRows.instant(rs, "paid_at"), JdbcRows.instant(rs, "voided_at"), rs.getString("void_reason"));
    }

    private List<Invoice> hydrate(List<Header> headers) {
        if (headers.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = headers.stream().map(Header::id).collect(Collectors.toSet());
        Map<UUID, String> currencies = headers.stream().collect(Collectors.toMap(Header::id, Header::currency));
        Map<UUID, List<InvoiceLine>> lines = new HashMap<>();
        Map<UUID, List<Payment>> payments = new HashMap<>();
        jdbc.sql("""
                SELECT invoice_id, kind, channel, description, quantity, unit_price, amount
                FROM invoice_line WHERE invoice_id IN (:ids) ORDER BY invoice_id, position""")
                .param("ids", ids)
                .query((rs, n) -> Map.entry(rs.getObject("invoice_id", UUID.class), line(rs, currencies)))
                .list().forEach(e -> lines.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        jdbc.sql("""
                SELECT id, invoice_id, amount, method, reference, received_at
                FROM invoice_payment WHERE invoice_id IN (:ids) ORDER BY received_at""")
                .param("ids", ids)
                .query((rs, n) -> Map.entry(rs.getObject("invoice_id", UUID.class), payment(rs, currencies)))
                .list().forEach(e -> payments.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return headers.stream().map(h -> Invoice.restore(new Invoice.State(h.id(), h.clientId(), h.planId(),
                BillingPeriod.of(h.periodStart()), h.currency(), h.taxRate(), lines.getOrDefault(h.id(), List.of()),
                h.status(), h.number(), h.createdAt(), h.issuedAt(), h.dueAt(), h.paidAt(), h.voidedAt(), h.voidReason(),
                payments.getOrDefault(h.id(), List.of())))).toList();
    }

    private static InvoiceLine line(ResultSet rs, Map<UUID, String> currencies) throws SQLException {
        String currency = currencies.get(rs.getObject("invoice_id", UUID.class));
        String channel = rs.getString("channel");
        return new InvoiceLine(InvoiceLineKind.valueOf(rs.getString("kind")), channel == null ? null : Channel.valueOf(channel),
                rs.getString("description"), rs.getLong("quantity"), new Money(rs.getBigDecimal("unit_price"), currency),
                new Money(rs.getBigDecimal("amount"), currency));
    }

    private static Payment payment(ResultSet rs, Map<UUID, String> currencies) throws SQLException {
        String currency = currencies.get(rs.getObject("invoice_id", UUID.class));
        return new Payment(rs.getObject("id", UUID.class), new Money(rs.getBigDecimal("amount"), currency),
                rs.getString("method"), rs.getString("reference"), JdbcRows.instant(rs, "received_at"));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
