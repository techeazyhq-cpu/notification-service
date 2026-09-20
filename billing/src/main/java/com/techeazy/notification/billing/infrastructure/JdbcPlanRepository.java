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

import com.techeazy.notification.billing.application.port.PlanRepository;
import com.techeazy.notification.billing.domain.ChannelRate;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.billing.domain.Plan;
import com.techeazy.notification.domain.Channel;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class JdbcPlanRepository implements PlanRepository {

    private record PlanRow(UUID id, String name, String currency, BigDecimal platformFee, BigDecimal taxRate, boolean active) {}

    private record RateRow(UUID planId, Channel channel, BigDecimal unitPrice, long freeAllowance) {}

    private final JdbcClient jdbc;

    JdbcPlanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Plan save(Plan plan) {
        jdbc.sql("""
                INSERT INTO billing_plan (id, name, currency, platform_fee, tax_rate, active, created_at, updated_at)
                VALUES (:id, :name, :currency, :fee, :tax, :active, now(), now())
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, currency = EXCLUDED.currency,
                    platform_fee = EXCLUDED.platform_fee, tax_rate = EXCLUDED.tax_rate, active = EXCLUDED.active,
                    updated_at = now()""")
                .param("id", plan.id()).param("name", plan.name()).param("currency", plan.currency())
                .param("fee", plan.platformFee().amount()).param("tax", plan.taxRate()).param("active", plan.active())
                .update();
        jdbc.sql("DELETE FROM billing_plan_rate WHERE plan_id = :id").param("id", plan.id()).update();
        plan.rates().forEach((channel, rate) -> jdbc.sql("""
                INSERT INTO billing_plan_rate (plan_id, channel, unit_price, free_allowance)
                VALUES (:plan, :channel, :price, :allowance)""")
                .param("plan", plan.id()).param("channel", channel.name())
                .param("price", rate.unitPrice().amount()).param("allowance", rate.freeAllowance())
                .update());
        return plan;
    }

    @Override
    public Optional<Plan> findById(UUID id) {
        return hydrate(jdbc.sql("SELECT id, name, currency, platform_fee, tax_rate, active FROM billing_plan WHERE id = :id")
                .param("id", id).query(this::planRow).list()).stream().findFirst();
    }

    @Override
    public Optional<Plan> findByName(String name) {
        return hydrate(jdbc.sql("SELECT id, name, currency, platform_fee, tax_rate, active FROM billing_plan WHERE name = :name")
                .param("name", name).query(this::planRow).list()).stream().findFirst();
    }

    @Override
    public List<Plan> findAll() {
        return hydrate(jdbc.sql("SELECT id, name, currency, platform_fee, tax_rate, active FROM billing_plan ORDER BY name")
                .query(this::planRow).list());
    }

    private PlanRow planRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new PlanRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("currency"),
                rs.getBigDecimal("platform_fee"), rs.getBigDecimal("tax_rate"), rs.getBoolean("active"));
    }

    private List<Plan> hydrate(List<PlanRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, Map<Channel, ChannelRate>> ratesByPlan = new HashMap<>();
        Map<UUID, String> currencies = new HashMap<>();
        rows.forEach(row -> currencies.put(row.id(), row.currency()));
        List<RateRow> rateRows = jdbc.sql("SELECT plan_id, channel, unit_price, free_allowance FROM billing_plan_rate WHERE plan_id IN (:ids)")
                .param("ids", currencies.keySet())
                .query((rs, n) -> new RateRow(rs.getObject("plan_id", UUID.class), Channel.valueOf(rs.getString("channel")),
                        rs.getBigDecimal("unit_price"), rs.getLong("free_allowance")))
                .list();
        for (RateRow rate : rateRows) {
            ratesByPlan.computeIfAbsent(rate.planId(), k -> new EnumMap<>(Channel.class)).put(rate.channel(),
                    new ChannelRate(new Money(rate.unitPrice(), currencies.get(rate.planId())), rate.freeAllowance()));
        }
        return rows.stream().map(row -> new Plan(row.id(), row.name(), row.currency(), new Money(row.platformFee(), row.currency()),
                row.taxRate(), ratesByPlan.getOrDefault(row.id(), Map.of()), row.active())).toList();
    }
}
