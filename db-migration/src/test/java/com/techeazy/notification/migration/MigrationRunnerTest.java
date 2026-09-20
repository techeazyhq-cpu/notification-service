package com.techeazy.notification.migration;

import com.techeazy.notification.migration.MigrationService.MigrationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MigrationRunnerTest {

    MigrationService service = mock(MigrationService.class);
    StringWriter printed = new StringWriter();
    MigrationRunner runner = new MigrationRunner(service, new PrintWriter(printed));

    @BeforeEach
    void quietDefaults() {
        when(service.status()).thenReturn(List.of());
        when(service.history()).thenReturn(List.of());
    }

    private int run(String... args) {
        runner.run(new DefaultApplicationArguments(args));
        return runner.getExitCode();
    }

    @Test
    void noArgumentsMeansUpdateAndSuccessIsExitCodeZero() {
        assertThat(run()).isZero();
        verify(service).update();
    }

    @Test
    void aFailedMigrationIsExitCodeOneSoTheRolloutCanStop() {
        doThrow(new MigrationException("boom", null)).when(service).update();

        assertThat(run("update")).isEqualTo(1);
    }

    @Test
    void aRefusedRollbackIsAFailureNotAUsageError() {
        doThrow(new IllegalStateException("Rollback ... is disabled")).when(service).rollbackCount(1);

        assertThat(run("rollback-count", "1")).isEqualTo(1);
    }

    @Test
    void badUsageIsExitCodeTwoAndDoesNotTouchTheDatabase() {
        assertThat(run("frobnicate")).isEqualTo(2);
        assertThat(run("tag")).isEqualTo(2);
        assertThat(run("rollback-tag")).isEqualTo(2);
        assertThat(run("rollback-count")).isEqualTo(2);
        assertThat(run("rollback-count", "abc")).isEqualTo(2);
        assertThat(run("rollback-count", "0")).isEqualTo(2);
        verify(service, never()).update();
        verify(service, never()).rollbackCount(anyInt());
        verify(service, never()).rollbackToTag(anyString());
        verify(service, never()).tag(anyString());
    }

    @Test
    void statusHistoryAndSqlPreviewAreWrittenToTheCommandOutput() {
        when(service.status()).thenReturn(List.of("003-client-owned-templates (notification)"));
        when(service.history()).thenReturn(List.of(new MigrationService.HistoryEntry("001-baseline", "notification", "2026-01-01 10:00:00", "EXECUTED", "pre-1")));
        when(service.updateSql()).thenReturn("CREATE TABLE x (id int);");

        run("status");
        run("history");
        run("update-sql");

        assertThat(printed.toString())
                .contains("1 pending changeset(s):", "003-client-owned-templates (notification)")
                .contains("001-baseline", "EXECUTED", "[tag pre-1]")
                .contains("--- BEGIN SQL ---", "CREATE TABLE x (id int);", "--- END SQL ---");
    }

    @Test
    void commandsAreRoutedToTheMatchingOperation() {
        assertThat(run("status")).isZero();
        assertThat(run("validate")).isZero();
        assertThat(run("history")).isZero();
        assertThat(run("tag", "v1")).isZero();
        assertThat(run("rollback-tag", "v1")).isZero();
        assertThat(run("rollback-count", "2")).isZero();
        assertThat(run("release-locks")).isZero();
        verify(service).validate();
        verify(service).tag("v1");
        verify(service).rollbackToTag("v1");
        verify(service).rollbackCount(2);
        verify(service).releaseLocks();
    }
}
