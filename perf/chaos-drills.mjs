// Copyright 2026 Vasantha Kumar
// Licensed under the Apache License, Version 2.0 (see LICENSE).
// @author Vasantha Kumar <vasantha.kumar@hotmail.com>
//
// Failure drills against a running Compose stack. Each drill breaks something while messages are in flight, then
// checks the promises the design makes: an accepted request is never lost, a provider outage does not fail messages,
// a broker or database outage does not stop ingest from accepting, and everything is eventually delivered.
//   node perf/chaos-drills.mjs [label]
// Env: ADMIN_URL, CLIENT_URL, CATCHER_URL (http://localhost:9000), PROJECT (Compose project name, default ns-bench),
//      ONLY (comma-separated drill names to run, default all),
//      DISPATCHER_URL (http://localhost:8082), ADMIN_USER/ADMIN_PASSWORD.
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';

const label = process.argv[2] || 'chaos';
const ADMIN = process.env.ADMIN_URL || 'http://localhost:8081'; // NOSONAR: local drill target
const CLIENT = process.env.CLIENT_URL || 'http://localhost:8080'; // NOSONAR: local drill target
const CATCHER = process.env.CATCHER_URL || 'http://localhost:9000'; // NOSONAR: local drill target
const DISPATCHER = process.env.DISPATCHER_URL || 'http://localhost:8082'; // NOSONAR: local drill target
const PROJECT = process.env.PROJECT || 'ns-bench';
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function call(method, url, headers, body) {
  const res = await fetch(url, { method, headers: { 'Content-Type': 'application/json', ...headers }, body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

function docker(...args) {
  execFileSync('docker', args, { stdio: 'pipe' }); // NOSONAR: docker is resolved from PATH on the operator's own machine
}

const container = (service) => `${PROJECT}-${service}-1`;

const login = await call('POST', ADMIN + '/api/admin/auth/login', {}, { username: process.env.ADMIN_USER || 'admin', password: process.env.ADMIN_PASSWORD || 'admin' });
const admin = { Authorization: 'Bearer ' + login.body.token };
const client = (await call('POST', ADMIN + '/api/admin/clients', admin, { name: 'chaos-' + Date.now(), allowedChannels: ['SMS'] })).body;
await call('POST', ADMIN + '/api/admin/rate-limits', admin, { scope: 'CLIENT_API', clientId: client.client.id, ratePerSecond: 100000, burst: 100000, enabled: true });
const headers = { 'X-API-Key': client.apiKey };
await sleep(12000);

let phone = 0;
function nextNumber() {
  let number = '+1416' + String(1000000 + phone++);
  while (number.endsWith('0400')) number = '+1416' + String(1000000 + phone++);
  return number;
}

const nextRecipients = (n) => Array.from({ length: n }, () => ({ recipient: nextNumber(), variables: { code: '1' } }));

async function backlog() {
  return (await call('GET', ADMIN + '/api/admin/dashboard/summary?hours=1', admin)).body.backlog;
}

async function waitEmpty(limitSeconds) {
  const start = Date.now();
  while ((await backlog()) > 0) {
    if ((Date.now() - start) / 1000 > limitSeconds) return { drained: false, seconds: limitSeconds };
    await sleep(1000);
  }
  return { drained: true, seconds: (Date.now() - start) / 1000 };
}

async function status(requestId) {
  return (await call('GET', `${CLIENT}/v1/notifications/${requestId}`, headers)).body;
}

async function deliveredAtProvider() {
  const messages = (await call('GET', CATCHER + '/messages?channel=sms')).body;
  const ids = messages.map((m) => m.messageId);
  return { total: ids.length, unique: new Set(ids).size };
}

const results = { label, date: new Date().toISOString(), drills: {} };

const only = (process.env.ONLY || '').split(',').filter(Boolean);

async function drill(name, run) {
  if (only.length > 0 && !only.includes(name)) return;
  console.log(`--- ${name}`);
  await call('DELETE', CATCHER + '/messages', {});
  await waitEmpty(600);
  const outcome = await run();
  console.log(JSON.stringify(outcome));
  results.drills[name] = outcome;
}

await drill('dispatcher-killed-mid-delivery', async () => {
  const n = 1200;
  const submit = await call('POST', CLIENT + '/v1/notifications/bulk', headers, { channel: 'SMS', templateName: 'otp-sms', recipients: nextRecipients(n) });
  await sleep(2500);
  docker('kill', container('dispatcher'));
  const killedAt = Date.now();
  await sleep(5000);
  docker('start', container('dispatcher'));
  const drained = await waitEmpty(900);
  const final = await status(submit.body.requestId);
  return { messages: n, accepted: submit.status === 202, ...drained, secondsFromKillToComplete: (Date.now() - killedAt) / 1000, finalStatus: final.status, counts: final.counts, atProvider: await deliveredAtProvider() };
});

await drill('provider-outage', async () => {
  const n = 300;
  await call('POST', CATCHER + '/admin/fail?status=503&count=1000000', {});
  const submit = await call('POST', CLIENT + '/v1/notifications/bulk', headers, { channel: 'SMS', templateName: 'otp-sms', recipients: nextRecipients(n) });
  await sleep(45000);
  const during = await status(submit.body.requestId);
  const breakers = await (await fetch(DISPATCHER + '/actuator/providerhealth')).json();
  await call('POST', CATCHER + '/admin/fail?count=0', {});
  const restoredAt = Date.now();
  const drained = await waitEmpty(300);
  const final = await status(submit.body.requestId);
  return { messages: n, countsDuringOutage: during.counts, failedDuringOutage: during.counts.failed, breakerStates: breakers, secondsFromRecoveryToComplete: (Date.now() - restoredAt) / 1000, ...drained, finalStatus: final.status, counts: final.counts, atProvider: await deliveredAtProvider() };
});

await drill('broker-down-during-ingest', async () => {
  docker('stop', container('pulsar'));
  const sent = [];
  for (let i = 0; i < 100; i++) {
    const r = await call('POST', CLIENT + '/v1/notifications', headers, { channel: 'SMS', recipient: nextRecipients(1)[0].recipient, templateName: 'otp-sms', variables: { code: '1' } });
    sent.push({ status: r.status, id: r.body?.requestId });
  }
  const acceptedWhileDown = sent.filter((s) => s.status === 202).length;
  docker('start', container('pulsar'));
  const startedAt = Date.now();
  const drained = await waitEmpty(900);
  let sentCount = 0;
  for (const s of sent.filter((x) => x.id)) if ((await status(s.id)).counts.sent === 1) sentCount++;
  return { requests: sent.length, acceptedWhileBrokerDown: acceptedWhileDown, ...drained, secondsFromBrokerStartToAllDelivered: (Date.now() - startedAt) / 1000, deliveredAfterRecovery: sentCount };
});

await drill('database-restart-during-ingest', async () => {
  const accepted = [];
  let rejected = 0;
  let firstRejectAt = null;
  let lastRejectAt = null;
  const started = Date.now();
  const restart = (async () => { await sleep(10000); docker('restart', container('postgres')); })();
  while (Date.now() - started < 60000) {
    try {
      const r = await call('POST', CLIENT + '/v1/notifications', headers, { channel: 'SMS', recipient: nextRecipients(1)[0].recipient, templateName: 'otp-sms', variables: { code: '1' } });
      if (r.status === 202) accepted.push(r.body.requestId);
      else { rejected++; firstRejectAt ??= Date.now(); lastRejectAt = Date.now(); }
    } catch {
      rejected++; firstRejectAt ??= Date.now(); lastRejectAt = Date.now();
    }
    await sleep(50);
  }
  await restart;
  const drained = await waitEmpty(900);
  let delivered = 0;
  for (const id of accepted) if ((await status(id)).counts.sent === 1) delivered++;
  return { attempts: accepted.length + rejected, accepted: accepted.length, rejected, secondsOfRejections: firstRejectAt ? (lastRejectAt - firstRejectAt) / 1000 : 0, ...drained, acceptedAndDelivered: delivered, acceptedButLost: accepted.length - delivered };
});

mkdirSync('docs/benchmarks', { recursive: true });
writeFileSync(`docs/benchmarks/${label}.json`, JSON.stringify(results, null, 2));
console.log('written docs/benchmarks/' + label + '.json');
