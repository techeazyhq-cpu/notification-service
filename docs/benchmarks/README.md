# Benchmark results

Produced on 2026-09-26; see [../quality-attributes-analysis.md](../quality-attributes-analysis.md) for the reading of them.

| File | What |
|---|---|
| `baseline-200rps.json`, `load-500rps.json`, `load-750rps.json`, `load-1000rps.json` | Single-send ingest and status-read latency at each offered rate; the 200 req/s run also includes a 10,000-recipient bulk. `*.k6.json` is the raw k6 summary |
| `encrypted-transport-200rps.json` | The 200 req/s run with TLS on PostgreSQL, Redis and Pulsar |
| `chaos-drills-1-2.jsonl` | Dispatcher-killed and provider-outage drills (one JSON result per line, in that order) |
| `chaos-drills-broker-db.json` | Broker-down and database-restart drills |

Reproduce: start the stack (`docker compose --profile app up -d --build`), then

```bash
RATE=200 node perf/run-benchmark.mjs my-run              # ingest, status reads, 10k bulk
RATE=750 SKIP_BULK=true node perf/run-benchmark.mjs my-750
node perf/chaos-drills.mjs my-drills                      # PROJECT=<compose project> if not ns-bench
```

Needs Node 20+ and Docker (k6 runs from the `grafana/k6` image). Numbers depend on the machine; compare runs from the
same machine.
