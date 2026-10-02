# ADR-025: Kubernetes deployment with a Helm chart, hardened pods and default-deny network policies

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

The platform could only be run with `docker compose`: one instance of each service, no rolling updates, no
disruption budgets, no resource limits, no network segmentation, every container able to reach every other one, and
no artefact that a platform team could install. The architecture review listed this as finding D1 (no
infrastructure as code, no HA topology), and the 2026-10-01 re-review as production blocker 5.

This repository does not commit to a cloud. The deployment artefact therefore has to work on any conformant
Kubernetes, and the datastores have to be pluggable: managed services on a cloud, operators on-premises.

## Decision

1. **A Helm chart, `deploy/helm/notification-service`,** for the six components:
   - client-api, admin-api and the dispatcher as Deployments and Services;
   - the two UIs as Deployments and Services;
   - the Liquibase migration as a `pre-install,pre-upgrade` hook Job.

   PostgreSQL, Redis and Pulsar are deliberately not in the chart. Their availability and backups are a separate
   decision (blocker 5's second half).
2. **Migrations gate releases.** The hook runs as the schema owner (ADR-012) before any service is replaced. If it
   fails, the release fails, and the services still run the old version against the old schema. The finished Job is
   kept for a day so its log can be read.
3. **Probes on the management port (ADR-021):**
   - a startup probe that allows three minutes for the first schema check and the broker connection;
   - liveness on `/actuator/health/liveness` and readiness on `/actuator/health/readiness`, which Spring Boot now
     serves explicitly in every service.

   Probes check the process, not its dependencies. A database outage must not restart or unroute every pod at once,
   because accepts wait for the connection pool and recover by themselves (quality-attribute analysis drills).
4. **Graceful rollouts:**
   - `maxUnavailable: 0`, a 5-second `preStop` sleep so endpoints are removed before shutdown begins, and 45 seconds
     of termination grace;
   - two replicas per component with a PodDisruptionBudget of `minAvailable: 1`;
   - topology spread across nodes;
   - CPU autoscaling for client-api (2–10) and the dispatcher (2–8). Several dispatchers are safe because claims and
     sweeps use `SKIP LOCKED` and invoices have unique constraints.
5. **Hardened pods:**
   - non-root (UID 1001 for the JVM images, 101 for nginx), with the `RuntimeDefault` seccomp profile;
   - no privilege escalation and all capabilities dropped;
   - a read-only root filesystem with a size-limited `emptyDir` at `/tmp`;
   - no service-account token, because nothing here talks to the Kubernetes API.
6. **Secrets stay out of the chart.** It references an existing Secret (`secrets.existingSecret`) holding the six
   secret values. That Secret comes from the organisation's secrets manager (External Secrets, Sealed Secrets, Vault),
   never from values files.
7. **Default-deny network policies,** then only what each component needs:
   - **Ingress:** public ports only from the ingress controller, and management ports only from monitoring.
   - **Egress:** DNS for everyone. The Java services and the migration job reach the datastores and the telemetry
     collector, as listed in values.
   - **Providers:** the dispatcher and client-api reach providers on 443, 25, 465 and 587 anywhere **except** private,
     loopback, link-local and carrier-grade NAT ranges, plus any internal gateways listed explicitly.

   This is the network-level half of ADR-022. It closes the DNS-rebinding gap that the application check cannot,
   because a host that resolves to an internal address at connect time is still unreachable. An empty peer list is
   never rendered: Kubernetes reads `from: []` as "from anywhere", so CI fails the build if one appears.
8. **The ingress** serves three hosts: the client API, the client UI (with the API paths it calls), and the admin UI
   (with `/api`). The UIs' own nginx proxy, written for Docker's DNS, is therefore not used. admin-api trusts
   `X-Forwarded-For` (ADR-019) only when network policies are enabled, because only then can nothing but the ingress
   controller reach it.
9. **Verification:**
   - **CI:** a new required check, `Helm chart`, runs `helm lint --strict`, renders the chart with default and
     development values, validates every object with `kubeconform` against the Kubernetes schemas, and checks that no
     policy allows traffic from anywhere.
   - **Live:** the chart was installed into k3s (`deploy/k8s/dev`). A message was sent end to end through the
     ingress, and 14 connection checks confirmed the network policies, including that the dispatcher reaches a public
     host on 443 but not an internal service on 443, and that nothing outside the namespace reaches any port.

## Options considered

- **Plain manifests or Kustomize.** Fine for one environment. The chart's values (hosts, datastores, peers, sizes)
  vary per environment and per organisation, which is what Helm templating is for, and Helm hooks give the
  migration ordering for free.
- **Bundle the datastores as chart dependencies (Bitnami PostgreSQL, Redis, Pulsar).** Quick to start, but it couples
  the application's release cycle to stateful systems that need their own upgrade, backup and HA plans, and most
  production targets use managed services instead.
- **Cloud-specific Terraform now.** Requires choosing a cloud, which this repository has not done. The chart is what
  any such Terraform would install.
- **Readiness that includes the database and broker.** Takes every pod out of service during a dependency blip, which
  turns "slower for a minute" into "down for a minute".
- **A service mesh for mTLS and egress control.** Stronger, and compatible with this chart later, but it is
  infrastructure the chart cannot assume.

## Consequences

Positive: a platform team can install, upgrade and roll back the service with one command. Schema changes gate
releases. A single pod or node failure does not interrupt service. A compromised pod can reach only what its
component needs.

Negative / accepted:

- **Network policies need a network plugin that enforces them** (Calico, Cilium, the cloud's plugin). Without one
  they are silently ignored, which the install notes say.
- **`networkPolicy.datastoreEgress` must be filled in** for each environment, and nothing works until it is. That is
  the price of default deny, and the install notes warn when it is empty.
- **Images are not yet published by CI.** The chart expects them in a registry (`image.registry`). Publishing signed
  images with an SBOM is a pipeline follow-up.
- **Autoscaling uses CPU.** Scaling the dispatcher on backlog (the `notification_backlog_*` gauges, ADR-024) needs
  KEDA or a custom-metrics adapter.

## Follow-ups

- Datastore availability, backups and a tested restore (the second half of blocker 5).
- Publish images from CI, signed and with an SBOM, and pin them by digest in values.
- Optionally a PrometheusRule rendered from `deploy/observability/prometheus/notification-slo.rules.yml`.
