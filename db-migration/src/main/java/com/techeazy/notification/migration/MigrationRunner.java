package com.techeazy.notification.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.util.List;

/**
 * Parses the command line, runs one command and reports the result as the process exit code (0 ok, 1 failed, 2 bad
 * usage), so a container platform or CI step can gate the rollout on it.
 */
@Component
public class MigrationRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

    static final String USAGE = """
            Usage: db-migration [command]
              update                 apply pending changesets (default); tags the previous state first
              update-sql             print the SQL that update would run, without running it
              status                 list changesets that are still to be applied
              validate               check the changelog and that applied changesets were not edited
              history                list applied changesets
              tag <name>             tag the current state
              rollback-count <n>     undo the last n changesets (needs MIGRATION_ALLOW_ROLLBACK=true)
              rollback-tag <name>    undo everything after the tag (needs MIGRATION_ALLOW_ROLLBACK=true)
              release-locks          clear a stale changelog lock (only when nothing is running)""";

    private final MigrationService service;
    /** Where command results (status, history, SQL preview) go: standard output, so they can be piped or redirected. */
    private final PrintWriter out;
    private volatile int exitCode;

    public MigrationRunner(MigrationService service, PrintWriter out) {
        this.service = service;
        this.out = out;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> a = args.getNonOptionArgs();
        String command = a.isEmpty() ? "update" : a.get(0);
        try {
            exitCode = dispatch(command, a);
        } catch (IllegalStateException e) {
            log.error(e.getMessage());
            exitCode = 1;
        } catch (RuntimeException e) {
            log.error("{}: {}", command, e.getMessage(), e);
            exitCode = 1;
        }
    }

    private int dispatch(String command, List<String> a) {
        switch (command) {
            case "update" -> service.update();
            case "update-sql" -> {
                out.println("--- BEGIN SQL ---");
                out.println(service.updateSql());
                out.println("--- END SQL ---");
                out.flush();
            }
            case "status" -> printStatus();
            case "validate" -> service.validate();
            case "history" -> printHistory();
            case "release-locks" -> service.releaseLocks();
            case "tag" -> {
                if (a.size() != 2) return usage("tag needs a name");
                service.tag(a.get(1));
            }
            case "rollback-tag" -> {
                if (a.size() != 2) return usage("rollback-tag needs a tag");
                service.rollbackToTag(a.get(1));
            }
            case "rollback-count" -> {
                Integer n = a.size() == 2 ? parsePositive(a.get(1)) : null;
                if (n == null) return usage("rollback-count needs a positive number");
                service.rollbackCount(n);
            }
            default -> {
                return usage("unknown command '" + command + "'");
            }
        }
        return 0;
    }

    private void printStatus() {
        List<String> pending = service.status();
        out.println(pending.isEmpty() ? "Up to date: no pending changesets" : pending.size() + " pending changeset(s):");
        pending.forEach(p -> out.println("  " + p));
        out.flush();
    }

    private void printHistory() {
        service.history().forEach(h -> out.printf("%-34s %-14s %-8s %s%s%n", h.id(), h.author(), h.type(),
                h.executedAt(), h.tag() == null ? "" : "  [tag " + h.tag() + "]"));
        out.flush();
    }

    private int usage(String problem) {
        log.error("{}\n{}", problem, USAGE);
        return 2;
    }

    private static Integer parsePositive(String text) {
        try {
            int n = Integer.parseInt(text);
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
