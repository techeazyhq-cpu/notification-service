package com.techeazy.notification.billing.infrastructure;

import com.techeazy.notification.billing.application.port.AccountRepository;
import com.techeazy.notification.billing.domain.AccountStatus;
import com.techeazy.notification.billing.domain.BillingAccount;
import com.techeazy.notification.billing.domain.BillingMode;
import com.techeazy.notification.billing.domain.BillingNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class JdbcAccountRepository implements AccountRepository {

    private static final String SELECT = """
            SELECT a.client_id, a.plan_id, a.mode, a.monthly_spend_cap, a.credit_balance, a.status, a.billing_email,
                   p.currency
            FROM billing_account a JOIN billing_plan p ON p.id = a.plan_id""";

    private final JdbcClient jdbc;

    JdbcAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BillingAccount save(BillingAccount account) {
        try {
            jdbc.sql("""
                    INSERT INTO billing_account (client_id, plan_id, mode, monthly_spend_cap, credit_balance, status,
                                                 billing_email, created_at, updated_at)
                    VALUES (:client, :plan, :mode, :cap, :balance, :status, :email, now(), now())
                    ON CONFLICT (client_id) DO UPDATE SET plan_id = EXCLUDED.plan_id, mode = EXCLUDED.mode,
                        monthly_spend_cap = EXCLUDED.monthly_spend_cap, status = EXCLUDED.status,
                        billing_email = EXCLUDED.billing_email, updated_at = now()""")
                    .param("client", account.clientId()).param("plan", account.planId()).param("mode", account.mode().name())
                    .param("cap", account.spendCap().map(cap -> cap.amount()).orElse(null))
                    .param("balance", account.creditBalance().amount()).param("status", account.status().name())
                    .param("email", account.billingEmail())
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw new BillingNotFoundException("Client");
        }
        return findByClientId(account.clientId()).orElseThrow();
    }

    @Override
    public Optional<BillingAccount> findByClientId(UUID clientId) {
        return jdbc.sql(SELECT + " WHERE a.client_id = :client").param("client", clientId).query(this::map).optional();
    }

    @Override
    public List<BillingAccount> findAll() {
        return jdbc.sql(SELECT + " ORDER BY a.created_at").query(this::map).list();
    }

    @Override
    public boolean existsWithPlan(UUID planId) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM billing_account WHERE plan_id = :plan)")
                .param("plan", planId).query(Boolean.class).single());
    }

    private BillingAccount map(ResultSet rs, int row) throws SQLException {
        String currency = rs.getString("currency");
        return new BillingAccount(rs.getObject("client_id", UUID.class), rs.getObject("plan_id", UUID.class),
                BillingMode.valueOf(rs.getString("mode")), JdbcRows.nullableMoney(rs, "monthly_spend_cap", currency),
                JdbcRows.money(rs, "credit_balance", currency), AccountStatus.valueOf(rs.getString("status")),
                rs.getString("billing_email"));
    }
}
