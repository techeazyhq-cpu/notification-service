# ADR-043: Two Docker Compose projects, `common` and `notification`

- **Status:** Accepted
- **Date:** 2026-10-21

## Context

ADR-042 gave Kubernetes two namespaces: `common` for the servers the platform depends on, `notification` for the
platform. Docker Compose, the way most people run the project, still had one project, `notification-service`, with
PostgreSQL, Redis, Pulsar and Mailpit next to the migration job, the services, the UIs and the catcher on one network.
So rebuilding the services meant the same `down` that stops the database and the broker, `down -v` on the services
wiped the data, and standalone and shared mode (ADR-040) were different projects with different volume names.

consent-manager already runs its servers as a Compose project named `common`, on a network named `common`, with
services named `postgres`, `redis`, `pulsar` and `mailpit` (its `infra/common/compose.yml`).

## Decision

1. **Two projects.** `docker-compose.common.yml` is the project `common`: PostgreSQL, Redis, Pulsar and Mailpit, on a
   network named `common`, with the same images, ports, limits and health checks as before. `docker-compose.yml` is the
   project `notification`: the migration job, client-api, admin-api, the dispatcher, both UIs and the catcher. The
   migration job and the three services join the external network `common` and reach the servers by the names they
   always used, so their configuration does not change. The UIs and the catcher stay on the project's own network.
2. **Start `common` first.** Compose cannot wait on a health check in another project, so the services no longer
   `depends_on` the servers. `docker compose -f docker-compose.common.yml up -d --wait` returns once they are healthy;
   then `docker compose --profile app up -d --build`. The migration job still gates the services.
3. **The same name as consent-manager's, one at a time.** This repository's `common` has the same project, network and
   service names as consent-manager's, so either can provide `common` and the services cannot tell them apart. The
   cost is that only one may exist on a machine: starting one while the other's containers exist makes Compose
   recreate those containers with its own settings. The README, the compose file and this ADR say to stop the other
   with `docker compose -p common down` (without `-v`) first.
4. **Volumes named for this project.** `notification-pgdata`, `notification-pulsardata` and, with encryption,
   `notification-certs`, never consent-manager's `common_*` volumes, so switching which `common` runs never mixes data.
5. **Shared mode shrinks.** With consent-manager's `common` running instead, `docker-compose.shared.yml` only sets Redis
   database `2` and moves the host ports. It no longer disables servers or rewires networks.
6. **Encryption splits too.** `docker-compose.common.encrypted.yml` creates the private CA and certificates in
   `notification-certs` and turns on TLS in PostgreSQL, Redis and Pulsar; `docker-compose.encrypted.yml` keeps the
   services' side (verify-full, TLS URLs, the CA mounted from the external volume). The edge TLS overlays only change
   their project name to `notification`.
7. **Pulsar gets time to stop.** `stop_grace_period: 60s` (as consent-manager has), so `docker compose stop` lets the
   bookie close its ledgers. A Pulsar killed mid-write left a ledger it could not read, and only deleting its volume
   brought it back.
8. **Scripts follow.** The chaos drills stop Pulsar and restart PostgreSQL in `INFRA_PROJECT` (default `common`) and
   the dispatcher in `PROJECT` (default `notification`); the seed script's `psql` runs in `common-postgres-1`.

## Options considered

- **A different name, such as `notification-common`.** Could run next to consent-manager's `common` without any risk of
  Compose replacing the other's containers, but then two sets of servers run side by side again (what ADR-040 removed)
  and shared mode needs a different network. Rejected in favour of one `common` contract.
- **`include:` the servers into the services' file.** One command again, but `include` merges them into the same
  project, which is exactly what this splits.
- **A wrapper script that starts both.** Saves a command, but hides the split this is meant to show and adds a script
  per platform. The two commands are documented instead.
- **Keep one project and only document `docker compose stop <service>`.** No migration, but `down` and `down -v` keep
  taking the data with the services, and standalone and shared mode stay different.

## Consequences

Positive: the same layout as Kubernetes; the services can be rebuilt, recreated or taken down without touching the
database or broker; standalone and shared mode are the same `notification` project on whichever `common` runs.

Negative / accepted:

- Two commands to start the stack and two to stop it.
- **Existing setups must migrate.** The old project's data is in `notification-service_pgdata` and
  `notification-service_pulsardata`. Stop the old project with `docker compose -p notification-service --profile app
  down` (without `-v`), copy the database volume into `notification-pgdata` (for example
  `docker run --rm -v notification-service_pgdata:/from -v notification-pgdata:/to alpine cp -a /from/. /to/`), and
  remove the old volumes once the new stack works. Pulsar's data can simply be dropped: everything accepted is in
  PostgreSQL and republished by the outbox (ADR-026).
- Running this repository's `common` while consent-manager's `common` containers exist would replace them. Only the
  documentation prevents it.
