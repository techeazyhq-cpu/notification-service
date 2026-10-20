# ADR-040: Running on servers shared with consent-manager

- **Status:** Accepted
- **Date:** 2026-10-20

## Context

consent-manager sends its email, SMS, WhatsApp and push through this service. Developed side by side on one laptop,
each started its own PostgreSQL, Redis, Pulsar and Mailpit: two of every server, with Pulsar alone wanting well over a
gigabyte, on a machine with 16 GB. consent-manager also had to move every port of this stack up by 10000 to avoid its
own. The same duplication would follow both into a shared Kubernetes cluster.

This service must still run alone. It is open source, and most people who run it have no consent-manager.

## Decision

Keep standalone as the default and add a shared mode next to it, using servers that consent-manager's repository runs
in a project or namespace named `common`. This repository only connects to them; it does not create them.

**What each side owns.** `common` runs PostgreSQL 16, Redis 7, Pulsar 3.3.3 standalone, Keycloak and Mailpit. For this
service it creates the database `notification`, owned by the role `notification`, and the DML-only role
`notification_app` (ADR-012), as `scripts/postgres-init` does when standalone, and sets the retention of Pulsar's
`public/default` to 60 minutes and 1024 MB, as `docker-compose.yml` does. This service keeps its topics in
`public/default` and uses Redis database `2`; consent-manager uses `0` and its sandbox `1`. The schema stays this
repository's: `db-migrate` (or the chart's migration hook) applies it as `notification`.

**Redis database.** `REDIS_DATABASE` (`spring.data.redis.database`, default `0`) selects the index in client-api,
admin-api and the dispatcher; the chart sets it from `config.redis.database`. An index is a namespace, not a security
boundary: anyone who can reach the server can select any index. That is acceptable between two applications of the
same owner on a development machine or cluster; production keeps a Redis of its own.

**Docker Compose.** `docker-compose.shared.yml` overrides `docker-compose.yml`:

- the project is named `notification`, so it never touches the standalone `notification-service` project's containers
  or volumes, and the two can even run at once;
- `postgres`, `redis`, `pulsar` and `mailpit` move to a profile that is never enabled, and nothing depends on them;
- the migration job and the three services also join the external network `common` and reach the shared servers by
  the same names they use standalone (`postgres`, `redis`, `pulsar`, `mailpit`), so only `REDIS_DATABASE` really
  changes; the catcher and UIs stay on the project's own network;
- host ports move clear of consent-manager's: client API 18080, admin API 18081, dispatcher management 18082, catcher
  19000, UIs 15173 and 15174;
- every container has a CPU and memory limit: 1 CPU and 768 MB for the Java containers, 0.25 CPU and 128 MB for the
  rest, so neither stack can starve the other.

**Kubernetes.** Three namespaces: `common`, `consent-manager` and `notification`. The chart is unchanged in shape:
`deploy/k8s/dev/values-shared.yaml`, layered on the k3s development values, points the services at
`<server>.common.svc.cluster.local`, sets Redis database `2`, and allows egress to `common` on the PostgreSQL, Redis,
Pulsar and SMTP ports, besides DNS. It selects `common` by namespace and port, not by pod labels, so it does not depend
on how the `common` chart labels its pods. `common` admits traffic only from the namespaces `consent-manager` and
`notification`. The catcher, which `common` does not run, comes from `deploy/k8s/dev/catcher.yaml` in `notification`.

## Consequences

- One set of servers instead of two on the developer's machine, and no remapped ports for the shared servers.
- Standalone use, the default compose command and the cloud values are unchanged.
- Shared mode depends on another repository for its servers and its database setup. The contract above (names,
  roles, database, Redis index, Pulsar namespace) is what both sides rely on; changing it needs both changed.
- Wiping the shared PostgreSQL or Pulsar volume wipes consent-manager's data too; `docker compose ... down -v` in
  shared mode removes only this project's volumes, of which it has none.
- On the shared PostgreSQL, `notification` and `notification_app` are granted rights on the `notification` database
  only, not on consent-manager's tables, and `common` revokes `PUBLIC` access to the `notification` database. The
  server's superuser still reaches both, as on any shared server.
- Shared mode is for development. In production each application keeps its own managed datastores (ADR-025 to
  ADR-030).
