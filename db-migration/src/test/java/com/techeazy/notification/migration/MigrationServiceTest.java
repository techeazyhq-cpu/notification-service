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
            List.of("001-baseline", "002-client-tracking-indexes", "003-client-owned-templates", "004-billing",
                    "005-admin-accounts", "006-client-senders", "007-personal-data-retention", "008-dead-letters",
                    "009-admin-roles", "010-admin-audit-log", "011-message-error-code",
                    "012-message-category", "013-otp-sweep-index");
    private static final String NOT_COMPARED = "('databasechangelog','databasechangeloglock','flyway_schema_history',"
            + "'billing_plan','billing_plan_rate','billing_account','credit_ledger_entry','credit_hold','invoice','invoice_line','invoice_payment','admin_user','admin_recovery_code','admin_session','client_sender','admin_audit_event')";
    private static final List<String> BILLING_TABLES =
            List.of("billing_plan", "billing_plan_rate", "billing_account", "credit_ledger_entry", "credit_hold", "invoice", "invoice_line", "invoice_payment");

    // ---------- fresh database ----------

    @Test
    void freshDatabaseGetsTheFullSchemaAndASecondRunChangesNothing() throws Exception {
        String db = newDatabase();
        MigrationService service = service(db, false);

        assertThat(service.status()).hasSize(EXPECTED_IDS.size());
        service.update();

        assertThat(service.status()).isEmpty();
        assertThat(service.history()).extracting(HistoryEntry::id).containsExactlyElementsOf(EXPECTED_IDS);
        assertThat(service.history()).extracting(HistoryEntry::type).containsOnly("EXECUTED");
        assertThat(query(db, "select table_name from information_schema.tables where table_schema='public'"))
                .contains("client", "template", "provider_config", "rate_limit_policy", "notification_request", "notification_message");

        service.update();
        assertThat(service.history()).hasSize(EXPECTED_IDS.size());
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

        assertThat(service.history()).extracting(HistoryEntry::type).containsExactlyElementsOf(adoptedLegacyHistory());
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

        undoFrom(service, "003-client-owned-templates");

        assertThat(query(db, "select column_name from information_schema.columns where table_name='template' and column_name='client_id'")).isEmpty();
        assertThat(query(db, "select count(*)::text from template")).containsExactly("2"); // the two shared ones; the client-owned one is gone
        assertThat(query(db, "select count(*)::text from notification_request")).containsExactly("1"); // request survives
        assertThat(query(db, "select coalesce(template_id::text,'null') from notification_request")).containsExactly("null");
        assertThat(service.history()).hasSize(2);
        assertThat(billingTablesPresent(db)).isEmpty();

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
        assertThat(service.history()).hasSize(EXPECTED_IDS.size());
    }

    @Test
    void updateTagsThePreviousStateSoAReleaseCanBeRolledBackToIt() {
        String db = newDatabase();
        MigrationService service = service(db, true);
        service.update();
        service.rollbackCount(1);

        service.update();

        String tag = service.history().stream().map(HistoryEntry::tag).filter(t -> t != null && t.startsWith("pre-")).findFirst().orElseThrow();
        assertThat(service.history()).hasSize(EXPECTED_IDS.size());

        service.rollbackToTag(tag);

        assertThat(service.history()).extracting(HistoryEntry::id).containsExactlyElementsOf(EXPECTED_IDS.subList(0,
                EXPECTED_IDS.size() - 1));
    }

    @Test
    void theAuditLogChangesetMakesTheTableAppendOnlyAndRollsBackCleanly() throws Exception {
        String db = newDatabase();
        MigrationService service = service(db, true);
        service.update();
        execute(db, "insert into admin_audit_event (id, occurred_at, actor, http_method, path, status_code, outcome) "
                + "values ('00000000-0000-0000-0000-0000000000e1', now(), 'alice', 'POST', '/api/admin/clients', 201, "
                + "'SUCCEEDED')");

        assertThatThrownBy(() -> execute(db, "update admin_audit_event set actor = 'mallory'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute(db, "delete from admin_audit_event")).hasMessageContaining("append-only");
        assertThat(query(db, "select actor from admin_audit_event")).containsExactly("alice");

        undoFrom(service, "010-admin-audit-log");

        assertThat(query(db, "select table_name from information_schema.tables where table_name = 'admin_audit_event'"))
                .isEmpty();
        assertThat(query(db, "select proname from pg_proc where proname = 'reject_admin_audit_event_change'")).isEmpty();
        assertThat(service.history()).hasSize(EXPECTED_IDS.indexOf("010-admin-audit-log"));

        service.update();

        assertThat(service.history()).extracting(HistoryEntry::id).containsExactlyElementsOf(EXPECTED_IDS);
    }

    @Test
    void theErrorCodeChangesetNamesExistingFailuresAndRetriesAndRollsBackCleanly() throws Exception {
        String db = newDatabase();
        MigrationService service = service(db, true);
        service.update();
        undoFrom(service, "011-message-error-code");
        seed(db);
        insertMessage(db, "00000000-0000-0000-0000-0000000000c1", "FAILED", "'EXHAUSTED'");
        insertMessage(db, "00000000-0000-0000-0000-0000000000c2", "FAILED", "'PERMANENT'");
        insertMessage(db, "00000000-0000-0000-0000-0000000000c3", "RETRYING", "null");

        service.update();

        assertThat(query(db,
                "select error_code from notification_message where id = '00000000-0000-0000-0000-0000000000c1'"))
                .containsExactly("DELIVERY_ATTEMPTS_EXHAUSTED");
        assertThat(query(db,
                "select error_code from notification_message where id = '00000000-0000-0000-0000-0000000000c2'"))
                .containsExactly("DELIVERY_REJECTED");
        assertThat(query(db,
                "select error_code from notification_message where id = '00000000-0000-0000-0000-0000000000c3'"))
                .containsExactly("PROVIDER_TEMPORARILY_FAILING");

        undoFrom(service, "011-message-error-code");

        assertThat(query(db,
                "select column_name from information_schema.columns where table_name = 'notification_message' "
                + "and column_name = 'error_code'")).isEmpty();
    }

    private static void insertMessage(String db, String id, String status, String failureKindLiteral) throws Exception {
        execute(db, "insert into notification_message (id, request_id, client_id, channel, recipient, variables, "
                + "status, failure_kind, last_error, created_at, updated_at) select '" + id + "', id, client_id, "
                + "'SMS', '+1', '{}'::jsonb, '" + status + "', " + failureKindLiteral + ", 'reason', now(), now() "
                + "from notification_request limit 1");
    }

    @Test
    void theCategoryChangesetGivesExistingMessagesTheDefaultAndAllowsExpiredFailuresAndRollsBackCleanly()
            throws Exception {
        String db = newDatabase();
        MigrationService service = service(db, true);
        service.update();
        undoFrom(service, "012-message-category");
        seed(db);
        insertMessage(db, "00000000-0000-0000-0000-0000000000d1", "QUEUED", "null");

        service.update();

        assertThat(query(db, "select category from notification_message "
                + "where id = '00000000-0000-0000-0000-0000000000d1'")).containsExactly("TRANSACTIONAL");
        assertThat(query(db, "select category from notification_request limit 1")).containsExactly("TRANSACTIONAL");
        execute(db, "update notification_message set status = 'FAILED', failure_kind = 'EXPIRED' "
                + "where id = '00000000-0000-0000-0000-0000000000d1'");
        assertThatThrownBy(() -> execute(db, "update notification_message set category = 'URGENT' "
                + "where id = '00000000-0000-0000-0000-0000000000d1'")).hasMessageContaining("ck_message_category");

        undoFrom(service, "012-message-category");

        assertThat(query(db, "select failure_kind from notification_message "
                + "where id = '00000000-0000-0000-0000-0000000000d1'")).containsExactly("PERMANENT");
        assertThat(query(db, "select column_name from information_schema.columns where column_name = 'category' "
                + "and table_name in ('notification_message', 'notification_request', 'template')")).isEmpty();
    }

    @Test
    void theOtpSweepIndexReplacesThePendingOnlyIndexAndRollsBackCleanly() throws Exception {
        String db = newDatabase();
        MigrationService service = service(db, true);
        service.update();

        assertThat(query(db, "select indexname from pg_indexes where indexname in "
                + "('ix_message_pending_otp', 'ix_message_inflight_otp')")).containsExactly("ix_message_inflight_otp");
        assertThat(query(db, "select indisvalid::text from pg_index where indexrelid = "
                + "'ix_message_inflight_otp'::regclass")).containsExactly("true");

        undoFrom(service, "013-otp-sweep-index");

        assertThat(query(db, "select indexname from pg_indexes where indexname in "
                + "('ix_message_pending_otp', 'ix_message_inflight_otp')")).containsExactly("ix_message_pending_otp");

        service.update();

        assertThat(service.history()).extracting(HistoryEntry::id).containsExactlyElementsOf(EXPECTED_IDS);
    }

    /** A concurrent build that failed leaves an invalid index behind; the changeset rebuilds it instead of adopting it. */
    @Test
    void theOtpSweepIndexChangesetRebuildsAnIndexLeftInvalid() throws Exception {
        String db = newDatabase();
        MigrationService service = service(db, true);
        service.update();
        undoFrom(service, "013-otp-sweep-index");
        execute(db, "create index ix_message_inflight_otp on notification_message (status, updated_at)");
        execute(db, "update pg_index set indisvalid = false where indexrelid = 'ix_message_inflight_otp'::regclass");

        service.update();

        assertThat(service.history()).extracting(HistoryEntry::type).last().isEqualTo("EXECUTED");
        assertThat(query(db, "select indisvalid::text from pg_index where indexrelid = "
                + "'ix_message_inflight_otp'::regclass")).containsExactly("true");
        assertThat(query(db, "select pg_get_indexdef('ix_message_inflight_otp'::regclass)").getFirst())
                .contains("WHERE");
    }

    @Test
    void theDeadLetterChangesetBackfillsExistingFailuresAndRollsBackCleanly() throws Exception {
        String db = newDatabase();
        MigrationService service = service(db, true);
        service.update();
        undoFrom(service, "008-dead-letters");
        seed(db);
        execute(db, "insert into notification_message (id, request_id, client_id, channel, recipient, variables, status, last_error, created_at, updated_at) "
                + "select '00000000-0000-0000-0000-0000000000f1', id, client_id, 'SMS', '+1', '{}'::jsonb, 'FAILED', 'Gave up after 5 attempts: timeout', now(), now() from notification_request limit 1");
        execute(db, "insert into notification_message (id, request_id, client_id, channel, recipient, variables, status, last_error, created_at, updated_at) "
                + "select '00000000-0000-0000-0000-0000000000f2', id, client_id, 'SMS', '+2', '{}'::jsonb, 'FAILED', 'invalid recipient', now(), now() from notification_request limit 1");

        service.update();

        assertThat(query(db, "select failure_kind from notification_message where id = '00000000-0000-0000-0000-0000000000f1'")).containsExactly("EXHAUSTED");
        assertThat(query(db, "select failure_kind from notification_message where id = '00000000-0000-0000-0000-0000000000f2'")).containsExactly("PERMANENT");
        assertThat(query(db, "select reprocess_count::text from notification_message where id = '00000000-0000-0000-0000-0000000000f2'")).containsExactly("0");

        undoFrom(service, "008-dead-letters");

        assertThat(query(db, "select column_name from information_schema.columns where table_name = 'notification_message' and column_name in ('failure_kind','reprocess_count')")).isEmpty();
        assertThat(query(db, "select indexname from pg_indexes where indexname = 'ix_message_dead_letters'")).isEmpty();
        assertThat(service.history()).hasSize(7);
    }

    @Test
    void theRetentionChangesetRemovesItsColumnsAndIndexesWhenRolledBackAndCanBeReapplied() throws Exception {
        String db = newDatabase();
        MigrationService service = service(db, true);
        service.update();
        assertThat(query(db, "select column_name from information_schema.columns where column_name = 'erased_at' and table_name in ('notification_message','notification_request')")).hasSize(2);
        assertThat(query(db, "select indexname from pg_indexes where indexname in ('ix_message_erase_due','ix_request_erase_due','ix_request_idempotency_due')")).hasSize(3);

        undoFrom(service, "007-personal-data-retention");

        assertThat(query(db, "select column_name from information_schema.columns where column_name = 'erased_at' and table_name in ('notification_message','notification_request')")).isEmpty();
        assertThat(query(db, "select indexname from pg_indexes where indexname in ('ix_message_erase_due','ix_request_erase_due','ix_request_idempotency_due')")).isEmpty();
        assertThat(service.history()).hasSize(6);

        service.update();

        assertThat(query(db, "select indexname from pg_indexes where indexname in ('ix_message_erase_due','ix_request_erase_due','ix_request_idempotency_due')")).hasSize(3);
        assertThat(service.history()).extracting(HistoryEntry::id).containsExactlyElementsOf(EXPECTED_IDS);
    }

    @Test
    void theSenderChangesetRemovesItsTableAndColumnsWhenRolledBackAndCanBeReapplied() throws Exception {
        String db = newDatabase();
        MigrationService service = service(db, true);
        service.update();
        assertThat(query(db, "select table_name from information_schema.tables where table_schema = 'public' and table_name = 'client_sender'")).hasSize(1);
        assertThat(query(db, "select column_name from information_schema.columns where table_name = 'notification_request' and column_name in ('sender_email','sender_name')")).hasSize(2);

        undoFrom(service, "006-client-senders");

        assertThat(query(db, "select table_name from information_schema.tables where table_schema = 'public' and table_name = 'client_sender'")).isEmpty();
        assertThat(query(db, "select column_name from information_schema.columns where table_name = 'notification_request' and column_name in ('sender_email','sender_name')")).isEmpty();
        assertThat(service.history()).hasSize(5);

        service.update();

        assertThat(query(db, "select table_name from information_schema.tables where table_schema = 'public' and table_name = 'client_sender'")).hasSize(1);
        assertThat(service.history()).extracting(HistoryEntry::id).containsExactlyElementsOf(EXPECTED_IDS);
    }

    @Test
    void theAdminAccountsChangesetRemovesItsTablesWhenRolledBackAndCanBeReapplied() throws Exception {
        String db = newDatabase();
        MigrationService service = service(db, true);
        service.update();
        assertThat(query(db, "select table_name from information_schema.tables where table_schema = 'public' and table_name in ('admin_user','admin_recovery_code','admin_session')")).hasSize(3);

        undoFrom(service, "005-admin-accounts");

        assertThat(query(db, "select table_name from information_schema.tables where table_schema = 'public' and table_name in ('admin_user','admin_recovery_code','admin_session')")).isEmpty();
        assertThat(service.history()).hasSize(4);

        service.update();

        assertThat(query(db, "select table_name from information_schema.tables where table_schema = 'public' and table_name in ('admin_user','admin_recovery_code','admin_session')")).hasSize(3);
        assertThat(service.history()).extracting(HistoryEntry::id).containsExactlyElementsOf(EXPECTED_IDS);
    }

    @Test
    void theBillingChangesetRemovesEverythingItCreatedWhenRolledBackAndCanBeReapplied() throws Exception {
        String db = newDatabase();
        MigrationService service = service(db, true);
        service.update();
        assertThat(billingTablesPresent(db)).containsExactlyInAnyOrderElementsOf(BILLING_TABLES);

        undoFrom(service, "004-billing");

        assertThat(billingTablesPresent(db)).isEmpty();
        assertThat(query(db, "select indexname from pg_indexes where indexname = 'ix_message_sent_usage'")).isEmpty();
        assertThat(query(db, "select sequencename from pg_sequences where sequencename = 'invoice_number_seq'")).isEmpty();
        assertThat(service.history()).hasSize(3);

        service.update();

        assertThat(billingTablesPresent(db)).containsExactlyInAnyOrderElementsOf(BILLING_TABLES);
        assertThat(service.history()).extracting(HistoryEntry::id).containsExactlyElementsOf(EXPECTED_IDS);
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

    /** Rolls back {@code changesetId} and every changeset applied after it. */
    private static void undoFrom(MigrationService service, String changesetId) {
        int index = EXPECTED_IDS.indexOf(changesetId);
        assertThat(index).as("known changeset %s", changesetId).isNotNegative();
        service.rollbackCount(EXPECTED_IDS.size() - index);
    }

    /** A Flyway-created database: the three Flyway-era changesets are adopted, everything later runs. */
    private static List<String> adoptedLegacyHistory() {
        List<String> types = new java.util.ArrayList<>(java.util.Collections.nCopies(3, "MARK_RAN"));
        types.addAll(java.util.Collections.nCopies(EXPECTED_IDS.size() - 3, "EXECUTED"));
        return types;
    }

    private static void execute(String database, String sql) throws SQLException {
        try (Connection c = connect(database); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static List<String> billingTablesPresent(String database) throws SQLException {
        return query(database, "select table_name from information_schema.tables where table_schema='public' and table_name in "
                + "('billing_plan','billing_plan_rate','billing_account','credit_ledger_entry','credit_hold','invoice','invoice_line','invoice_payment')");
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
                + "where table_schema='public' and column_name not in "
                + "('sender_email','sender_name','erased_at','failure_kind',"
                + "'reprocess_count','error_code','category','expires_at') and table_name not in " + NOT_COMPARED));
        lines.addAll(query(database, "select 'idx ' || tablename || ' ' || indexdef from pg_indexes "
                + "where schemaname='public' and indexname not in ('ix_message_sent_usage','ix_message_erase_due',"
                + "'ix_request_erase_due','ix_request_idempotency_due','ix_message_dead_letters',"
                + "'ix_message_pending_otp','ix_message_inflight_otp') and tablename not in " + NOT_COMPARED));
        lines.addAll(query(database, "select 'con ' || conrelid::regclass || ' ' || conname || ' ' || pg_get_constraintdef(oid) "
                + "from pg_constraint where connamespace = 'public'::regnamespace and conrelid::regclass::text not in "
                        + NOT_COMPARED + " and conname not in ('ck_message_failure_kind', 'ck_request_category',"
                + " 'ck_message_category', 'ck_template_category')"));
        Collections.sort(lines);
        return lines;
    }
}
