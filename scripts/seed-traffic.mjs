/*
 * Copyright 2026 Vasantha Kumar Kesavan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>
 */

// Loads eight weeks of synthetic hourly traffic for one client into traffic_hourly, so the anomaly pages of the admin
// UI have a baseline to show locally (ADR-039). The hour that has just closed gets an SMS spike that also reaches the
// international network code +882, which the next scoring run flags as two high-severity anomalies.
//   node scripts/seed-traffic.mjs              # for the seeded demo-app client
//   node scripts/seed-traffic.mjs --print      # print the SQL instead of running it
// Env: ADMIN_URL, ADMIN_USER, ADMIN_PASSWORD, ADMIN_OTP as for seed.mjs; CLIENT_NAME (demo-app);
//      PSQL, the command that runs psql against the local database
//      (default: docker exec -i common-postgres-1 psql -U notification -d notification -v ON_ERROR_STOP=1).
// Scoring runs at five past every hour. To see the anomalies at once, start the stack with
// ANOMALY_AGGREGATION_CRON='0 * * * * *' so it runs every minute. Local use only: it overwrites traffic_hourly rows.
import { spawnSync } from 'node:child_process';

const ADMIN = process.env.ADMIN_URL || 'http://localhost:8081'; // NOSONAR: plain-HTTP default is for local development only
const CLIENT_NAME = process.env.CLIENT_NAME || 'demo-app';
const PSQL = process.env.PSQL || 'docker exec -i common-postgres-1 psql -U notification -d notification -v ON_ERROR_STOP=1';
const WEEKS = 8;
const HOUR = 3600 * 1000;

async function clientId() {
  const login = await fetch(ADMIN + '/api/admin/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: process.env.ADMIN_USER || 'admin',
      password: process.env.ADMIN_PASSWORD || 'admin',
      verificationCode: process.env.ADMIN_OTP || null,
    }),
  });
  if (!login.ok) throw new Error(`Sign-in failed: ${login.status} ${await login.text()}`);
  const token = (await login.json()).token;
  const res = await fetch(ADMIN + '/api/admin/clients', { headers: { Authorization: 'Bearer ' + token } });
  if (!res.ok) throw new Error(`GET /clients -> ${res.status}`);
  const client = (await res.json()).find((c) => c.name === CLIENT_NAME);
  if (!client) throw new Error(`No client named ${CLIENT_NAME}; run scripts/seed.mjs first`);
  return client.id;
}

/** A small deterministic generator, so every run seeds the same shape. */
function random(seed) {
  let state = seed;
  return () => {
    state = (state * 1103515245 + 12345) % 2147483648;
    return state / 2147483648;
  };
}

/** Business hours busy, nights quiet, weekends at half: the weekly rhythm the baseline learns. */
function volume(at, base, noise) {
  const hour = at.getUTCHours();
  const weekend = at.getUTCDay() === 0 || at.getUTCDay() === 6;
  const daytime = hour >= 8 && hour < 20 ? 1 : 0.15;
  return Math.max(0, Math.round(base * daytime * (weekend ? 0.5 : 1) * (0.9 + noise() * 0.2)));
}

function row(client, channel, at, messages, destinations, noise) {
  const failed = Math.round(messages * (0.01 + noise() * 0.01));
  const newRecipients = Math.round(messages * 0.1);
  const largest = Math.max(1, Math.round(messages / 8));
  const json = JSON.stringify(destinations).replaceAll("'", "''");
  return `('${client}', '${channel}', '${at.toISOString()}', ${messages}, ${messages}, ${newRecipients}, ${failed}, `
    + `${largest}, '${json}', '{}', now())`;
}

const client = await clientId();
const noise = random(39);
const lastClosed = new Date(Math.floor(Date.now() / HOUR) * HOUR - HOUR);
const rows = [];
for (let at = new Date(lastClosed.getTime() - WEEKS * 7 * 24 * HOUR); at < lastClosed; at = new Date(at.getTime() + HOUR)) {
  const sms = volume(at, 400, noise);
  if (sms > 0) rows.push(row(client, 'SMS', at, sms, { 44: Math.round(sms * 0.9), 91: sms - Math.round(sms * 0.9) }, noise));
  const email = volume(at, 900, noise);
  if (email > 0) rows.push(row(client, 'EMAIL', at, email, { 'example.com': email }, noise));
}
const spike = Math.max(volume(lastClosed, 400, noise), 60) * 8;
rows.push(row(client, 'SMS', lastClosed, spike, { 44: spike - 300, 882: 300 }, noise));

const sql = `INSERT INTO traffic_hourly (client_id, channel, hour, messages, recipients, new_recipients, failed,
  largest_request, destinations, error_codes, computed_at) VALUES
${rows.join(',\n')}
ON CONFLICT (client_id, channel, hour) DO UPDATE SET messages = EXCLUDED.messages, recipients = EXCLUDED.recipients,
  new_recipients = EXCLUDED.new_recipients, failed = EXCLUDED.failed, largest_request = EXCLUDED.largest_request,
  destinations = EXCLUDED.destinations, error_codes = EXCLUDED.error_codes, computed_at = EXCLUDED.computed_at;
`;

if (process.argv.includes('--print')) {
  process.stdout.write(sql);
} else {
  const [command, ...args] = PSQL.split(' ');
  const result = spawnSync(command, args, { input: sql, stdio: ['pipe', 'inherit', 'inherit'] });
  if (result.status !== 0) throw new Error(`psql failed (${result.status ?? result.error})`);
  console.log(`+ ${rows.length} hourly rows for ${CLIENT_NAME}, with an SMS spike to +882 at ${lastClosed.toISOString()}`);
  console.log('  The next scoring run flags it: five past the hour, or every minute with ANOMALY_AGGREGATION_CRON=\'0 * * * * *\'.');
}
