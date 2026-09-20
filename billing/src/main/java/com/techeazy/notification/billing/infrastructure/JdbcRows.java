package com.techeazy.notification.billing.infrastructure;

import com.techeazy.notification.billing.domain.Money;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

/** Column readers shared by the JDBC adapters. */
final class JdbcRows {

    private JdbcRows() {}

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static Money money(ResultSet rs, String column, String currency) throws SQLException {
        return new Money(rs.getBigDecimal(column), currency);
    }

    static Money nullableMoney(ResultSet rs, String column, String currency) throws SQLException {
        return rs.getBigDecimal(column) == null ? null : money(rs, column, currency);
    }
}
