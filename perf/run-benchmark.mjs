// Copyright 2026 Vasantha Kumar
// Licensed under the Apache License, Version 2.0 (see LICENSE).
// @author Vasantha Kumar <vasantha.kumar@hotmail.com>
//
// Runs the whole benchmark against a running stack and writes docs/benchmarks/<label>.json.
//   node perf/run-benchmark.mjs <label>
// Env: ADMIN_URL (http://localhost:8081), CLIENT_URL (http://localhost:8080), ADMIN_USER/ADMIN_PASSWORD,
//      RATE (single sends per second, default 200), BULK (recipients in the bulk request, default 10000),
//      SKIP_BULK=true to measure only the single-send and status-read scenarios,
//      K6_HOST (how the k6 container reaches CLIENT_URL, default host.docker.internal).
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { hostname, cpus, totalmem } from 'node:os';

const label = process.argv[2] || new Date().toISOString().slice(0, 10);
const ADMIN = process.env.ADMIN_URL || 'http://localhost:8081'; // NOSONAR: local benchmark target
const CLIENT = process.env.CLIENT_URL || 'http://localhost:8080'; // NOSONAR: local benchmark target
const RATE = process.env.RATE || '200';
const BULK = Number(process.env.BULK || 10000);
const K6_HOST = process.env.K6_HOST || 'host.docker.internal';
const SKIP_BULK = process.env.SKIP_BULK === 'true';

async function json(method, url, headers, body) {
  const res = await fetch(url, { method, headers: { 'Content-Type': 'application/json', ...headers }, body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${url} -> ${res.status} ${text.slice(0, 200)}`);
  return text ? JSON.parse(text) : null;
}

console.log('seeding providers and templates');
execFileSync(process.execPath, ['scripts/seed.mjs'], { stdio: 'inherit', env: process.env });

const login = await json('POST', ADMIN + '/api/admin/auth/login', {}, {
  username: process.env.ADMIN_USER || 'admin', password: process.env.ADMIN_PASSWORD || 'admin',
});
const admin = { Authorization: 'Bearer ' + login.token };
const client = await json('POST', ADMIN + '/api/admin/clients', admin, { name: 'bench-' + Date.now(), allowedChannels: ['SMS'] });
const key = client.apiKey;
await json('POST', ADMIN + '/api/admin/rate-limits', admin, { scope: 'CLIENT_API', clientId: client.client.id, ratePerSecond: 100000, burst: 100000, enabled: true });
const clientHeaders = { 'X-API-Key': key };
console.log('waiting 12 s for the rate-limit policy cache (10 s) to pick up the new limit');
await new Promise((resolve) => setTimeout(resolve, 12000));

async function backlog() {
  return (await json('GET', ADMIN + '/api/admin/dashboard/summary?hours=1', admin)).backlog;
}

async function waitForEmptyBacklog() {
  const start = Date.now();
  const initial = await backlog();
  while ((await backlog()) > 0) await new Promise((resolve) => setTimeout(resolve, 1000));
  return { backlogAtStart: initial, seconds: (Date.now() - start) / 1000 };
}

console.log(`k6: ${RATE} single sends/s for 60 s, then ${RATE} status reads/s for 30 s`);
mkdirSync('docs/benchmarks', { recursive: true });
const k6Summary = `docs/benchmarks/${label}.k6.json`;
try {
  execFileSync('docker', ['run', '--rm', '-v', `${process.cwd()}/perf:/perf`, '-v', `${process.cwd()}/docs/benchmarks:/out`, // NOSONAR: docker is resolved from PATH on the operator's own machine
    '-e', `BASE_URL=http://${K6_HOST}:${new URL(CLIENT).port}`, '-e', `API_KEY=${key}`, '-e', `RATE=${RATE}`,
    'grafana/k6', 'run', '--summary-export', `/out/${label}.k6.json`, '/perf/ingest.k6.js'], { stdio: 'inherit' });
} catch (error) {
  if (error.status !== 99) throw error;
  console.warn('k6 reports a crossed threshold (exit 99); recording the numbers anyway');
}
const k6 = JSON.parse(readFileSync(k6Summary, 'utf8')).metrics;
console.log('waiting for the delivery backlog to drain');
const afterLoad = await waitForEmptyBacklog();

async function drain(requestId, total) {
  const start = Date.now();
  for (;;) {
    const v = await json('GET', `${CLIENT}/v1/notifications/${requestId}`, clientHeaders);
    if (['COMPLETED', 'PARTIALLY_FAILED', 'FAILED'].includes(v.status)) return { seconds: (Date.now() - start) / 1000, status: v.status, counts: v.counts };
    await new Promise((r) => setTimeout(r, 1000));
  }
}

let acceptSeconds = 0;
let bulkDrain = { seconds: 0, status: 'SKIPPED', counts: {} };
if (!SKIP_BULK) {
  console.log(`bulk: ${BULK} recipients in one request`);
  const recipients = Array.from({ length: BULK }, (_, i) => ({ recipient: '+1415' + String(5000000 + i), variables: { code: String(i % 1000000) } }));
  const t0 = Date.now();
  const submit = await json('POST', CLIENT + '/v1/notifications/bulk', clientHeaders, { channel: 'SMS', templateName: 'otp-sms', recipients });
  acceptSeconds = (Date.now() - t0) / 1000;
  bulkDrain = await drain(submit.requestId, BULK);
}

const result = {
  label, date: new Date().toISOString(),
  environment: { host: hostname(), cpus: cpus().length, memGiB: Math.round(totalmem() / 2 ** 30), note: 'one instance of each service, single-node Postgres, Redis and Pulsar, all in Docker Desktop on this machine' },
  singleSend: {
    targetRatePerSecond: Number(RATE), acceptedMessages: k6.accepted_messages?.count, achievedRatePerSecond: k6.iterations?.rate,
    latencyMs: k6['http_req_duration{scenario:single_send}'] || k6.http_req_duration, errorRate: k6['http_req_failed{scenario:single_send}']?.value ?? k6.http_req_failed?.value,
  },
  afterLoadDrain: afterLoad,
  statusRead: { latencyMs: k6['http_req_duration{scenario:status_read}'] },
  bulk: SKIP_BULK ? null : { recipients: BULK, acceptSeconds, acceptRecipientsPerSecond: Math.round(BULK / acceptSeconds), drainSeconds: bulkDrain.seconds, deliveredPerSecond: Math.round(BULK / bulkDrain.seconds), finalStatus: bulkDrain.status, counts: bulkDrain.counts },
};
writeFileSync(`docs/benchmarks/${label}.json`, JSON.stringify(result, null, 2));
console.log(JSON.stringify(result, null, 2));
