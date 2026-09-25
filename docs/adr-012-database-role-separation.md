# ADR-012: Separate database roles for the migration job and the services

- **Status:** Accepted
- **Date:** 2026-09-25

## Context

Every service and the migration job connected to PostgreSQL as the same role, `notification` — the role the official
Postgres image creates from `POSTGRES_USER`/`POSTGRES_PASSWORD` on first start, which owns the database and therefore
has full DDL rights on every table in it. This was flagged as a follow-up when the migration job was introduced
(ADR-003) and independently as finding S2 in `docs/architecture-review.md`: a single SQL injection, a compromised
pod, or a bug in application code that runs the wrong statement can alter or drop schema and read or write any
table, because the runtime role that `client-api`, `admin-api` and `dispatcher` connect as is indistinguishable from
the one that owns the schema.

## Decision

1. **Two roles.** `notification` keeps owning the schema and is used only by the `db-migration` job. A new role,
   `notification_app`, is granted `SELECT`/`INSERT`/`UPDATE`/`DELETE` on every table and `USAGE`/`SELECT` on every
   sequence in the `public` schema, and nothing else: no `CREATE`, no `DROP`, no `ALTER`, no ownership. `client-api`,
   `admin-api` and `dispatcher` connect as `notification_app`.
2. **Creation.** For `docker compose`, `scripts/postgres-init/01-create-app-role.sh` runs once through the official
   Postgres image's `docker-entrypoint-initdb.d` convention (first start against an empty data directory): it
   creates the role if missing and grants it access, plus `ALTER DEFAULT PRIVILEGES FOR ROLE notification` so tables
   and sequences a *future* Liquibase changeset creates are covered automatically, with no changelog awareness
   needed. For a database this project doesn't provision (a managed instance, an existing cluster), an operator runs
   the same SQL once by hand; the script is the executable form of that SQL, not compose-only logic.
3. **Defaults changed, not just compose.** `client-api`, `admin-api` and `dispatcher`'s `application.yml` default
   `DB_USER`/`DB_PASSWORD` to `notification_app` (the migration job's defaults are untouched: `notification`). This
   keeps the "infra only, run services from your IDE" workflow in the README on the least-privilege role too, since
   `docker compose up -d` (no `--profile app`) still starts Postgres and therefore still runs the init script.

## Options considered

- **Grant DML on the existing `notification` role and create a second, DDL-only role for migrations instead.**
  Rejected: it would mean rotating and re-provisioning the credential every current deployment and every operator's
  muscle memory already uses, for no benefit over doing it the other way around.
- **A Postgres extension or managed IAM role (e.g. cloud-provider IAM database authentication).** More robust
  long-term, but ties the open-source default to one cloud provider's mechanism; plain role/password is what works
  identically on a laptop, bare-metal Postgres and every managed offering.
- **Row-level security instead of, or in addition to, coarse table grants.** Solves a different problem (tenant
  isolation inside a table) than this one (the runtime role should not be able to do DDL). Worth its own ADR if
  cross-tenant data leakage through a bug becomes a concern; not needed to close S2.

## Consequences

Positive: a compromised or buggy service process cannot alter schema, drop data, or grant itself more access; the
blast radius of a SQL injection is limited to the rows the DML grants already expose through the application's own
queries, not the whole database; closes ADR-003's stated follow-up and S2.

Negative / accepted: one more role and password to manage per environment; the `docker-entrypoint-initdb.d` script
only runs against a fresh Postgres data directory, so an already-provisioned environment needs the grants applied by
hand once (the script's SQL, run manually, is exactly that statement); `ALTER DEFAULT PRIVILEGES` only covers objects
future changesets create as the `notification` role — if a changeset ever creates an object as a different role, its
grants need to be added explicitly, the same way a new table's indexes are.

## Follow-ups

Document the manual grant statements for a non-compose deployment (e.g. RDS/Cloud SQL) in the production deployment
guide once one exists; consider a distinct read-only role for reporting/dashboard queries if a read replica is
introduced (see finding C1); rotate `DB_APP_PASSWORD` the same way any other production secret is rotated once
secrets management (finding S3/S4) is addressed.
