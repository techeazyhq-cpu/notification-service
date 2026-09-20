# ADR-003: A dedicated Liquibase migration job replaces per-service Flyway

- **Status:** Accepted
- **Date:** 2026-09-20

## Context

Schema changes were Flyway scripts inside `notification-core`, applied by whichever service started first. That worked for a walking skeleton but has known problems for an enterprise deployment:

- **Three services, one schema.** All three run the migration at start-up. Flyway locks, so it is safe, but a rolling deploy means an old and a new version of the same service run against a schema that just changed, and every service needs DDL rights on the database at runtime.
- **No control over rollout.** Migrations cannot be previewed, gated, validated or run on their own; they happen as a side effect of an application start.
- **No way back.** Flyway Community has no undo, so a bad release needs hand-written SQL under pressure.

## Decision

1. **One-shot `db-migration` job** (a Spring Boot command-line app around the Liquibase library, shipped as its own image) applies schema changes. It runs to completion before the services start (`docker compose`: `service_completed_successfully`; on Kubernetes: a pre-install/upgrade Job or init step) and its exit code gates the rollout: 0 ok, 1 failed, 2 bad usage.
2. **Commands:** `update` (default), `update-sql` (print the SQL without running it, for review), `status`, `validate` (changelog is valid and no applied changeset was edited), `history`, `tag`, `rollback-count`, `rollback-tag`, `release-locks`.
3. **Every changeset has a rollback**, written by hand (SQL changesets need it). `update` tags the previous state (`pre-<utc timestamp>`) before applying anything, so a release can be undone with `rollback-tag`. Rollback deletes or alters data, so it is refused unless `MIGRATION_ALLOW_ROLLBACK=true`.
4. **Services no longer migrate.** Flyway is removed from `notification-core`, and every service runs Hibernate `ddl-auto: validate`, so it refuses to start if the schema is missing or behind the code.
5. **Adopting existing databases.** The three Flyway scripts became Liquibase changesets (`001-baseline`, `002-client-tracking-indexes`, `003-client-owned-templates`), each guarded by a precondition on `onFail: MARK_RAN`. On a database Flyway already migrated they are recorded as applied without running; on an empty database they run. A test proves the resulting schema is identical to what the old scripts produced. The old `flyway_schema_history` table is left in place, unused.
6. **Serialising runs:** a PostgreSQL session advisory lock is taken before Liquibase touches anything (Liquibase's own lock table is created lazily, and two runs starting together can collide creating it; this was found by a test).
7. **Format:** SQL-formatted changesets (`--liquibase formatted sql`) listed from a YAML master. The schema uses PostgreSQL-specific features (`jsonb`, partial and expression indexes), so database-neutral XML/YAML changes would add translation without giving portability.

## Options considered

- **Keep Flyway, run it as a job.** Fixes ordering and privileges but not undo or preview in the free edition. Chosen against because the requirement was Liquibase and rollback is a real need.
- **Liquibase inside each service (Spring Boot auto-configuration).** Least code, but keeps every problem above.
- **Official `liquibase/liquibase` image + mounted changelog.** Less code and full CLI, but no place for our guards (rollback refusal, advisory lock, pre-update tag, connect retries) and no tests in our build.

## Consequences

Positive: schema changes are an explicit, reviewable, gated step; previews and rollbacks exist; services need no DDL rights at runtime once a separate runtime database role is introduced (follow-up); services fail at start-up, not at first query, when the schema is wrong.

Negative / accepted: one more artifact to build and run; `ddl-auto: validate` catches missing tables and columns but not every difference (for example indexes or constraints); rollback scripts are hand-written and only as good as their tests; a bad rollback of a data-changing changeset cannot restore deleted rows (changeset 003 documents this); the job has to be wired into any new deployment target (compose is done; Kubernetes/CI is not).

## Follow-ups

Separate database roles (migration job owns the schema; services get only DML rights); run `validate` and `update-sql` in CI against a production-like snapshot; wire the job into the real deployment pipeline; drop the unused `flyway_schema_history` table once every environment has been adopted.
