/*
 * Copyright 2026 Vasantha Kumar Kesavan
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
 * @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>
 */

package com.techeazy.notification.adminapi.anomaly;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Where the anomaly job reads tenants' messages and requests from (ADR-050). Without a reporting user that is the
 * service's own connection. With one, it is a small pool of its own that logs in as that user, on the primary or on a
 * read replica, and marks every connection read-only, so the job's reads cannot write even if the role could. The
 * PostgreSQL driver enforces read-only only inside explicit transactions unless {@code readOnlyMode} is {@code always},
 * and the job's queries run in autocommit, so the pool sets it.
 *
 * <p>The pool connects on first use, not at startup, so the admin console starts while a replica is unreachable; the
 * job's run fails and is retried the next hour instead.
 */
final class TrafficSource implements AutoCloseable {

    private static final String POOL_NAME = "traffic-source";
    private static final String READ_ONLY_MODE = "readOnlyMode";

    private final JdbcClient jdbc;
    private final HikariDataSource pool;

    private TrafficSource(JdbcClient jdbc, HikariDataSource pool) {
        this.jdbc = jdbc;
        this.pool = pool;
    }

    /**
     * @param reporting the reporting database settings
     * @param primary   the service's own connection, used when no reporting user is set
     * @return the source the job reads through
     */
    static TrafficSource of(AnomalyProperties.ReportingDatabase reporting, JdbcClient primary) {
        if (!reporting.isConfigured()) {
            return new TrafficSource(primary, null);
        }
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName(POOL_NAME);
        pool.setJdbcUrl(reporting.getUrl());
        pool.setUsername(reporting.getUsername());
        pool.setPassword(reporting.getPassword());
        pool.setMaximumPoolSize(reporting.getMaximumPoolSize());
        pool.setMinimumIdle(0);
        pool.setReadOnly(true);
        pool.addDataSourceProperty(READ_ONLY_MODE, "always");
        return new TrafficSource(JdbcClient.create(pool), pool);
    }

    JdbcClient jdbc() {
        return jdbc;
    }

    /** Whether the job reads through a pool of its own rather than the service's connection. */
    boolean isSeparate() {
        return pool != null;
    }

    /** Closes the job's own pool; the service's connection is left alone. */
    @Override
    public void close() {
        if (pool != null) {
            pool.close();
        }
    }
}
