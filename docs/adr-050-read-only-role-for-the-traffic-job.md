# ADR-050: A read-only database role for the traffic job

- **Status:** Accepted
- **Date:** 2026-10-27
- **Amends:** ADR-039, ADR-012

## Context

ADR-039's hourly job in `admin-api` reads an hour of `notification_message` and `notification_request` and writes the
totals to `traffic_hourly`. It ran as `notification_app`, which can write to every table (ADR-012). ADR-039 left "a
read-only role, or a read replica, for reporting jobs" as a follow-up, and ADR-012 left a read-only role for reporting
queries for when a replica exists.

Two things are worth having. A bug in the job's reads cannot change any data. And the reads can move off the primary,
once there is a replica to move them to. ADR-039 also asked for the job's queries to be checked with `EXPLAIN` on
production-sized data before adding an index.

## Decision

1. **Reads and writes go through separate connections.** `TrafficAggregator` reads tenants' messages and requests
   through one `JdbcClient`, called the traffic source, and writes `traffic_hourly` through the service's own. Scoring,
   the anomaly records and the reports read and write only the job's own tables, so they stay on the service's
   connection.
2. **The traffic source is opt-in.** With no `DB_REPORTING_USER` it is the service's own connection, as before, so an
   existing deployment keeps working unchanged. With one set, it is a pool of its own (two connections, created on
   first use). It logs in as that user at `DB_REPORTING_URL`, which can be a read replica, or else at `DB_URL`.
3. **Read-only three times over:**
   - the role has `SELECT` and nothing else;
   - the role's transactions are read-only by default (`default_transaction_read_only = on`);
   - the pool marks every connection read-only, with the driver's `readOnlyMode=always`. The PostgreSQL driver
     enforces read-only only inside explicit transactions unless that is set, and the job's queries run in autocommit.
4. **The role is `notification_reporting`.** It gets `SELECT` on every table through `ALTER DEFAULT PRIVILEGES`, the
   same way `notification_app` gets its rights, so a later changeset needs no grant of its own.
   - `scripts/postgres-init/02-create-reporting-role.sh` creates it when `DB_REPORTING_USER` is set.
   - The CloudNativePG cluster creates it with a managed role (secret `notification-db-reporting`).
   - The chart passes `config.database.reportingUser` and `config.database.reportingUrl` to `admin-api` only. The
     password comes from the Secret's `DB_REPORTING_PASSWORD`.
   - On CloudNativePG, `reportingUrl` can point at the `notification-db-ro` service, which serves only the standbys.
5. **Development passwords are refused outside `local`.** The shipped password `notification_reporting` is refused
   outside the `local` profile, like the other database passwords (ADR-013).
6. **No new index.** We seeded 2 million messages from 100 tenants over 60 days (about 1,400 an hour) and ran
   `EXPLAIN ANALYZE` on each of the job's queries.
   - The hourly counts use `ix_message_created`.
   - The new-recipient check uses `ix_message_client_fingerprint`, as an index-only scan.
   - The largest request uses `ix_request_created`.
   - One hour takes about 20 ms in all, so the 56-day backfill takes about 30 seconds.

## Options considered

- **One role with `SELECT` on the source tables and write rights on `traffic_hourly` only.** One connection, but it is
  not read-only, and it cannot point at a replica, which accepts no writes at all.
- **Grant `SELECT` only on `notification_message` and `notification_request`, or only on the columns the job reads.**
  The tightest. But those tables exist only after the migration job runs, and the Postgres image's init scripts run
  before it, so the grant would need a changeset that knows the role's name, or a manual step. Granting every table
  matches how ADR-012 grants `notification_app`, and the role still reads nothing the services cannot read already.
- **Make the role mandatory.** Every existing deployment, and the shared PostgreSQL of ADR-040, would need the role
  before upgrading, or the job would fail every hour. Opt-in keeps the upgrade a no-op.
- **Use PostgreSQL's built-in `pg_read_all_data`.** It reads every schema in the database, including any other
  application's on a shared server (ADR-040).

## Consequences

Positive:

- With the role configured, nothing the traffic job runs can change data, even if the job has a bug.
- The job's reads can move to a replica by changing one URL. It re-measures the last few hours on every run, so
  replication lag of a few minutes does not change the results.
- An index for the job is settled: none is needed.

Negative / accepted:

- One more role, password and secret key per environment that turns it on.
- Without `DB_REPORTING_USER` nothing changes, so the protection is only as wide as its adoption.
- Docker Compose turns it on by default. A Postgres volume created before this change has no such role, so the job
  fails every hour until someone runs the script's SQL once, or sets `DB_REPORTING_USER` empty. The shared overlay
  (`docker-compose.shared.yml`) sets it empty, because consent-manager's PostgreSQL does not create the role.
- The role can read every table, not only the two the job needs.
