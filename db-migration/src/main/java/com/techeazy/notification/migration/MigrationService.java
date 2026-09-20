package com.techeazy.notification.migration;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.RanChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.changelog.ChangeLogHistoryServiceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The migration operations, each on its own short-lived connection. Runs are serialised by a PostgreSQL advisory lock
 * (see {@code acquireRunLock}) on top of Liquibase's own lock table, so it is safe if two of these start at once, for
 * example during a rolling deployment.
 */
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);
    /** Arbitrary constant identifying "a notification-service migration is running" to pg_advisory_lock. */
    private static final long RUN_LOCK_KEY = 7_242_019_931L;
    private static final DateTimeFormatter TAG_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /** One row of the changelog history. */
    public record HistoryEntry(String id, String author, String executedAt, String type, String tag) {}

    @FunctionalInterface
    private interface LiquibaseAction<T> {
        T apply(Liquibase liquibase) throws LiquibaseException, SQLException;
    }

    private final MigrationProperties props;

    public MigrationService(MigrationProperties props) {
        this.props = props;
    }

    /** Applies all pending changesets; tags the previous state first (when enabled) so it can be rolled back to. */
    public void update() {
        execute(lb -> {
            List<ChangeSet> pending = lb.listUnrunChangeSets(contexts(), new LabelExpression());
            if (pending.isEmpty()) {
                log.info("Database is up to date, nothing to apply");
                return null;
            }
            log.info("Applying {} pending changeset(s): {}", pending.size(), ids(pending));
            if (props.isTagBeforeUpdate() && hasHistory(lb.getDatabase())) {
                String tag = "pre-" + TAG_TIME.format(Instant.now());
                lb.tag(tag);
                log.info("Tagged the current state as '{}' (roll back to it with: rollback-tag {})", tag, tag);
            }
            lb.update(contexts(), new LabelExpression());
            log.info("Migration complete");
            return null;
        });
    }

    /** The SQL an {@link #update()} would run, without running it. */
    public String updateSql() {
        return execute(lb -> {
            StringWriter out = new StringWriter();
            lb.update(contexts(), new LabelExpression(), out);
            return out.toString();
        });
    }

    /** Changesets that are still to be applied, as "id (author)" lines. */
    public List<String> status() {
        return execute(lb -> lb.listUnrunChangeSets(contexts(), new LabelExpression()).stream()
                .map(cs -> cs.getId() + " (" + cs.getAuthor() + ")").toList());
    }

    /** Fails if the changelog is invalid or an already-applied changeset was edited (checksum mismatch). */
    public void validate() {
        execute(lb -> {
            lb.validate();
            log.info("Changelog is valid and matches what has been applied");
            return null;
        });
    }

    public List<HistoryEntry> history() {
        return execute(lb -> {
            List<HistoryEntry> entries = new ArrayList<>();
            if (!hasHistory(lb.getDatabase())) return entries;
            Connection c = ((JdbcConnection) lb.getDatabase().getConnection()).getUnderlyingConnection();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, author, dateexecuted, exectype, tag FROM databasechangelog ORDER BY orderexecuted");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new HistoryEntry(rs.getString(1), rs.getString(2), String.valueOf(rs.getTimestamp(3)),
                            rs.getString(4), rs.getString(5)));
                }
            }
            return entries;
        });
    }

    public void tag(String tag) {
        execute(lb -> {
            lb.tag(tag);
            log.info("Tagged the current state as '{}'", tag);
            return null;
        });
    }

    public void rollbackCount(int count) {
        requireRollbackAllowed();
        execute(lb -> {
            log.warn("Rolling back the last {} changeset(s)", count);
            lb.rollback(count, contexts(), new LabelExpression());
            return null;
        });
    }

    public void rollbackToTag(String tag) {
        requireRollbackAllowed();
        execute(lb -> {
            log.warn("Rolling back to tag '{}'", tag);
            lb.rollback(tag, contexts(), new LabelExpression());
            return null;
        });
    }

    /** Clears a lock left behind by a job that was killed mid-run. Only use when no migration is actually running. */
    public void releaseLocks() {
        execute(lb -> {
            lb.forceReleaseLocks();
            log.info("Released the changelog lock");
            return null;
        });
    }

    private void requireRollbackAllowed() {
        if (!props.isAllowRollback()) {
            throw new IllegalStateException("Rollback changes or deletes data and is disabled. "
                    + "Set MIGRATION_ALLOW_ROLLBACK=true (migration.allow-rollback) to allow it deliberately.");
        }
    }

    private Contexts contexts() {
        return new Contexts(props.getContexts());
    }

    private static List<String> ids(List<ChangeSet> changeSets) {
        return changeSets.stream().map(ChangeSet::getId).toList();
    }

    /** True when the changelog table already records at least one applied changeset. */
    private static boolean hasHistory(Database database) throws LiquibaseException {
        List<RanChangeSet> ran = ChangeLogHistoryServiceFactory.getInstance().getChangeLogService(database).getRanChangeSets();
        return !ran.isEmpty();
    }

    private <T> T execute(LiquibaseAction<T> action) {
        try (Connection connection = connect()) {
            acquireRunLock(connection);
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(props.getChangelog(), new ClassLoaderResourceAccessor(), database)) {
                return action.apply(liquibase);
            }
        } catch (Exception e) { // Liquibase and its close() declare several checked exceptions; callers get one type
            throw new MigrationException("Migration failed: " + e.getMessage(), e);
        }
    }

    /**
     * Makes whole runs mutually exclusive. Liquibase has its own lock table, but two runs starting together can both
     * try to create that table (and the changelog table) first, and PostgreSQL rejects the loser with a duplicate-key
     * error. A session-level advisory lock is taken before Liquibase touches anything, and released when the
     * connection closes (also if this process dies), so a second run simply waits and then finds nothing to do.
     */
    private void acquireRunLock(Connection connection) throws SQLException {
        if (!props.getUrl().startsWith("jdbc:postgresql:")) return;
        log.debug("Acquiring the migration run lock (waits if another migration is running)");
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
            ps.setLong(1, RUN_LOCK_KEY);
            ps.execute();
        }
    }

    /** Connects with retries: in a container start-up the database may accept connections a little after the job starts. */
    private Connection connect() throws SQLException {
        SQLException last = null;
        int attempts = Math.max(props.getConnectRetries(), 1);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return DriverManager.getConnection(props.getUrl(), props.getUsername(), props.getPassword());
            } catch (SQLException e) {
                last = e;
                log.warn("Database not reachable yet (attempt {}/{}): {}", attempt, attempts, e.getMessage());
                try {
                    Thread.sleep(props.getConnectRetryDelayMs());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted while waiting for the database", ie);
                }
            }
        }
        throw new SQLException("Could not connect to the database after " + attempts + " attempt(s)", last);
    }

    /** Wraps any failure so callers deal with one unchecked type. */
    public static class MigrationException extends RuntimeException {
        public MigrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
