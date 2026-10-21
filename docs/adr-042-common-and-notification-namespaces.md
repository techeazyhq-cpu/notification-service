# ADR-042: Two Kubernetes namespaces, `common` and `notification`

- **Status:** Accepted
- **Date:** 2026-10-21

## Context

The platform's own workloads always ran in the namespace `notification`, but the servers they depend on were spread
over names that changed with the setting:

- the local k3s setup put PostgreSQL, Redis, Pulsar, Mailpit and the SMS/WhatsApp/push catcher in `datastores`;
- the self-managed PostgreSQL manifests (CloudNativePG, `deploy/k8s/postgres`) and their backups also used
  `datastores`;
- the AWS, Azure and Google Cloud guides installed Pulsar into `pulsar`;
- shared mode (ADR-040) used `common`, the namespace consent-manager's chart runs.

So the same server had a different address, network policy and runbook command in each setting, and moving from
standalone to shared mode meant changing all of them.

## Decision

1. **Two namespaces everywhere.** `common` holds the servers the services depend on that run in the cluster: Pulsar
   always, and PostgreSQL, Redis and Mailpit wherever they are not managed services. `notification` holds the platform:
   client-api, admin-api, the dispatcher, the migration job and the two UIs (the Helm chart), plus the development
   catcher.
2. **One manifest creates them.** `deploy/k8s/namespaces.yaml` defines both, labelled
   `app.kubernetes.io/part-of: notification-service` and `notification-service/role` (`infrastructure` or
   `services`). The local guide and the three cloud guides apply it first; `--create-namespace` is gone from the
   Pulsar install.
3. **Renames.** `datastores` becomes `common` in the development servers (`deploy/k8s/dev/datastores.yaml`, now
   `common.yaml`), the development backup store, the CloudNativePG cluster, object store, pod monitor and recovery
   manifests, the PostgreSQL recovery alert tests and the disaster-recovery runbook. `pulsar` becomes `common` in the
   cloud guides, the cloud Helm values (`pulsar-broker.common.svc.cluster.local:6651`), the network-policy egress that
   Terraform outputs (`namespaceSelector` on `common`, pods labelled `component: broker`) and CI's Pulsar render. The
   Pulsar chart derives its certificates' names from the release namespace, so they follow.
4. **The catcher moves to `notification`.** It stands in for external providers and is not a shared server, so
   `common` holds only real infrastructure in every setting, and `catcher.yaml` is the one catcher manifest, standalone
   and shared.
5. **`common` admits only the platform's namespaces (development).** `common.yaml` adds a NetworkPolicy that accepts
   connections from inside `common` and from `notification` and `consent-manager`, the same rule consent-manager's
   chart applies in shared mode (ADR-040). The services' own egress policies already limit what they reach in `common`
   by port.

## Options considered

- **Keep the names and document them.** No migration, but the inconsistency stays and every new manifest or runbook has
  to know which setting it is in.
- **One namespace per server** (`postgres`, `redis`, `pulsar`). Finer policies, but more names to manage, and it does
  not match the shared cluster consent-manager already runs.
- **Put everything in `notification`.** Simplest, but mixes stateful servers with stateless releases: a `helm
  uninstall` or a namespace delete of the platform would take the broker and the database with it, and policies could
  no longer tell the two apart.
- **A production NetworkPolicy for `common`.** Desirable, but the CloudNativePG operator, Pulsar's own components and
  the monitoring stack all need ingress there, and the right rule depends on where the operators run. Left as a
  follow-up rather than shipped untested.

## Consequences

Positive: one layout and one set of addresses in development, shared mode and production; standalone and shared mode
differ only in who installs `common`; the platform can be removed or reinstalled without touching the servers.

Negative / accepted:

- **Existing clusters must migrate.** A running Pulsar in `pulsar` or a CloudNativePG cluster in `datastores` does not
  move by itself. Either keep the old namespace and override the values (`config.pulsar.url`, the egress
  `namespaceSelector`), or reinstall into `common`. Pulsar holds nothing that cannot be rebuilt from PostgreSQL
  (ADR-026); PostgreSQL moves by restoring a backup into the new cluster (docs/disaster-recovery.md). A local k3s is
  simply recreated.
- Shared mode applies `namespaces.yaml` next to consent-manager's chart, which also creates `common`; applying it again
  only adds the labels.

## Follow-ups

- A NetworkPolicy for `common` in production that admits `notification`, the operators and monitoring.
