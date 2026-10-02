# ADR-023: One trace from the API call to the provider, and structured logs

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

A message crosses three processes and a broker: client-api accepts it and publishes its id to Pulsar, the dispatcher
consumes it, and the dispatcher calls a provider, possibly several times and through failover. Nothing tied these
steps together. There were no trace or correlation ids and no MDC, and the logs were plain text. When a client
reported "request X was slow" or "never arrived", the only way in was to read database rows and grep each service's
logs by time. The architecture review listed this as finding O1, and the 2026-10-01 re-review as part of production
blocker 4. This ADR covers tracing and logs; service level objectives and alerts follow in a separate change.

## Decision

1. **Micrometer Tracing with the OpenTelemetry bridge** in notification-core, so all three services have it. Spring
   Boot then traces every inbound HTTP request and puts `traceId` and `spanId` into the logging context of the
   thread handling it.
2. **The trace crosses Pulsar in the message properties.** This project uses the plain Pulsar client, which nothing
   instruments, so it is done explicitly with Micrometer observations:
   - **Publish:** each publish is a `notification.publish` observation, started on the calling thread so it is a child
     of the request being handled. The tracing handler writes W3C `traceparent` into the message properties.
   - **Consume:** the dispatcher handles each message inside a `notification.consume` observation that reads
     `traceparent` back. Handling, its log lines and its provider calls therefore join the original trace.
   - **Retries:** Pulsar copies properties onto messages it redelivers through the retry topic, so a retry joins the
     same trace too. This was verified with an injected provider failure.
3. **Each provider call is a `notification.provider.send` span and timer,** tagged with the provider's name and type
   and the channel, and marked with the error when it fails. Trace headers are not sent to providers: they are third
   parties, and the span on our side already measures the call.
4. **Span tags carry the message id** (`notification.message_id`) as a high-cardinality value, so it appears on spans
   but never becomes a metric tag. Metrics keep only low-cardinality tags (channel, provider, type).
5. **Callers get the trace id back.** Every response from client-api, admin-api and the dispatcher carries
   `X-Trace-Id`, including refusals by security (401, 403), because the filter that sets it runs right after the one
   that starts the trace. A client quoting it lets support open the exact trace and find every log line for it.
6. **Logs are JSON by default.** `logging.structured.format.console` is `ecs` (Elastic Common Schema), `LOG_FORMAT`
   overrides it, and an empty `LOG_FORMAT` gives Spring Boot's text format, which still shows the trace id.
   `docker compose` uses text, which is easier to read in a terminal.
7. **Export only when asked.** Spans are sent over OTLP only when `MANAGEMENT_OTLP_TRACING_ENDPOINT` is set. Trace
   ids are created and logged either way. Sampling is `TRACING_SAMPLING_PROBABILITY`: 0.1 by default, 1.0 in
   `docker compose`. `docker-compose.observability.yml` adds Jaeger and points all three services at it, with its UI
   at `http://localhost:16686`.

## Options considered

- **Correlation ids only (a request id in MDC and in the Pulsar envelope).** Gives grep-ability but no timing, no
  causality across retries and failover, and nothing a tracing backend can show. OpenTelemetry gives both, and the
  same trace id doubles as the correlation id in logs.
- **The OpenTelemetry Java agent.** Instruments more (JDBC, Redis) with no code, but it is opaque, version-sensitive
  with a JDK and Spring Boot pinned as carefully as here (ADR-011, ADR-014, ADR-018), and it would still not know
  that our Pulsar message is the continuation of an HTTP request without the explicit propagation above.
- **Put the trace context in the JSON envelope.** It would change the published message contract (ADR-001's
  versioned envelope) for transport metadata that Pulsar properties exist to carry.
- **Propagate trace headers to providers.** Would let a provider's own tracing join in, but it hands internal
  identifiers to third parties for little benefit.

## Consequences

Positive:

- One trace shows a message's whole path, from the API call through publish and consume to each provider attempt,
  with timings and the failing attempt marked.
- Every log line carries the trace id, and callers receive it.
- Logs can be ingested by any log platform without parsing rules.

Negative / accepted:

- **Republished messages start new traces.** A message republished by the outbox sweeper after a broker outage,
  re-queued by an operator, or reprocessed from the dead-letter queue starts a new trace, because the original request
  is long gone and its trace context is not stored. The message id on the spans still links them.
- **Database and Redis calls are not separate spans.** Their time shows inside the surrounding spans.
- **A small cost on every publish and consume.** It was not measurable next to a database round trip at local load,
  but the sampling probability is there to turn it down.

## Follow-ups

- Service level objectives, alert rules and the metrics they need (backlog age, rate-limiter errors): the second half
  of blocker 4.
- Store the trace context on the message row if republished messages should stay in their original trace.
