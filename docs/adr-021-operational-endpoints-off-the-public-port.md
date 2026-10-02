# ADR-021: Operational endpoints and admin API docs off the public port

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

`client-api` and `admin-api` served `/actuator/health` and `/actuator/prometheus` on the same port the reverse proxy
publishes (ADR-016), and admin-api's security configuration let anyone fetch them. The Prometheus output describes
the system from the inside: request rates per endpoint, connection pool sizes, circuit-breaker states per provider,
JVM and library versions. That is reconnaissance material, and none of it is meant for the internet. admin-api also
let anyone fetch its OpenAPI document and Swagger UI, which publishes a map of every administrative endpoint. The
architecture review listed this as finding S6, and the 2026-10-01 re-review as production blocker 6.

Verifying the change exposed a related defect: client-api answered every unknown path, `/actuator/*` included, with
`500` and an ERROR log entry with a stack trace, because its catch-all exception handler treated Spring's "no such
resource" as an unexpected failure. On a public API, every scanner request would have become an error-level log line.

## Decision

1. **Actuator moves to a management port.** `management.server.port` is `9080` for client-api and `9081` for
   admin-api (`MANAGEMENT_PORT`). The application ports, which Traefik routes, no longer serve `/actuator/**`.
   Exposure stays `health,info,prometheus`. The dispatcher has no public surface (ADR-016), so its only port already
   is its management port and is left as is.
2. **Publishing.** The TLS overlay publishes nothing but Traefik, so the management ports are reachable only inside
   the Docker network, where Prometheus and orchestrator probes belong. The base `docker compose` file publishes them,
   and the dispatcher port, on `127.0.0.1` only, so they stay usable on a developer's machine without being exposed
   to the local network.
3. **admin-api still applies Spring Security on its management port.** Health and metrics are allowed without a
   session; anything else answers `401`. This was checked live, not assumed.
4. **Admin API docs are off by default.** `springdoc.api-docs.enabled` and `springdoc.swagger-ui.enabled` follow
   `API_DOCS_ENABLED`, `false` for admin-api. `docker compose` turns them on (`ADMIN_API_DOCS_ENABLED`) for
   development.
5. **Client API docs stay on.** They are the published contract for API users, the client UI proxies them and its
   Playground links to them, and every documented call still needs an API key. `API_DOCS_ENABLED=false` switches them
   off where that is wanted.
6. **Framework refusals keep their status in client-api.** Spring exceptions that carry a 4xx status (unknown path,
   unsupported method or media type, missing parameter) are answered with that status and their own code, such as
   `NOT_FOUND`, and are not logged as errors. Anything else is still an opaque `500` with an ERROR log line.

## Options considered

- **Keep actuator on the public port and require authentication.** Prometheus would need credentials, probes would
  need credentials, and a misconfiguration would again expose everything on the internet. A port the proxy never
  routes fails safe.
- **Block `/actuator` in Traefik.** Works only for deployments that use this Traefik overlay. Any other ingress, or a
  direct port, would expose it again. Keeping it off the application port protects every deployment.
- **Turn the client API docs off too.** The original review suggested disabling Swagger outside development, but for
  the Client API that would remove the documentation that API users and the Playground rely on, while hiding nothing:
  the same contract is in the client UI's catalogue. Kept on, switchable.
- **Fix only `NoResourceFoundException`.** The same catch-all also turned 405, 415 and missing-parameter 400s into
  500s. Using Spring's `ErrorResponse` contract covers all of them at once.

## Consequences

Positive: nothing operational is reachable through the public route; the admin API's surface is not advertised;
unknown paths on client-api get an honest `404` without filling the error log.

Negative / accepted:

- Anything that checked health on the public port, such as an external load balancer or uptime monitor, must move to
  the management port or to an application endpoint.
- Two more ports per deployment to know about, documented in the README.

## Follow-ups

- Split liveness and readiness probe groups on the management port (review finding R5).
- The base `docker compose` file still publishes PostgreSQL, Redis, Pulsar and Mailpit on every interface with
  development credentials. That is fine for an isolated laptop, but they should be bound to `127.0.0.1` the same way.
