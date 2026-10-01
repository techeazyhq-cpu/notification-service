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

import com.techeazy.notification.billing.application.port.CreditStore;
import com.techeazy.notification.billing.domain.BillingNotFoundException;
import com.techeazy.notification.billing.domain.CreditHold;
import com.techeazy.notification.billing.domain.HoldScope;
import com.techeazy.notification.billing.domain.HoldStatus;
import com.techeazy.notification.billing.domain.LedgerEntry;
import com.techeazy.notification.billing.domain.LedgerEntryType;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.domain.Channel;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

class JdbcCreditStore implements CreditStore {

    private static final String CLIENT = "client";
    private static final String AMOUNT = "amount";
    private static final String REFERENCE = "reference";
    private static final String SCOPE = "scope";
    private static final String STATUS = "status";
    private static final String CURRENCY = "currency";

    private static final String COMPLETED_HOLDS = """
            SELECT h.id, h.client_id, h.scope, h.reference_id, h.channel, h.message_count, h.unit_price, h.amount,
                   h.status, h.settled_charge, h.created_at, h.settled_at, p.currency,
                   CASE h.scope
                       WHEN 'REQUEST' THEN (SELECT count(*) FROM notification_message m
                                            WHERE m.request_id = h.reference_id AND m.status = 'SENT')
                       ELSE (SELECT count(*) FROM notification_message m
                             WHERE m.id = h.reference_id AND m.status = 'SENT')
                   END AS sent_count
            FROM credit_hold h
            JOIN billing_account a ON a.client_id = h.client_id
            JOIN billing_plan p ON p.id = a.plan_id
            WHERE h.status = 'HELD'
              AND NOT EXISTS (SELECT 1 FROM notification_message m
                              WHERE h.scope = 'REQUEST' AND m.request_id = h.reference_id
                                AND m.status IN ('PENDING', 'QUEUED', 'PROCESSING', 'RETRYING'))
              AND NOT EXISTS (SELECT 1 FROM notification_message m
                              WHERE h.scope = 'MESSAGE' AND m.id = h.reference_id
                                AND m.status IN ('PENDING', 'QUEUED', 'PROCESSING', 'RETRYING'))
            ORDER BY h.created_at
            LIMIT :limit
            FOR UPDATE OF h SKIP LOCKED""";

    private final JdbcClient jdbc;

    JdbcCreditStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean debitIfSufficient(UUID clientId, Money amount) {
        return jdbc.sql("""
                UPDATE billing_account SET credit_balance = credit_balance - :amount, updated_at = now()
                WHERE client_id = :client AND credit_balance >= :amount""")
                .param(CLIENT, clientId).param(AMOUNT, amount.amount()).update() == 1;
    }

    @Override
    public void credit(UUID clientId, Money amount) {
        jdbc.sql("UPDATE billing_account SET credit_balance = credit_balance + :amount, updated_at = now() WHERE client_id = :client")
                .param(CLIENT, clientId).param(AMOUNT, amount.amount()).update();
    }

    @Override
    public Money balance(UUID clientId) {
        return jdbc.sql("""
                SELECT a.credit_balance, p.currency FROM billing_account a JOIN billing_plan p ON p.id = a.plan_id
                WHERE a.client_id = :client""")
                .param(CLIENT, clientId)
                .query((rs, n) -> JdbcRows.money(rs, "credit_balance", rs.getString(CURRENCY)))
                .optional().orElseThrow(() -> new BillingNotFoundException("Billing account"));
    }

    @Override
    public boolean appendLedger(LedgerEntry entry) {
        return jdbc.sql("""
                INSERT INTO credit_ledger_entry (id, client_id, type, amount, reference, description, created_at)
                VALUES (:id, :client, :type, :amount, :reference, :description, :createdAt)
                ON CONFLICT (client_id, type, reference) DO NOTHING""")
                .param("id", entry.id()).param(CLIENT, entry.clientId()).param("type", entry.type().name())
                .param(AMOUNT, entry.amount().amount()).param(REFERENCE, entry.reference())
                .param("description", entry.description()).param("createdAt", Timestamp.from(entry.createdAt()))
                .update() == 1;
    }

