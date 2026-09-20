package com.techeazy.notification.migration;

import com.techeazy.notification.migration.MigrationService.HistoryEntry;
import com.techeazy.notification.migration.MigrationService.MigrationException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against a real PostgreSQL (Testcontainers); skipped automatically when Docker is not available. */
@Testcontainers(disabledWithoutDocker = true)
class MigrationServiceTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final List<String> LEGACY_FLYWAY_SCRIPTS =
            List.of("V1__init.sql", "V2__client_tracking_indexes.sql", "V3__client_owned_templates.sql");
    private static final List<String> EXPECTED_IDS =
            List.of("001-baseline", "002-client-tracking-indexes", "003-client-owned-templates");
    private static final String BOOKKEEPING = "('databasechangelog','databasechangeloglock','flyway_schema_history')";

    // ---------- fresh database ----------

    @Test
    void freshDatabaseGetsTheFullSchemaAndASecondRunChangesNothing() throws Exception {
        String db = newDatabase();
        MigrationService service = service(db, false);

        assertThat(service.status()).hasSize(3);
        service.update();

        assertThat(service.status()).isEmpty();
        assertThat(service.history()).extracting(HistoryEntry::id).containsExactlyElementsOf(EXPECTED_IDS);
        assertThat(service.history()).extracting(HistoryEntry::type).containsOnly("EXECUTED");
        assertThat(query(db, "select table_name from information_schema.tables where table_schema='public'"))
                .contains("client", "template", "provider_config", "rate_limit_policy", "notification_request", "notification_message");

        service.update(); // idempotent
        assertThat(service.history()).hasSize(3);
        service.validate();
    }

    @Test
    void theSchemaIsIdenticalToWhatTheLegacyFlywayScriptsProduced() throws Exception {
        String legacy = newDatabase();
        applyLegacyFlywayScripts(legacy);
        String fresh = newDatabase();

        service(fresh, false).update();

        assertThat(schemaSignature(fresh)).isEqualTo(schemaSignature(legacy));
    }

    // ---------- adopting a database that Flyway created ----------

    @Test
    void aFlywayCreatedDatabaseIsAdoptedWithoutRerunningAnythingAndKeepsItsData() throws Exception {
        String db = newDatabase();
        applyLegacyFlywayScripts(db);
        seed(db);
        List<String> before = schemaSignature(db);
        MigrationService service = service(db, false);

        service.update();

        assertThat(service.history()).extracting(HistoryEntry::type).containsOnly("MARK_RAN");
        assertThat(service.status()).isEmpty();
        assertThat(schemaSignature(db)).isEqualTo(before);
        assertThat(query(db, "select count(*)::text from notification_request")).containsExactly("1");
        assertThat(query(db, "select count(*)::text from template")).containsExactly("2");
        service.validate();
    }

    // ---------- rollback, tags, dry run ----------

    @Test
    void rollbackUndoesTheLastChangesetEvenWithClientTemplatesInUseAndItCanBeReapplied() throws Exception {
        String db = newDatabase();
        MigrationService service = service(db, true);
        service.update();
        seed(db);
        execute(db, "insert into template (id, client_id, name, channel, body, created_at, updated_at) values "
                + "('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-00000000000a', 'mine', 'SMS', 'x', now(), now())");
        execute(db, "update notification_request set template_id = '00000000-0000-0000-0000-0000000000c1'");

        service.rollbackCount(1);

        assertThat(query(db, "select column_name from information_schema.columns where table_name='template' and column_name='client_id'")).isEmpty();
        assertThat(query(db, "select count(*)::text from template")).containsExactly("2"); // the two shared ones; the client-owned one is gone
        assertThat(query(db, "select count(*)::text from notification_request")).containsExactly("1"); // request survives
        assertThat(query(db, "select coalesce(template_id::text,'null') from notification_request")).containsExactly("null");
        assertThat(service.history()).hasSize(2);

        service.update();

        assertThat(service.history()).extracting(HistoryEntry::id).containsExactlyElementsOf(EXPECTED_IDS);
        assertThat(query(db, "select column_name from information_schema.columns where table_name='template' and column_name='client_id'")).hasSize(1);
    }

    @Test
    void rollbackIsRefusedUnlessExplicitlyAllowed() {
        String db = newDatabase();
        MigrationService service = service(db, false);
        service.update();

        assertThatThrownBy(() -> service.rollbackCount(1)).hasMessageContaining("disabled");
        assertThatThrownBy(() -> service.rollbackToTag("anything")).hasMessageContaining("disabled");
        assertThat(service.history()).hasSize(3);
    }

    @Test
    void updateTagsThePreviousStateSoAReleaseCanBeRolledBackToIt() {
        String db = newDatabase();
        MigrationService service = service(db, true);
        service.update();
        service.rollbackCount(1); // pretend we are on the previous release: 001 and 002 applied

        service.update();         // the "new release" applies 003 and tags the state before it

        String tag = service.history().stream().map(HistoryEntry::tag).filter(t -> t != null && t.startsWith("pre-")).findFirst().orElseThrow();
        assertThat(service.history()).hasSize(3);

        service.rollbackToTag(tag);

        assertThat(service.history()).extracting(HistoryEntry::id).containsExactly(EXPECTED_IDS.get(0), EXPECTED_IDS.get(1));
    }

    @Test
    void updateSqlShowsWhatWouldRunWithoutRunningIt() throws Exception {
        String db = newDatabase();

        String sql = service(db, false).updateSql();

        assertThat(sql).contains("CREATE TABLE client").contains("ALTER TABLE template ADD COLUMN client_id");
        assertThat(query(db, "select table_name from information_schema.tables where table_schema='public' and table_name='client'")).isEmpty();
    }

    @Test
    void validateDetectsAnAppliedChangesetThatWasEdited() throws Exception {
        String db = newDatabase();
        MigrationService service = service(db, false);
        service.update();
        execute(db, "update databasechangelog set md5sum = '9:deadbeefdeadbeefdeadbeefdeadbeef' where id = '002-client-tracking-indexes'");

        assertThatThrownBy(service::validate).isInstanceOf(MigrationException.class);
    }

    // ---------- operations ----------

    @Test
    void twoMigrationsStartingAtOnceDoNotCollide() throws Exception {
        String db = newDatabase();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) runs.add(pool.submit(() -> service(db, false).update()));
            for (Future<?> run : runs) run.get(120, TimeUnit.SECONDS); // rethrows any failure
        } finally {
            pool.shutdownNow();
        }

        assertThat(service(db, false).history()).extracting(HistoryEntry::id).containsExactlyElementsOf(EXPECTED_IDS);
    }

    @Test
    void anUnreachableDatabaseFailsAfterTheConfiguredRetries() {
        MigrationProperties props = new MigrationProperties();
        props.setUrl("jdbc:postgresql://localhost:1/none");
        props.setConnectRetries(2);
        props.setConnectRetryDelayMs(10);

        MigrationService service = new MigrationService(props);

        assertThatThrownBy(service::update).isInstanceOf(MigrationException.class).hasMessageContaining("2 attempt");
    }

    // ---------- helpers ----------

    private static String newDatabase() {
        String name = "t" + SEQ.incrementAndGet();
        try (Connection c = DriverManager.getConnection(jdbcUrl(PG.getDatabaseName()), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            s.execute("create database " + name);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return name;
    }

    private static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;
    }

    private static MigrationService service(String database, boolean allowRollback) {
        MigrationProperties props = new MigrationProperties();
        props.setUrl(jdbcUrl(database));
        props.setUsername(PG.getUsername());
        props.setPassword(PG.getPassword());
        props.setAllowRollback(allowRollback);
        return new MigrationService(props);
    }

    private static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), PG.getUsername(), PG.getPassword());
    }

    private static void execute(String database, String sql) throws SQLException {
        try (Connection c = connect(database); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static List<String> query(String database, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = connect(database); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) rows.add(rs.getString(1));
        }
        return rows;
    }

    private static void applyLegacyFlywayScripts(String database) throws IOException, SQLException {
        for (String script : LEGACY_FLYWAY_SCRIPTS) {
            try (InputStream in = MigrationServiceTest.class.getResourceAsStream("/legacy-flyway/" + script)) {
                assertThat(in).as(script).isNotNull();
                execute(database, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    /** One client, a shared template, a request that used the template. Valid in the V3 schema. */
    private static void seed(String database) throws SQLException {
        execute(database, "insert into client (id, name, api_key_hash, api_key_prefix, status, allowed_channels, created_at, updated_at) values "
                + "('00000000-0000-0000-0000-00000000000a', 'acme', 'hash', 'ntf_abc', 'ACTIVE', 'SMS', now(), now())");
        execute(database, "insert into template (id, name, channel, body, created_at, updated_at) values "
                + "('00000000-0000-0000-0000-0000000000b1', 'shared-one', 'SMS', 'hello {{name}}', now(), now())");
        execute(database, "insert into notification_request (id, client_id, kind, channel, template_id, subject, body, total, created_at) values "
                + "('00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-00000000000a', 'SINGLE', 'SMS', "
                + "'00000000-0000-0000-0000-0000000000b1', null, 'hello {{name}}', 1, now())");
        execute(database, "insert into template (id, name, channel, body, created_at, updated_at) values "
                + "('00000000-0000-0000-0000-0000000000b2', 'shared-two', 'SMS', 'bye', now(), now())");
    }

    /** Columns, indexes and constraints of the application tables, normalised, so two databases can be compared. */
    private static List<String> schemaSignature(String database) throws SQLException {
        List<String> lines = new ArrayList<>();
        lines.addAll(query(database, "select 'col ' || table_name || '.' || column_name || ' ' || data_type || ' null=' || is_nullable "
                + "|| ' default=' || coalesce(column_default, '') from information_schema.columns "
                + "where table_schema='public' and table_name not in " + BOOKKEEPING));
        lines.addAll(query(database, "select 'idx ' || tablename || ' ' || indexdef from pg_indexes "
                + "where schemaname='public' and tablename not in " + BOOKKEEPING));
        lines.addAll(query(database, "select 'con ' || conrelid::regclass || ' ' || conname || ' ' || pg_get_constraintdef(oid) "
                + "from pg_constraint where connamespace = 'public'::regnamespace and conrelid::regclass::text not in " + BOOKKEEPING));
        Collections.sort(lines);
        return lines;
    }
}
