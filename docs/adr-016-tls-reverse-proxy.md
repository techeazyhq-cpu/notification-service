# ADR-016: TLS via an optional Traefik reverse proxy, not baked into every service

- **Status:** Accepted
- **Date:** 2026-09-26

## Context

No service terminated TLS anywhere; `docker-compose.yml` published `client-api`, `admin-api`, `admin-ui` and
`client-ui` on plain HTTP ports. API keys, admin session tokens and every request body crossed the network in
clear — finding S3 in `docs/architecture-review.md`. At the same time, the project's own instruction for this pass
was to keep things usable with "simple CI/CD": the existing `docker compose --profile app up` workflow in the
README, and every curl example that follows it, needed to keep working unchanged for local development.

## Decision

1. **TLS lives in an optional overlay, not the base `docker-compose.yml`.** `docker-compose.tls.yml` adds a
   [Traefik](https://traefik.io) reverse proxy in front of the four public HTTP surfaces (`client-api`, `admin-api`,
   `admin-ui`, `client-ui`) and removes their direct host port publishing, so they're reachable only through it once
   this overlay is used. The base file and the plain-HTTP workflow are untouched — `docker compose --profile app up`
   still works exactly as before for anyone not opting in.
2. **`dispatcher` is deliberately not fronted.** It has no client- or admin-facing HTTP surface — only
   `/actuator/health` and `/actuator/providerhealth` for operators — which stay on the internal Docker network or a
   direct port, the same posture as `postgres`/`redis`/`pulsar` today.
3. **Works with zero configuration first.** Traefik generates a default self-signed certificate for any HTTPS
   entrypoint with no other certificate configured, so `docker compose -f docker-compose.yml -f
   docker-compose.tls.yml --profile app --profile tls up` gives working TLS immediately at
   `https://client-api.localhost`, `https://admin-api.localhost`, `https://admin.localhost` and
   `https://app.localhost` (`*.localhost` resolves to the loopback address without any DNS or hosts-file entry in
   every current browser and OS resolver) — a browser warning to accept, nothing to generate or mount by hand.
4. **Real certificates are a second, additive overlay**, `docker-compose.tls.letsencrypt.yml`, layered on top: it
   adds Traefik's ACME HTTP-01 challenge and points each router's `rule=Host(...)` at a real domain instead of the
   `*.localhost` default, requiring only `ACME_EMAIL` and the four `*_DOMAIN` variables (each with a
   `:?` required-variable guard, so a forgotten one fails the compose command immediately with a clear message
   rather than silently routing nowhere).

## Options considered

- **Terminate TLS inside each Spring Boot service** (a keystore + `server.ssl.*` properties per service). Rejected:
  four certificates and four renewal processes to manage instead of one, and it re-introduces exactly the kind of
  per-service configuration drift this pass is trying to remove elsewhere (see ADR-012, ADR-013).
- **nginx instead of Traefik.** Works as well once configured, but its config isn't naturally docker-compose-native:
  routing rules live in a separate `nginx.conf` that has to be kept in sync with the compose file by hand, and
  Let's Encrypt needs a separate `certbot` sidecar and a renewal cron. Traefik's Docker provider reads routing
  straight from container labels already in `docker-compose.yml`, and ACME renewal is built in — less to keep in
  sync for the same result.
- **A Kubernetes Ingress + cert-manager.** The eventual answer for anyone actually running this on Kubernetes, but
  this project ships Docker Compose as its only deployment target today (see ADR-001); adding a Kubernetes manifest
  set as part of a TLS change would be solving a problem this repo doesn't have yet.
- **Bake TLS into the base compose file (always on).** Rejected per the explicit constraint for this pass: keep the
  existing local-development workflow working unchanged. Forcing every contributor and CI run through a reverse
  proxy and a certificate (even a self-signed one) to hit `localhost:8080` is friction with no corresponding benefit
  for that workflow.

## Consequences

Positive: closes finding S3's TLS gap with a path that works immediately (self-signed, zero config) and scales
straight to a real deployment (Let's Encrypt, same files, one more overlay); the base development workflow is
completely unaffected; no per-service certificate or keystore management.

Negative / accepted: two more compose files to maintain alongside the base one; Traefik is one more moving part in
a real deployment, though a single well-known one; the self-signed default cert means a real browser warning during
local evaluation, which is expected and documented, not a bug; `*.localhost` routing requires the resolver behavior
most current OSes and browsers already have — an unusual, very old resolver could need a hosts-file entry instead
(the ADR and README both name the fallback).

## Follow-ups

Document the Let's Encrypt overlay's firewall/DNS prerequisites in more depth once this is used against a real host;
consider mTLS between Traefik and the backend services if they ever sit on a network that isn't fully trusted;
revisit nginx or a cloud load balancer if a deployment target other than Docker Compose is ever added.