    @Override
    public List<LedgerEntry> ledger(UUID clientId, int limit, int offset) {
        return jdbc.sql("""
                SELECT e.id, e.client_id, e.type, e.amount, e.reference, e.description, e.created_at, p.currency
                FROM credit_ledger_entry e
                JOIN billing_account a ON a.client_id = e.client_id
                JOIN billing_plan p ON p.id = a.plan_id
                WHERE e.client_id = :client
                ORDER BY e.created_at DESC, e.id
                LIMIT :limit OFFSET :offset""")
                .param(CLIENT, clientId).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new LedgerEntry(rs.getObject("id", UUID.class), rs.getObject("client_id", UUID.class),
                        LedgerEntryType.valueOf(rs.getString("type")), JdbcRows.money(rs, AMOUNT, rs.getString(CURRENCY)),
                        rs.getString(REFERENCE), rs.getString("description"), JdbcRows.instant(rs, "created_at")))
                .list();
    }

    @Override
    public long ledgerSize(UUID clientId) {
        return jdbc.sql("SELECT count(*) FROM credit_ledger_entry WHERE client_id = :client")
                .param(CLIENT, clientId).query(Long.class).single();
    }

    @Override
    public void saveHold(CreditHold hold) {
        jdbc.sql("""
                INSERT INTO credit_hold (id, client_id, scope, reference_id, channel, message_count, unit_price, amount,
                                         status, settled_charge, created_at, settled_at)
                VALUES (:id, :client, :scope, :reference, :channel, :count, :unitPrice, :amount, :status, null, :createdAt, null)""")
                .param("id", hold.id()).param(CLIENT, hold.clientId()).param(SCOPE, hold.scope().name())
                .param(REFERENCE, hold.referenceId()).param("channel", hold.channel().name())
                .param("count", hold.messageCount()).param("unitPrice", hold.unitPrice().amount())
                .param(AMOUNT, hold.amount().amount()).param(STATUS, hold.status().name())
                .param("createdAt", Timestamp.from(hold.createdAt()))
                .update();
    }

    @Override
    public void updateHold(CreditHold hold) {
        jdbc.sql("UPDATE credit_hold SET status = :status, settled_charge = :charge, settled_at = :settledAt WHERE id = :id")
                .param("id", hold.id()).param(STATUS, hold.status().name())
                .param("charge", hold.settledCharge() == null ? null : hold.settledCharge().amount())
                .param("settledAt", hold.settledAt() == null ? null : Timestamp.from(hold.settledAt()))
                .update();
    }

    @Override
    public boolean hasHeldFor(HoldScope scope, UUID referenceId) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM credit_hold WHERE scope = :scope AND reference_id = :reference AND status = 'HELD')")
                .param(SCOPE, scope.name()).param(REFERENCE, referenceId).query(Boolean.class).single());
    }

    @Override
    public boolean hasOpenHolds(UUID clientId) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM credit_hold WHERE client_id = :client AND status = 'HELD')")
                .param(CLIENT, clientId).query(Boolean.class).single());
    }

    @Override
    public List<CompletedHold> lockCompletedHolds(int limit) {
        return jdbc.sql(COMPLETED_HOLDS).param("limit", limit).query(this::completedHold).list();
    }

    private CompletedHold completedHold(ResultSet rs, int row) throws SQLException {
        String currency = rs.getString(CURRENCY);
        Money settledCharge = JdbcRows.nullableMoney(rs, "settled_charge", currency);
        CreditHold hold = new CreditHold(rs.getObject("id", UUID.class), rs.getObject("client_id", UUID.class),
                HoldScope.valueOf(rs.getString(SCOPE)), rs.getObject("reference_id", UUID.class),
                Channel.valueOf(rs.getString("channel")), rs.getLong("message_count"),
                JdbcRows.money(rs, "unit_price", currency), JdbcRows.money(rs, AMOUNT, currency),
                HoldStatus.valueOf(rs.getString(STATUS)), settledCharge, JdbcRows.instant(rs, "created_at"),
                JdbcRows.instant(rs, "settled_at"));
        return new CompletedHold(hold, rs.getLong("sent_count"));
    }
}
